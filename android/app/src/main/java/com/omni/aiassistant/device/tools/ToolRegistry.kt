package com.omni.aiassistant.device.tools

import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolSpec

/**
 * Immutable catalogue of every tool compiled into this build.
 *
 * Duplicate names are a wiring bug, not a case for "last one wins": the model
 * would see two definitions and the dispatcher could silently run the wrong
 * implementation. Fail fast during dependency construction instead.
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = buildMap {
        tools.forEach { tool ->
            val name = tool.spec.name
            require(name.isNotBlank()) { "Tool names must not be blank" }
            require(name.matches(TOOL_NAME_PATTERN)) {
                "Tool name '$name' must match ${TOOL_NAME_PATTERN.pattern}"
            }
            require(put(name, tool) == null) { "Duplicate tool name: $name" }
        }
    }

    fun tool(name: String): Tool? = byName[name]

    fun specs(): List<ToolSpec> = byName.values.map { it.spec }.sortedBy { it.category.name }

    fun all(): List<Tool> = byName.values.toList()

    val size: Int get() = byName.size

    private companion object {
        val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{1,63}$")
    }
}

/**
 * Human-facing confirmation request. The ViewModel turns this into a bottom
 * sheet; the dispatcher only knows the risk and the call.
 */
data class ToolConfirmation(
    val call: ToolCall,
    val spec: ToolSpec,
)
