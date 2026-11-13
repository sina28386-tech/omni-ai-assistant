package com.omni.aiassistant.core.device

import org.junit.Assert.*
import org.junit.Test

/**
 * Deeper edge coverage for [FileSafetyPolicy]: delete target validation,
 * confirmation identity, ambiguity blocking, and play resolution.
 */
class FileSafetyPolicyEdgeTest {
    private fun record(name: String, path: String = "/vault/$name") =
        FileRecord(name = name, path = path, sizeBytes = 512L, modifiedEpochMillis = 42L)

    @Test fun deleteTargetValidationRejectsWildcardsTraversalAndVagueWords() {
        val rejected = listOf(
            "", " ", "\t\n",
            "all", "ALL", "everything", "files", "کل", "تمام", "همه", "هەموو",
            "فایلها", "فایل‌ها", "فایل ها",
            "*.mp4", "god*", "100%", "a/b", "../secret.mp4", "dir/../../x",
            "a", "ab",
            "movie", "film", "video", "photo", "picture", "image", "gallery",
            "song", "music", "audio",
            "فیلم", "عکس", "تصویر", "گالری", "آهنگ", "موزیک", "موسیقی",
        )
        rejected.forEach { target ->
            assertFalse("Expected rejection: $target", FileSafetyPolicy.isDeleteTargetValid(target))
        }
    }

    @Test fun deleteTargetValidationAcceptsConcreteIdentities() {
        val accepted = listOf(
            "abc",
            "godfather",
            "godfather 1972 bluray.mp4",
            "archive.tar.gz",
            "فیلم پدرخوانده.mp4",
            "شبح جنگل ۲.mp4",
            "فيلم قديمي.mkv",
            "می‌خواهم.mp4",
            "  godfather 1972  ",
            "GODFATHER.MP4",
        )
        accepted.forEach { target ->
            assertTrue("Expected acceptance: $target", FileSafetyPolicy.isDeleteTargetValid(target))
        }
    }

    @Test fun confirmDeleteRequiresExactIdentityIncludingUnicodeAndCase() {
        val movie = record("Godfather 1972 BluRay.MP4")
        assertTrue(FileSafetyPolicy.confirmDelete("Godfather 1972 BluRay.MP4", movie))
        assertTrue(FileSafetyPolicy.confirmDelete("godfather 1972 bluray.mp4", movie))
        assertTrue(FileSafetyPolicy.confirmDelete("GODFATHER 1972 BLURAY", movie))
        assertTrue(FileSafetyPolicy.confirmDelete("  godfather 1972 bluray.mp4  ", movie))
        assertFalse(FileSafetyPolicy.confirmDelete("godfather 1972", movie))
        assertFalse(FileSafetyPolicy.confirmDelete("godfather 1972 bluray.mkv", movie))
        assertFalse(FileSafetyPolicy.confirmDelete("godfather", movie))

        val persian = record("فيلم قديمي.mkv")
        assertTrue(FileSafetyPolicy.confirmDelete("فیلم قدیمی.mkv", persian))
        assertTrue(FileSafetyPolicy.confirmDelete("فیلم قدیمی", persian))

        val digits = record("قسمت 2.mp4")
        assertTrue(FileSafetyPolicy.confirmDelete("قسمت ۲.mp4", digits))

        val zwnj = record("می‌خواهم.mp4")
        assertTrue(FileSafetyPolicy.confirmDelete("می خواهم.mp4", zwnj))

        val archive = record("archive.tar.gz")
        assertTrue(FileSafetyPolicy.confirmDelete("archive.tar.gz", archive))
        assertTrue(FileSafetyPolicy.confirmDelete("archive.tar", archive))
        assertFalse(FileSafetyPolicy.confirmDelete("archive", archive))

        val noExtension = record("README")
        assertTrue(FileSafetyPolicy.confirmDelete("README", noExtension))
        assertTrue(FileSafetyPolicy.confirmDelete("readme", noExtension))
        assertFalse(FileSafetyPolicy.confirmDelete("README.md", noExtension))
    }

    @Test fun confirmDeleteRejectsVagueRequestsEvenWhenCandidateMatchesText() {
        assertFalse(FileSafetyPolicy.confirmDelete("همه", record("همه.mp4")))
        assertFalse(FileSafetyPolicy.confirmDelete("*.mp4", record("*.mp4")))
        assertFalse(FileSafetyPolicy.confirmDelete("فیلم", record("فیلم.mp4")))
        assertFalse(FileSafetyPolicy.confirmDelete("ab", record("ab")))
        assertFalse(
            FileSafetyPolicy.confirmDelete(
                "godfather 1972 bluray.mp4",
                record("godfather 1972 bluray.mkv"),
            ),
        )
    }

    @Test fun decideDeleteBlocksVagueEmptyAndAmbiguousMatches() {
        val target = record("godfather 1972 bluray.mp4")
        val decoy = record("godfather part 2.mkv")

        val vague = FileSafetyPolicy.decideDelete("همه", listOf(target))
        assertTrue(vague is FileSafetyPolicy.DeleteDecision.Blocked)
        assertTrue((vague as FileSafetyPolicy.DeleteDecision.Blocked).reason.contains("exact"))

        val missing = FileSafetyPolicy.decideDelete("godfather 1972", emptyList())
        assertTrue(missing is FileSafetyPolicy.DeleteDecision.Blocked)
        assertTrue((missing as FileSafetyPolicy.DeleteDecision.Blocked).reason.contains("No file"))

        val ambiguous = FileSafetyPolicy.decideDelete("godfather", listOf(target, decoy))
        assertTrue(ambiguous is FileSafetyPolicy.DeleteDecision.Blocked)
        assertTrue((ambiguous as FileSafetyPolicy.DeleteDecision.Blocked).reason.contains("Multiple"))

        assertTrue(
            FileSafetyPolicy.decideDelete("godfather", listOf(target, target))
                is FileSafetyPolicy.DeleteDecision.Blocked,
        )
        assertTrue(
            FileSafetyPolicy.decideDelete("godfather", emptyList())
                is FileSafetyPolicy.DeleteDecision.Blocked,
        )

        val needs = FileSafetyPolicy.decideDelete(
            "godfather 1972 bluray.mp4",
            listOf(target),
        ) as FileSafetyPolicy.DeleteDecision.NeedsConfirmation
        assertEquals(target, needs.candidate)
        assertEquals("/vault/godfather 1972 bluray.mp4", needs.candidate.path)
    }

    @Test fun playResolutionLaunchesOneAndCapsDisambiguationAtFive() {
        assertTrue(FileSafetyPolicy.resolvePlay(emptyList()) is FileSafetyPolicy.PlayResolution.NotFound)

        val only = record("only.mp4")
        val launch = FileSafetyPolicy.resolvePlay(listOf(only)) as FileSafetyPolicy.PlayResolution.Launch
        assertEquals(only, launch.record)

        val options = (1..6).map { record("clip $it.mp4") }
        val many = FileSafetyPolicy.resolvePlay(options) as FileSafetyPolicy.PlayResolution.Disambiguate
        assertEquals(5, many.options.size)
        assertEquals(options.take(5), many.options)

        val duplicate = FileSafetyPolicy.resolvePlay(listOf(only, only))
        assertTrue(duplicate is FileSafetyPolicy.PlayResolution.Disambiguate)
        assertEquals(2, (duplicate as FileSafetyPolicy.PlayResolution.Disambiguate).options.size)
    }
}
