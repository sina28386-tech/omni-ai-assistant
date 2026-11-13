package com.omni.aiassistant.feature.chat

import com.omni.aiassistant.core.agent.ToolOutcome
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Per-turn guard: no screen text is saved, and a real UI action resets progress. */
internal class ScreenProgressGuard {
    private var snapshot: List<Byte>? = null
    private var sameReads = 0
    private var reads = 0
    private var failures = 0

    fun record(name: String, outcome: ToolOutcome): Boolean {
        if (name != "screen_read" && !name.startsWith("ui_")) return false
        if (outcome !is ToolOutcome.Success) return ++failures >= 3
        if (name == "screen_read") {
            val data = outcome.data as? JsonObject
            if (data != null) {
                val stable = JsonObject(data.filterKeys { it != "timestamp" }).toString()
                val hash = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray()).toList()
                sameReads = if (hash == snapshot) sameReads + 1 else 1
                snapshot = hash
            }
            return ++reads >= 6 || sameReads >= 3
        }
        snapshot = null; sameReads = 0; reads = 0; failures = 0
        return false
    }
}
