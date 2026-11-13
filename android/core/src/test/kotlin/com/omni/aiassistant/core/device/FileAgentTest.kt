package com.omni.aiassistant.core.device

import org.junit.Assert.*
import org.junit.Test

class FileAgentTest {
    private fun record(name: String, modified: Long = 0L) =
        FileRecord(name = name, path = "/vault/$name", sizeBytes = 1024L, modifiedEpochMillis = modified)

    // ---------- Normalization (Persian voice-typing tolerance) ----------

    @Test fun normalizesPersianVariants() {
        assertEquals(FileNameNormalize.normalize("فيلم"), FileNameNormalize.normalize("فیلم"))
        assertEquals(FileNameNormalize.normalize("آهنگ جديد"), FileNameNormalize.normalize("آهنگ جديد"))
    }

    @Test fun normalizesDigitsAndSpacing() {
        assertEquals("godfather 1972", FileNameNormalize.normalize("Godfather  ۱۹۷۲"))
        assertEquals("می خواهم", FileNameNormalize.normalize("می‌خواهم"))
    }

    @Test fun detectsMediaKindFaAndEn() {
        assertEquals(MediaKind.VIDEO, FileNameNormalize.mediaKindFor("فیلم"))
        assertEquals(MediaKind.VIDEO, FileNameNormalize.mediaKindFor("movie"))
        assertEquals(MediaKind.IMAGE, FileNameNormalize.mediaKindFor("عکس"))
        assertEquals(MediaKind.AUDIO, FileNameNormalize.mediaKindFor("آهنگ"))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor("godfather"))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor(null))
    }

    // ---------- Search correctness ----------

    private val library = listOf(
        record("godfather 1972 bluray.mp4", modified = 300L),
        record("godfather part 2.mkv", modified = 200L),
        record("godfather theme.mp3", modified = 100L),
        record("inception 2010.mp4", modified = 400L),
        record("شبح جنگل.mp4", modified = 500L),
        record("عکس خانوادگی.jpg", modified = 600L),
    )

    @Test fun exactBeatsPrefixBeatsSubstring() {
        val index = FileSearch.buildIndex(library)
        val hits = FileSearch.search(index, "godfather")
        assertEquals(3, hits.size)
        // exact base-name match first, then recency among prefix matches
        assertEquals("godfather 1972 bluray.mp4", hits[0].record.name)
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test fun mediaFilterRestrictsToVideo() {
        val index = FileSearch.buildIndex(library)
        val hits = FileSearch.search(index, "godfather", kind = MediaKind.VIDEO)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.record.extension in setOf("mp4", "mkv") })
    }

    @Test fun persianQueryMatchesPersianName() {
        val index = FileSearch.buildIndex(library)
        val hits = FileSearch.search(index, "شبح")
        assertEquals(1, hits.size)
        assertEquals("شبح جنگل.mp4", hits[0].record.name)
    }

    @Test fun allTokensMustMatch() {
        val index = FileSearch.buildIndex(library)
        assertTrue(FileSearch.search(index, "godfather tarantino").isEmpty())
        assertEquals(1, FileSearch.search(index, "godfather bluray").size)
    }

    @Test fun emptyOrBlankQueryMatchesNothing() {
        val index = FileSearch.buildIndex(library)
        assertTrue(FileSearch.search(index, "   ").isEmpty())
    }

    // ---------- Search speed with many files ----------

    @Test fun twentyThousandFilesSearchUnderBudget() {
        val big = ArrayList<FileRecord>(20_000)
        val titles = arrayOf("godfather", "inception", "darkknight", "پدرخوانده", "شبح", "آواز", "interstellar", "تایتانیک")
        val exts = arrayOf("mp4", "mkv", "mp3", "jpg")
        for (i in 0 until 20_000) {
            big.add(record("${titles[i % titles.size]} $i.${exts[i % exts.size]}", modified = i.toLong()))
        }
        val t0 = System.nanoTime()
        val index = FileSearch.buildIndex(big)
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val s0 = System.nanoTime()
        val hits = FileSearch.search(index, "godfather", kind = MediaKind.VIDEO)
        val searchMs = (System.nanoTime() - s0) / 1_000_000
        assertTrue("expected hits, got none", hits.isNotEmpty())
        assertTrue("index build too slow: ${buildMs}ms", buildMs < 2_000)
        assertTrue("search too slow: ${searchMs}ms", searchMs < 1_500)
    }

    // ---------- Safety: delete gate ----------

    @Test fun vagueDeleteTargetsAreInvalid() {
        assertFalse(FileSafetyPolicy.isDeleteTargetValid(""))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("   "))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("همه"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("all"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("*.mp4"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("فیلم"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("ab"))
        assertTrue(FileSafetyPolicy.isDeleteTargetValid("godfather 1972 bluray.mp4"))
        assertTrue(FileSafetyPolicy.isDeleteTargetValid("godfather"))
    }

    @Test fun confirmRequiresExactIdentity() {
        val target = record("godfather 1972 bluray.mp4")
        val decoy = record("godfather part 2.mkv")
        assertTrue(FileSafetyPolicy.confirmDelete("godfather 1972 bluray.mp4", target))
        // extension may be omitted in voice commands, still exact on the base name
        assertTrue(FileSafetyPolicy.confirmDelete("godfather 1972 bluray", target))
        // wrong file, vague text, and wildcard never confirm
        assertFalse(FileSafetyPolicy.confirmDelete("godfather 1972 bluray.mp4", decoy))
        assertFalse(FileSafetyPolicy.confirmDelete("godfather", target))
        assertFalse(FileSafetyPolicy.confirmDelete("*.mp4", target))
        // Arabic ي typed instead of Persian ی still resolves to the same file
        assertTrue(FileSafetyPolicy.confirmDelete("godfather 1972 bluray.mp4".replace('ی', 'ي'), target))
    }

    @Test fun decideDeleteMatrix() {
        val target = record("godfather 1972 bluray.mp4")
        val other = record("godfather part 2.mkv")
        assertTrue(FileSafetyPolicy.decideDelete("همه", listOf(target)) is FileSafetyPolicy.DeleteDecision.Blocked)
        assertTrue(FileSafetyPolicy.decideDelete("godfather 1972", emptyList()) is FileSafetyPolicy.DeleteDecision.Blocked)
        val ambiguous = FileSafetyPolicy.decideDelete("godfather", listOf(target, other))
        assertTrue(ambiguous is FileSafetyPolicy.DeleteDecision.Blocked)
        val needs = FileSafetyPolicy.decideDelete("godfather 1972 bluray.mp4", listOf(target))
        assertTrue(needs is FileSafetyPolicy.DeleteDecision.NeedsConfirmation)
        assertEquals(target, (needs as FileSafetyPolicy.DeleteDecision.NeedsConfirmation).candidate)
    }

    // ---------- Play resolution: exact-or-ask ----------

    @Test fun playResolutionNeverOpensWrongFile() {
        val a = record("godfather 1972 bluray.mp4")
        val b = record("godfather part 2.mkv")
        assertTrue(FileSafetyPolicy.resolvePlay(emptyList()) is FileSafetyPolicy.PlayResolution.NotFound)
        val single = FileSafetyPolicy.resolvePlay(listOf(a))
        assertEquals(a, (single as FileSafetyPolicy.PlayResolution.Launch).record)
        val multi = FileSafetyPolicy.resolvePlay(listOf(a, b))
        assertEquals(2, (multi as FileSafetyPolicy.PlayResolution.Disambiguate).options.size)
    }
}
