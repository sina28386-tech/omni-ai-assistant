package com.omni.aiassistant.device.tools

import com.omni.aiassistant.core.logging.SafeLogger
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Executes a tool call with the app's security policy in one place:
 *
 * 1. unknown tool -> failure, never a silent success;
 * 2. HIGH risk always asks unless the user explicitly trusted that tool;
 * 3. MEDIUM risk asks unless auto-approval is enabled;
 * 4. every execution is time-boxed and every exception is converted into a
 *    model-readable failure instead of crashing the generation loop;
 * 5. the audit trail never stores argument values (they can contain SMS bodies
 *    or contact data) — only the tool name and a short outcome summary.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val settings: SettingsRepository,
    private val audit: ToolAuditLog,
    private val logger: SafeLogger = SafeLogger(),
) {
    val availableSpecs: List<ToolSpec> get() = registry.specs()
    val toolCount: Int get() = registry.size

    suspend fun execute(
        call: ToolCall,
        confirm: suspend (ToolConfirmation) -> Boolean,
    ): ToolOutcome {
        val tool: Tool = registry.tool(call.name)
            ?: return ToolOutcome.Failure("Unknown tool '${call.name}'").also {
                logger.warn("Tool", "Unknown tool requested: ${call.name.take(64)}")
            }

        val snapshot = settings.snapshot()
        if (!snapshot.agentMode) {
            return ToolOutcome.Disabled("Agent mode is switched off in Settings")
        }

        val trusted = call.name in snapshot.trustedTools
        val needsConfirmation = when (tool.spec.risk) {
            ToolRisk.LOW -> false
            ToolRisk.MEDIUM -> !snapshot.autoApproveMediumRisk && !trusted
            ToolRisk.HIGH -> !trusted && !(call.name == "ui_type" && snapshot.autoTypeInApps)
        }
        if (needsConfirmation && !confirm(ToolConfirmation(call, tool.spec))) {
            val outcome = ToolOutcome.Denied("The user declined this action")
            record(call, tool.spec, outcome, snapshot.toolAuditEnabled)
            return outcome
        }

        val outcome = try {
            withTimeout(TOOL_TIMEOUT_MS) {
                runCatching { tool.execute(call.arguments) }
                    .getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        logger.warn("Tool", "${call.name.take(48)} failed: ${error::class.simpleName}")
                        ToolOutcome.Failure(
                            error.message?.take(240)?.ifBlank { null }
                                ?: "${call.name} failed (${error::class.simpleName})",
                        )
                    }
            }
        } catch (timeout: TimeoutCancellationException) {
            ToolOutcome.Failure("${call.name} timed out after ${TOOL_TIMEOUT_MS / 1000}s")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }

        record(call, tool.spec, outcome, snapshot.toolAuditEnabled)
        return outcome
    }

    private suspend fun record(
        call: ToolCall,
        spec: ToolSpec,
        outcome: ToolOutcome,
        enabled: Boolean,
    ) {
        if (!enabled) return
        runCatching { audit.record(call.name, spec.risk, outcome) }
            .onFailure { logger.warn("Tool", "Audit write failed: ${it::class.simpleName}") }
    }

    companion object {
        /** Accessibility actions can legitimately take a while on a loaded device. */
        const val TOOL_TIMEOUT_MS = 20_000L
    }
}
