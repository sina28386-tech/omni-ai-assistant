package com.omni.aiassistant.device.tools

import android.content.Context
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.security.KeystoreAead
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ToolAuditEntry(
    val at: Long,
    val tool: String,
    val risk: String,
    val ok: Boolean,
    val summary: String,
)

/**
 * Rolling local audit trail, encrypted with the same Keystore-backed key as the
 * database. Arguments are never recorded; only the tool name, risk and a short
 * summary, so the log can prove what ran without becoming a second copy of the
 * user's messages.
 */
class ToolAuditLog(
    context: Context,
    private val aead: KeystoreAead,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val file = File(context.filesDir, "tool_audit.log")
    private val lock = Mutex()

    suspend fun record(tool: String, risk: ToolRisk, outcome: ToolOutcome) = withContext(Dispatchers.IO) {
        val entry = ToolAuditEntry(
            at = System.currentTimeMillis(),
            tool = tool.take(64),
            risk = risk.name,
            ok = outcome is ToolOutcome.Success,
            summary = summaryOf(outcome).take(160),
        )
        lock.withLock {
            val line = runCatching { aead.encrypt(json.encodeToString(entry)) }.getOrNull() ?: return@withLock
            file.appendText(line + "\n")
            if (file.length() > MAX_FILE_BYTES) trim()
        }
    }

    suspend fun recent(limit: Int = 100): List<ToolAuditEntry> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) return@withLock emptyList()
            file.readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<ToolAuditEntry>(aead.decrypt(line)) }.getOrNull()
                }
                .toList()
                .takeLast(limit.coerceIn(1, 500))
                .reversed()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { file.delete() }
    }

    private fun trim() {
        val kept = file.readLines().filter { it.isNotBlank() }.takeLast(MAX_ENTRIES)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private fun summaryOf(outcome: ToolOutcome): String = when (outcome) {
        is ToolOutcome.Success -> outcome.summary
        is ToolOutcome.Failure -> "failed: ${outcome.message}"
        is ToolOutcome.Denied -> "denied: ${outcome.message}"
        is ToolOutcome.NeedsPermission -> "permission required: ${outcome.label}"
        is ToolOutcome.Disabled -> "disabled: ${outcome.message}"
    }

    private companion object {
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_ENTRIES = 600
    }
}
