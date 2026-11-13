package com.omni.aiassistant.core.device

import org.junit.Assert.*
import org.junit.Test

/** Edge coverage for [FileSearch] scoring, filtering, limits, and ordering. */
class FileSearchEdgeTest {
    private fun record(name: String, modified: Long = 0L) =
        FileRecord(name = name, path = "/vault/$name", sizeBytes = 1L, modifiedEpochMillis = modified)

    private val library = listOf(
        record("godfather.mp4", modified = 10L),
        record("Godfather Part 2.MKV", modified = 20L),
        record("the godfather.mp4", modified = 30L),
        record("mygodfather.mp4", modified = 40L),
        record("godfather theme.mp3", modified = 50L),
        record("عکس خانوادگی.JPG", modified = 60L),
        record("فيلم قديمي.mkv", modified = 70L),
    )

    @Test fun ranksExactThenWordPrefixThenSubstring() {
        val hits = FileSearch.search(FileSearch.buildIndex(library), "godfather")
        assertEquals("godfather.mp4", hits[0].record.name)
        assertEquals(100, hits[0].score)
        assertTrue(hits.map { it.record.name }.containsAll(
            listOf("godfather.mp4", "Godfather Part 2.MKV", "the godfather.mp4", "mygodfather.mp4", "godfather theme.mp3"),
        ))
        val substring = hits.first { it.record.name == "mygodfather.mp4" }
        val prefix = hits.first { it.record.name == "Godfather Part 2.MKV" }
        assertTrue(substring.score < prefix.score)
    }

    @Test fun mediaKindFiltersByExtensionCaseInsensitively() {
        val index = FileSearch.buildIndex(library)
        val videos = FileSearch.search(index, "godfather", MediaKind.VIDEO)
        assertTrue(videos.isNotEmpty())
        assertTrue(videos.all { it.record.extension in FileNameNormalize.VIDEO_EXTS })
        assertEquals("Godfather Part 2.MKV", videos.first { it.record.name.contains("Part") }.record.name)

        val audio = FileSearch.search(index, "godfather", MediaKind.AUDIO)
        assertEquals(listOf("godfather theme.mp3"), audio.map { it.record.name })

        val images = FileSearch.search(index, "عکس", MediaKind.IMAGE)
        assertEquals(listOf("عکس خانوادگی.JPG"), images.map { it.record.name })

        assertTrue(FileSearch.search(index, "godfather", MediaKind.IMAGE).isEmpty())
    }

    @Test fun normalizesPersianQueryAndIndex() {
        val index = FileSearch.buildIndex(library)
        assertEquals(1, FileSearch.search(index, "فیلم").size)
        assertEquals(1, FileSearch.search(index, "فيلم").size)
        assertEquals(1, FileSearch.search(index, "قديمي").size)
        assertEquals("فيلم قديمي.mkv", FileSearch.search(index, "قدیمی").single().record.name)
    }

    @Test fun allTokensMustMatchAndBlankQueryMatchesNothing() {
        val index = FileSearch.buildIndex(library)
        assertTrue(FileSearch.search(index, "godfather tarantino").isEmpty())
        assertEquals(1, FileSearch.search(index, "godfather theme").size)
        assertTrue(FileSearch.search(index, "   ").isEmpty())
        assertTrue(FileSearch.search(index, "\t\n").isEmpty())
        assertTrue(FileSearch.search(index, "").isEmpty())
    }

    @Test fun limitZeroOrNegativeReturnsEmptyAndPositiveTruncates() {
        val index = FileSearch.buildIndex(library)
        assertTrue(FileSearch.search(index, "godfather", limit = 0).isEmpty())
        assertTrue(FileSearch.search(index, "godfather", limit = -1).isEmpty())
        assertEquals(1, FileSearch.search(index, "godfather", limit = 1).size)
        assertEquals("godfather.mp4", FileSearch.search(index, "godfather", limit = 1).single().record.name)
    }

    @Test fun tiesBreakByRecencyThenName() {
        val tied = listOf(
            record("same b.mp4", modified = 5L),
            record("same a.mp4", modified = 5L),
            record("same c.mp4", modified = 9L),
        )
        val names = FileSearch.search(FileSearch.buildIndex(tied), "same").map { it.record.name }
        assertEquals(listOf("same c.mp4", "same a.mp4", "same b.mp4"), names)
    }

    @Test fun emptyIndexIsSafe() {
        val index = FileSearch.buildIndex(emptyList())
        assertEquals(emptyList<FileRecord>(), index.records)
        assertTrue(index.normalizedNames.isEmpty())
        assertTrue(FileSearch.search(index, "anything").isEmpty())
    }
}
