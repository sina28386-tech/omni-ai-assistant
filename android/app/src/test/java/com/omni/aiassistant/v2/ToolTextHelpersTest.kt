package com.omni.aiassistant.v2

import com.omni.aiassistant.device.tools.comm.safeFailure
import com.omni.aiassistant.device.tools.comm.truncateTo
import com.omni.aiassistant.device.tools.files.shortSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTextHelpersTest {

    @Test
    fun truncateToKeepsShortTextAndCapsLongText() {
        assertEquals("", "".truncateTo(10))
        assertEquals("abc", "abc".truncateTo(5))
        assertEquals("abc", "abcdef".truncateTo(3))
        assertEquals("سلام", "سلام".truncateTo(10))
    }

    @Test
    fun shortSummaryCollapsesLineBreaksAndCapsAtTwoHundredCharacters() {
        assertEquals("a b", shortSummary("  a\nb  "))
        assertEquals("", shortSummary("\n\r "))
        assertEquals(200, shortSummary("x".repeat(500)).length)
    }

    @Test
    fun safeFailureKeepsPrefixAndBoundsTheDetail() {
        val failure = safeFailure("SMS", IllegalStateException("boom"))
        assertEquals("SMS: boom", failure.message)

        val missingMessage = safeFailure("SMS", IllegalStateException())
        assertEquals("SMS: IllegalStateException", missingMessage.message)

        val long = safeFailure("SMS", IllegalStateException("x".repeat(500)))
        assertEquals(165, long.message!!.length)
        assertTrue(long.message!!.startsWith("SMS: "))
    }
}
