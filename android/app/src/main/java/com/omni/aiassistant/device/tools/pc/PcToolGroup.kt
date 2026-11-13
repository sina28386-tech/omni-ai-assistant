package com.omni.aiassistant.device.tools.pc

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.HermesBridge
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PC bridge tools. The constructor follows the V2 tool-group convention so the
 * DI layer can register it as
 * `PcToolGroup(androidContext(), get(), get<SettingsRepository>())`.
 *
 * There is deliberately no arbitrary `pc_run`/PowerShell tool. The model may
 * only request the fixed actions in [PC_ACTIONS], and the Python agent enforces
 * the same list again. `pc_execute` is HIGH risk because screenshot and app
 * launch act on the user's desktop.
 */
class PcToolGroup(
    context: Context,
    capabilities: CapabilityCenter,
    private val settings: SettingsRepository,
) : ToolGroup {
    override fun tools(): List<Tool> = listOf(
        PcStatusTool(settings),
        PcExecuteTool(settings),
    )
}

private val PC_ACTION_CHOICES = listOf(
    "sys_info",
    "desktop_files",
    "open_app",
    "screenshot",
    "list_processes",
    "disk_info",
)

private val PC_ACTIONS = PC_ACTION_CHOICES.toSet()

/** Apps the model may start. The Python agent has the same allowlist. */
private val SAFE_APP_CHOICES = listOf("notepad", "calc", "explorer", "mspaint")
private val SAFE_APPS = SAFE_APP_CHOICES.toSet()

private data class PcConfig(
    val url: String,
    val token: String,
)

private class PcStatusTool(
    private val settings: SettingsRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "pc_status",
        description = "Check whether the configured Hermes PC bridge is reachable and report " +
            "the PC system name, node name and number of desktop files. Requires the bridge " +
            "URL and token saved in Settings.",
        category = ToolCategory.PC,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val config = readConfig(settings).getOrElse { error ->
            return ToolOutcome.Disabled(
                error.message?.take(160) ?: "The PC bridge is not configured.",
            )
        }
        return HermesBridge.check(config.url, config.token).fold(
            onSuccess = { status ->
                ToolOutcome.Success(
                    summary = "PC bridge connected: ${status.system} (${status.node}), " +
                        "${status.desktopFiles} desktop files.",
                    data = buildJsonObject {
                        put("connected", true)
                        put("system", status.system)
                        put("node", status.node)
                        put("desktop_files", status.desktopFiles)
                    },
                )
            },
            onFailure = { error ->
                ToolOutcome.Failure(
                    "Could not reach the PC bridge: " +
                        (error.message?.take(160) ?: "network error"),
                )
            },
        )
    }
}

private class PcExecuteTool(
    private val settings: SettingsRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "pc_execute",
        description = "Run one vetted action on the configured desktop PC. Allowed actions: " +
            PC_ACTION_CHOICES.joinToString(", ") + ". There is no arbitrary shell or " +
            "PowerShell execution. Use pc_status first if the connection is unknown.",
        category = ToolCategory.PC,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "action" to ToolSchema.string(
                    "Action to run on the PC. Only these values are accepted.",
                    choices = PC_ACTION_CHOICES,
                ),
                "app" to ToolSchema.string(
                    "Application for open_app. Only these values are accepted.",
                    choices = SAFE_APP_CHOICES,
                    default = "notepad",
                ),
            ),
            required = listOf("action"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val action = arguments.string("action")?.trim()?.lowercase()
            ?: return ToolOutcome.Failure("Missing required argument 'action'.")
        if (action !in PC_ACTIONS) {
            return ToolOutcome.Failure(
                "Unsupported PC action. Allowed actions: ${PC_ACTION_CHOICES.joinToString()}.",
            )
        }

        val payload = if (action == "open_app") {
            val app = arguments.string("app")?.trim()?.lowercase() ?: "notepad"
            if (app !in SAFE_APPS) {
                return ToolOutcome.Failure(
                    "Unsupported app. Allowed apps: ${SAFE_APP_CHOICES.joinToString()}.",
                )
            }
            app
        } else {
            ""
        }

        val config = readConfig(settings).getOrElse { error ->
            return ToolOutcome.Disabled(
                error.message?.take(160) ?: "The PC bridge is not configured.",
            )
        }

        return HermesBridge.execute(
            baseUrl = config.url,
            action = action,
            payload = payload,
            token = config.token,
        ).fold(
            onSuccess = { output ->
                ToolOutcome.Success(
                    summary = "Ran '$action' on the PC.",
                    data = buildJsonObject {
                        put("action", action)
                        put("output", output.take(8_000))
                    },
                )
            },
            onFailure = { error ->
                ToolOutcome.Failure(
                    "PC action failed: " + (error.message?.take(160) ?: "network error"),
                )
            },
        )
    }
}

/**
 * Reads the live settings at execution time, not at group construction time.
 * The token never appears in an outcome message.
 */
private suspend fun readConfig(settings: SettingsRepository): Result<PcConfig> {
    return try {
        val snapshot = settings.snapshot()
        val url = HermesBridge.normalizeBaseUrl(snapshot.hermesUrl)
        val token = snapshot.hermesToken.trim()
        when {
            url == null -> Result.failure(
                IllegalStateException(
                    "The PC bridge URL is invalid. Set an http(s) address in Settings.",
                ),
            )

            token.isEmpty() -> Result.failure(
                IllegalStateException(
                    "The PC bridge token is missing. Add it in Settings before using PC tools.",
                ),
            )

            else -> Result.success(PcConfig(url, token))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(
            IllegalStateException(
                error.message?.take(160) ?: "Could not read the PC bridge settings.",
            ),
        )
    }
}
