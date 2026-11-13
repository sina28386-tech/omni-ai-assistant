package com.omni.aiassistant.feature.chat

import com.omni.aiassistant.core.agent.ToolOutcome
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenProgressGuardTest {
    @Test fun repeatedScreenReadsStopEvenWhenTimestampsChange() {
        val guard = ScreenProgressGuard()
        fun read(t: Int) = ToolOutcome.Success("read", buildJsonObject { put("timestamp", t); put("nodes", JsonPrimitive("same")) })
        assertFalse(guard.record("screen_read", read(1)))
        assertFalse(guard.record("screen_read", read(2)))
        assertTrue(guard.record("screen_read", read(3)))
    }
    @Test fun realUiActionResetsFailureCounter() {
        val guard = ScreenProgressGuard()
        assertFalse(guard.record("ui_tap", ToolOutcome.Failure("failed")))
        assertFalse(guard.record("ui_tap", ToolOutcome.Failure("failed")))
        assertFalse(guard.record("ui_tap", ToolOutcome.Success("tapped")))
        assertFalse(guard.record("ui_tap", ToolOutcome.Failure("failed")))
    }
}
