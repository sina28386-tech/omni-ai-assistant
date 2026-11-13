package com.omni.aiassistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the command parser: an explicit "search file(s)"
 * request must reach the file index, while a plain web search keeps going to
 * the browser.
 */
class DeviceCommandParserSearchTest {

    private fun single(raw: String): DeviceAction {
        val parsed = DeviceCommandParser.parse(raw)
        requireNotNull(parsed) { "command was not recognised: $raw" }
        assertEquals(1, parsed.actions.size)
        return parsed.actions.single()
    }

    @Test fun searchFilesResolvesToTheFileIndex() {
        val action = single("/device search files godfather")
        assertTrue("expected a file search, got $action", action is DeviceAction.FindFile)
    }

    @Test fun searchFileResolvesToTheFileIndex() {
        val action = single("/device search file godfather")
        assertTrue("expected a file search, got $action", action is DeviceAction.FindFile)
    }

    @Test fun persianFileSearchResolvesToTheFileIndex() {
        val action = single("گوشی: جستجوی فایل godfather")
        assertTrue("expected a file search, got $action", action is DeviceAction.FindFile)
    }

    @Test fun plainSearchStaysAWebSearch() {
        val action = single("/device search godfather")
        assertTrue("expected a web search, got $action", action is DeviceAction.WebSearch)
    }

    @Test fun findKeepsResolvingToTheFileIndex() {
        val action = single("/device find godfather")
        assertTrue("expected a file search, got $action", action is DeviceAction.FindFile)
    }
}
