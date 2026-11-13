package com.omni.aiassistant.core.device

import org.junit.Assert.*
import org.junit.Test

/** Edge coverage for [FileNameNormalize] and [FileRecord] derived fields. */
class FileNameNormalizeEdgeTest {
    @Test fun normalizesPersianLettersDigitsAndDiacritics() {
        assertEquals("کتاب", FileNameNormalize.normalize("كتاب"))
        assertEquals("مدرسه", FileNameNormalize.normalize("مدرسة"))
        assertEquals("فیلم", FileNameNormalize.normalize("فيلم"))
        assertEquals("محمد", FileNameNormalize.normalize("مُحَمَّد"))
        assertEquals("0123456789", FileNameNormalize.normalize("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("0123456789", FileNameNormalize.normalize("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("قسمت 2.mp4", FileNameNormalize.normalize("قسمت ۲.mp4"))
    }

    @Test fun normalizesWhitespaceJoinersAndCase() {
        assertEquals("", FileNameNormalize.normalize(""))
        assertEquals("", FileNameNormalize.normalize(" \t\n "))
        assertEquals("godfather 1972", FileNameNormalize.normalize("  Godfather\t ۱۹۷۲  "))
        assertEquals("می خواهم", FileNameNormalize.normalize("می‌خواهم"))
        assertEquals("می خواهم", FileNameNormalize.normalize("می‍خواهم"))
        assertEquals("café", FileNameNormalize.normalize("Café"))
    }

    @Test fun detectsMediaKindForWholeTokensOnly() {
        assertEquals(MediaKind.VIDEO, FileNameNormalize.mediaKindFor("MOVIE"))
        assertEquals(MediaKind.VIDEO, FileNameNormalize.mediaKindFor("فيلم"))
        assertEquals(MediaKind.IMAGE, FileNameNormalize.mediaKindFor("عكس"))
        assertEquals(MediaKind.IMAGE, FileNameNormalize.mediaKindFor("Gallery"))
        assertEquals(MediaKind.AUDIO, FileNameNormalize.mediaKindFor("موزیک"))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor("play movie"))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor("فیلم!"))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor(""))
        assertEquals(MediaKind.ANY, FileNameNormalize.mediaKindFor(null))
    }

    @Test fun extensionSetsAreLowercaseDotlessAndDisjoint() {
        val all = FileNameNormalize.VIDEO_EXTS + FileNameNormalize.IMAGE_EXTS + FileNameNormalize.AUDIO_EXTS
        assertTrue(FileNameNormalize.VIDEO_EXTS.containsAll(listOf("mp4", "mkv", "webm")))
        assertTrue(FileNameNormalize.IMAGE_EXTS.containsAll(listOf("jpg", "jpeg", "png", "heic")))
        assertTrue(FileNameNormalize.AUDIO_EXTS.containsAll(listOf("mp3", "m4a", "flac", "opus")))
        assertTrue(all.all { it == it.lowercase() && !it.startsWith(".") })
        assertEquals(all.size, FileNameNormalize.VIDEO_EXTS.size + FileNameNormalize.IMAGE_EXTS.size +
            FileNameNormalize.AUDIO_EXTS.size)
        assertNull(FileNameNormalize.extensionsFor(MediaKind.ANY))
        assertEquals(FileNameNormalize.VIDEO_EXTS, FileNameNormalize.extensionsFor(MediaKind.VIDEO))
        assertEquals(FileNameNormalize.IMAGE_EXTS, FileNameNormalize.extensionsFor(MediaKind.IMAGE))
        assertEquals(FileNameNormalize.AUDIO_EXTS, FileNameNormalize.extensionsFor(MediaKind.AUDIO))
    }

    @Test fun recordDerivesExtensionWithoutGuessing() {
        val movie = FileRecord("Movie.MP4", "/vault/Movie.MP4")
        assertEquals("mp4", movie.extension)
        assertEquals("Movie", movie.nameWithoutExtension)

        val archive = FileRecord("archive.tar.gz", "/vault/archive.tar.gz")
        assertEquals("gz", archive.extension)
        assertEquals("archive.tar", archive.nameWithoutExtension)

        val noExtension = FileRecord("README", "/vault/README")
        assertEquals("", noExtension.extension)
        assertEquals("README", noExtension.nameWithoutExtension)

        val trailingDot = FileRecord("name.", "/vault/name.")
        assertEquals("", trailingDot.extension)
        assertEquals("name", trailingDot.nameWithoutExtension)
    }
}
