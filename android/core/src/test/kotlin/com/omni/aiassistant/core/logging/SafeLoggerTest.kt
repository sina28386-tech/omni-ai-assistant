package com.omni.aiassistant.core.logging

import org.junit.Assert.*
import org.junit.Test

/** Edge coverage for [SafeLogger] redaction, truncation, ordering, and capacity. */
class SafeLoggerTest {
    private fun message(line: String): String = line.substringAfter(' ').substringAfter(' ')

    @Test fun redactsAuthorizationAndApiKeyLabels() {
        val logger = SafeLogger()
        logger.info("Net", "apiKey=sk-secret-123")
        logger.info("Net", "api_key: sk-secret-456")
        logger.info("Net", "api-key = sk-secret-789, retry=1")
        logger.info("Net", "API KEY=sk-secret-000")
        logger.info("Net", "authorization: topsecret")

        val lines = logger.snapshot()
        assertEquals(5, lines.size)
        assertFalse(lines.any { it.contains("sk-secret") })
        assertFalse(lines.any { it.contains("topsecret") })
        assertTrue(lines[0].endsWith("I/Net apiKey=<redacted>"))
        assertTrue(lines[4].endsWith("I/Net authorization=<redacted>"))
    }

    @Test fun redactsEveryOccurrenceAndLeavesOrdinaryFieldsAlone() {
        val logger = SafeLogger()
        logger.warn("Net", "apiKey=a Authorization: b model=gpt-4 temperature=0.7")
        val text = logger.snapshot().single()
        assertTrue(text.contains("W/Net"))
        assertFalse(text.contains("Authorization: b"))
        assertFalse(text.contains("apiKey=a"))
        assertTrue(text.contains("model=gpt-4 temperature=0.7"))
        assertEquals(2, Regex("<redacted>").findAll(text).count())
    }

    @Test fun keepsSeverityTagAndTimestamp() {
        val logger = SafeLogger()
        logger.info("Tag", "one")
        logger.warn("Tag", "two")
        logger.error("Tag", "three")
        val lines = logger.snapshot()
        assertTrue(lines[0].contains(" I/Tag one"))
        assertTrue(lines[1].contains(" W/Tag two"))
        assertTrue(lines[2].contains(" E/Tag three"))
        lines.forEach { line ->
            assertTrue("Missing numeric timestamp: $line", line.substringBefore(' ').toLong() > 0L)
        }
    }

    @Test fun truncatesSanitizedMessagesAtTwelveHundredChars() {
        val logger = SafeLogger()
        logger.info("T", "x".repeat(1300))
        assertEquals(1200, message(logger.snapshot().single()).length)

        val logger2 = SafeLogger()
        logger2.info("T", "a".repeat(1190) + " apiKey=secret")
        val text = message(logger2.snapshot().single())
        assertEquals(1200, text.length)
        assertFalse(text.contains("secret"))
    }

    @Test fun capacityEvictsOldestAndSnapshotIsStable() {
        val logger = SafeLogger(capacity = 2)
        logger.info("T", "a")
        logger.info("T", "b")
        val firstSnapshot = logger.snapshot()
        logger.info("T", "c")
        val secondSnapshot = logger.snapshot()

        assertEquals(2, firstSnapshot.size)
        assertEquals(2, secondSnapshot.size)
        assertTrue(firstSnapshot[0].endsWith(" a"))
        assertTrue(secondSnapshot[0].endsWith(" b"))
        assertTrue(secondSnapshot[1].endsWith(" c"))
    }
}
