package com.omni.aiassistant.core.regression

import com.omni.aiassistant.core.device.FileSafetyPolicy
import com.omni.aiassistant.core.logging.SafeLogger
import com.omni.aiassistant.core.security.PortableBackupCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the four defects the extended edge suite uncovered:
 * backslash traversal in delete targets, bulk "all files" phrasing, bearer
 * tokens surviving redaction, and empty backup payloads that could not be
 * decrypted again.
 */
class SafetyRegressionTest {

    @Test fun deleteTargetRejectsBackslashTraversal() {
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("..\\..\\secret.mp4"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("dir\\file.mp4"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid(".."))
    }

    @Test fun deleteTargetRejectsBulkPhrasingMadeOfGenericWords() {
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("همه فایل ها"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("همه فایلها"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("all the files"))
        assertFalse(FileSafetyPolicy.isDeleteTargetValid("تمام فایل ها"))
    }

    @Test fun deleteTargetStillAcceptsConcreteNames() {
        assertTrue(FileSafetyPolicy.isDeleteTargetValid("godfather 1972 bluray.mp4"))
        assertTrue(FileSafetyPolicy.isDeleteTargetValid("فیلم پدرخوانده.mp4"))
        assertTrue(FileSafetyPolicy.isDeleteTargetValid("همه چیز من.mp4"))
    }

    @Test fun loggerRedactsBearerTokensCompletely() {
        val logger = SafeLogger()
        logger.info("Net", "Authorization: Bearer sk-live-9999")
        logger.info("Net", "authorization=Basic dXNlcjpwYXNz")
        logger.info("Net", "x-api-key: abc123")

        val lines = logger.snapshot()
        assertFalse(lines.any { it.contains("sk-live-9999") })
        assertFalse(lines.any { it.contains("dXNlcjpwYXNz") })
        assertFalse(lines.any { it.contains("abc123") })
        assertTrue(lines[0].endsWith("I/Net Authorization=<redacted>"))
    }

    @Test fun loggerWithNonPositiveCapacityStillTerminates() {
        val logger = SafeLogger(capacity = 0)
        logger.info("T", "one")
        logger.info("T", "two")
        assertEquals(1, logger.snapshot().size)
    }

    @Test fun emptyBackupPayloadRoundTrips() {
        val cipher = PortableBackupCipher(iterations = 100_000)
        val password = "correct horse battery".toCharArray()
        val encrypted = cipher.encrypt(ByteArray(0), password)
        assertArrayEquals(ByteArray(0), cipher.decrypt(encrypted, "correct horse battery".toCharArray()))
    }
}
