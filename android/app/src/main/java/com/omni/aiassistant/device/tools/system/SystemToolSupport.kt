package com.omni.aiassistant.device.tools.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs.boolean
import com.omni.aiassistant.core.agent.ToolArgs.int
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maximum number of rows any list-shaped tool result may contain. */
internal const val MAX_RESULT_ITEMS = 50

/** Maximum number of characters a tool may return in a text payload. */
internal const val MAX_RESULT_CHARS = 8_000

/** Clipboard reads are capped more aggressively than ordinary text. */
internal const val MAX_CLIPBOARD_READ_CHARS = 2_000

/**
 * Shared shell for every system tool.
 *
 * It performs the capability pre-check on the caller thread, moves all work to
 * [Dispatchers.IO], maps [SecurityException] back to a typed permission result
 * and guarantees that no ordinary exception escapes into the generation loop.
 */
internal abstract class SystemTool(
    protected val context: Context,
    protected val capabilities: CapabilityCenter,
    private val checkedCapability: Capability? = null,
    private val capabilityLabel: String = "",
    private val securityCapability: Capability? = checkedCapability,
) : Tool {

    final override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val capability = checkedCapability
        if (capability != null && !runCatching { capabilities.isGranted(capability) }.getOrDefault(false)) {
            return ToolOutcome.NeedsPermission(capability.name, capabilityLabel)
        }
        return try {
            withContext(Dispatchers.IO) { run(arguments) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            val mapped = securityCapability
            if (mapped != null) {
                ToolOutcome.NeedsPermission(mapped.name, capabilityLabel)
            } else {
                ToolOutcome.Failure("The system denied this action")
            }
        } catch (error: Exception) {
            ToolOutcome.Failure(
                error.message?.take(160)?.ifBlank { null } ?: "The action failed",
            )
        }
    }

    protected abstract suspend fun run(arguments: JsonObject): ToolOutcome
}

internal fun JsonObject.requireIntArg(key: String): Int =
    int(key) ?: throw IllegalArgumentException("Missing required integer '$key'")

internal fun JsonObject.requireBooleanArg(key: String): Boolean =
    boolean(key) ?: throw IllegalArgumentException("Missing required boolean '$key'")

internal fun JsonObject.requireTextArg(key: String): String =
    string(key) ?: throw IllegalArgumentException("Missing required argument '$key'")

/** Opens a system screen, trying [primary] first and then [fallback]. */
internal fun Context.openSystemSettings(
    primary: Intent,
    fallback: Intent? = null,
    label: String,
): ToolOutcome {
    val attempts = if (fallback == null) listOf(primary) else listOf(primary, fallback)
    for (intent in attempts) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return ToolOutcome.Success("Opened $label")
        } catch (notFound: ActivityNotFoundException) {
            // Try the next candidate screen.
        }
    }
    return ToolOutcome.Failure("No $label screen is available on this device")
}

internal fun ringerModeName(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_SILENT -> "silent"
    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
    AudioManager.RINGER_MODE_NORMAL -> "normal"
    else -> "unknown"
}

internal fun formatDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date(millis))

internal fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun formatUptime(totalSeconds: Long): String {
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1_024.0
    var index = 0
    while (value >= 1_024.0 && index < units.lastIndex) {
        value /= 1_024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
