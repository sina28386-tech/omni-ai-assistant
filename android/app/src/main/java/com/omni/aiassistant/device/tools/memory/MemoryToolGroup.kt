package com.omni.aiassistant.device.tools.memory

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.data.memory.UserMemoryRepository
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's own memory.
 *
 * Arman may store a fact the user stated explicitly and recall it later, so
 * the assistant gets to know its user over time. The user can always read and
 * delete everything that is stored.
 */
class MemoryToolGroup(
    context: Context,
    capabilities: CapabilityCenter,
    private val memory: UserMemoryRepository,
) : ToolGroup {

    override fun tools(): List<Tool> = listOf(
        MemoryRememberTool(memory),
        MemoryRecallTool(memory),
        MemoryForgetTool(memory),
    )
}

private class MemoryRememberTool(
    private val memory: UserMemoryRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "memory_remember",
        description = "Store one durable fact the user explicitly told you, so you can use it " +
            "in a later conversation. Use a short snake_case key (for example " +
            "\"favorite_music\", \"colleague_name\", \"home_address\"). Only store what the " +
            "user actually said - never guess, never infer. Storing the same key again " +
            "replaces the previous value.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "key" to ToolSchema.string("Short machine key for the fact, snake_case"),
                "value" to ToolSchema.string("The fact itself, in the user's own words"),
            ),
            required = listOf("key", "value"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        val key = arguments.string("key")?.trim()
        val value = arguments.string("value")?.trim()
        if (key.isNullOrEmpty()) {
            ToolOutcome.Failure("Missing required argument 'key'")
        } else if (value.isNullOrEmpty()) {
            ToolOutcome.Failure("Missing required argument 'value'")
        } else {
            val fact = memory.remember(key, value, source = "chat")
            ToolOutcome.Success(
                summary = "Remembered \"${fact.key}\"",
                data = buildJsonObject {
                    put("key", fact.key)
                    put("value", fact.value)
                },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolOutcome.Failure("memory_remember failed (${error::class.simpleName})")
    }
}

private class MemoryRecallTool(
    private val memory: UserMemoryRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "memory_recall",
        description = "Look up what you already know about the user. Call it with a key to get " +
            "one fact, or without a key to list everything you stored. Useful before asking " +
            "the user for information they already gave you.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "key" to ToolSchema.string("Optional exact key to look up"),
            ),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        val key = arguments.string("key")?.trim()
        val facts = memory.all()
            .filter { key.isNullOrEmpty() || it.key.equals(key, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
        ToolOutcome.Success(
            summary = if (facts.isEmpty()) {
                "No stored fact matches that key."
            } else {
                "${facts.size} stored fact(s)."
            },
            data = buildJsonObject {
                put("count", facts.size)
                put("facts", buildJsonArray {
                    facts.forEach { fact ->
                        add(
                            buildJsonObject {
                                put("id", fact.id)
                                put("key", fact.key)
                                put("value", fact.value)
                                put("updatedAt", fact.updatedAt)
                            },
                        )
                    }
                })
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolOutcome.Failure("memory_recall failed (${error::class.simpleName})")
    }
}

private class MemoryForgetTool(
    private val memory: UserMemoryRepository,
) : Tool {

    override val spec = ToolSpec(
        name = "memory_forget",
        description = "Delete a stored fact about the user, either by its id or by its key. Use " +
            "it when the user asks you to forget something.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "id" to ToolSchema.string("Id of the fact to delete"),
                "key" to ToolSchema.string("Key of the fact to delete"),
            ),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        val id = arguments.string("id")?.trim()
        val key = arguments.string("key")?.trim()
        val removed = when {
            !id.isNullOrEmpty() -> memory.forget(id)
            !key.isNullOrEmpty() -> memory.forgetByKey(key) > 0
            else -> false
        }
        if (!removed && id.isNullOrEmpty() && key.isNullOrEmpty()) {
            ToolOutcome.Failure("Provide either 'id' or 'key'")
        } else {
            ToolOutcome.Success(
                summary = if (removed) "Forgotten." else "Nothing matched that reference.",
                data = buildJsonObject { put("removed", removed) },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolOutcome.Failure("memory_forget failed (${error::class.simpleName})")
    }
}
