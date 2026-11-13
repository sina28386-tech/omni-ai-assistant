package com.omni.aiassistant.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDictationTest {
    @Test fun addsRecognizedWordsToTheCurrentDraft() {
        assertEquals("سلام hello world", appendDictation("سلام ", " hello world "))
        assertEquals("متن فارسی", appendDictation("", "متن فارسی"))
        assertEquals("unfinished draft  ", appendDictation("unfinished draft  ", "  "))
    }
}
