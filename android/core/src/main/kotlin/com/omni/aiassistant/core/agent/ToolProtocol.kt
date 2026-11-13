package com.omni.aiassistant.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single function call requested by the model.
 *
 * [arguments] is always a JSON object. Providers stream the arguments as text
 * fragments; the gateway accumulates them and only emits a [ToolCall] once the
 * object parses, so executors never see partial JSON.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

enum class ToolCategory {
    DEVICE,
    COMMUNICATION,
    FILES,
    APPS,
    SYSTEM,
    SCREEN,
    MEDIA,
    WEB,
    PC,
}

/**
 * Risk drives the confirmation policy in the dispatcher:
 * - LOW: runs immediately.
 * - MEDIUM: runs immediately when the user enabled auto-approval, otherwise asks.
 * - HIGH: always asks, unless this exact tool was explicitly trusted by the user.
 */
enum class ToolRisk { LOW, MEDIUM, HIGH }

/**
 * Provider-facing description of a tool. [description] is English because every
 * supported model reasons better about English tool documentation; user-facing
 * labels live in the app module (`ToolPresentation`).
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val risk: ToolRisk,
    val parameters: JsonObject,
) {
    /** OpenAI `tools[].function` shape. */
    fun toOpenAiFunction(): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive(description),
            "parameters" to parameters,
        ),
    )
}

/**
 * Result of one tool execution. `data` is optional structured payload that is
 * serialised back to the model; `summary` is the short human-readable line the
 * chat timeline shows.
 */
sealed interface ToolOutcome {
    data class Success(
        val summary: String,
        val data: JsonElement? = null,
    ) : ToolOutcome

    data class Failure(val message: String) : ToolOutcome

    /** The user refused the confirmation sheet. */
    data class Denied(val message: String) : ToolOutcome

    /**
     * The action is implemented but a runtime Android permission is missing.
     * [permission] is an opaque key understood by the app's PermissionGateway.
     */
    data class NeedsPermission(
        val permission: String,
        val label: String,
    ) : ToolOutcome

    /** The feature is switched off in Omni settings (e.g. Accessibility). */
    data class Disabled(val message: String) : ToolOutcome

    /** Serialised for the `role: tool` message sent back to the model. */
    fun toModelPayload(): JsonObject {
        val outcome = this
        return JsonObject(
            buildMap {
                when (outcome) {
                    is Success -> {
                        put("ok", JsonPrimitive(true))
                        put("summary", JsonPrimitive(outcome.summary))
                        outcome.data?.let { put("data", it) }
                    }
                    is Failure -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive(outcome.message))
                    }
                    is Denied -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive(outcome.message))
                        put("denied", JsonPrimitive(true))
                    }
                    is NeedsPermission -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive("Permission required: ${outcome.label}"))
                        put("permission_required", JsonPrimitive(outcome.permission))
                    }
                    is Disabled -> {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive(outcome.message))
                        put("disabled", JsonPrimitive(true))
                    }
                }
            },
        )
    }
}

/** Implemented in the app module; one class per capability group. */
interface Tool {
    val spec: ToolSpec
    suspend fun execute(arguments: JsonObject): ToolOutcome
}

/**
 * A capability package. The DI layer explicitly registers each group, so a
 * missing group is a compile-time wiring decision rather than a runtime
 * discovery failure.
 */
interface ToolGroup {
    fun tools(): List<Tool>
}

/**
 * Tiny JSON-Schema builder. Keeping schemas in code (instead of reflection)
 * makes the exact contract visible at the call site and keeps R8 happy.
 */
object ToolSchema {
    fun objectOf(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
        additionalProperties: Boolean = false,
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
            put("additionalProperties", JsonPrimitive(additionalProperties))
        },
    )

    fun string(
        description: String,
        choices: List<String>? = null,
        default: String? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            choices?.let { values -> put("enum", JsonArray(values.map { JsonPrimitive(it) })) }
            default?.let { put("default", JsonPrimitive(it)) }
        },
    )

    fun integer(
        description: String,
        minimum: Int? = null,
        maximum: Int? = null,
        default: Int? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(description))
            minimum?.let { put("minimum", JsonPrimitive(it)) }
            maximum?.let { put("maximum", JsonPrimitive(it)) }
            default?.let { put("default", JsonPrimitive(it)) }
        },
    )

    fun number(description: String): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)),
    )

    fun boolean(description: String, default: Boolean? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("boolean"))
            put("description", JsonPrimitive(description))
            default?.let { put("default", JsonPrimitive(it)) }
        },
    )

    fun stringArray(description: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        ),
    )
}

/** Defensive argument readers: a model can always invent a wrong type. */
object ToolArgs {
    fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    fun JsonObject.rawString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    /**
     * Booleans are read case-insensitively ("True", "TRUE", "true" all work)
     * because models frequently capitalise them. Numbers are deliberately
     * *not* accepted: `1` and `0` are not booleans and silently coercing them
     * hides a model mistake.
     */
    fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { primitive ->
            when (primitive.content.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    fun JsonObject.stringList(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }?.takeIf { it.isNotEmpty() }

    fun JsonObject.requireString(key: String): String =
        string(key) ?: throw IllegalArgumentException("Missing required argument '$key'")

    fun JsonObject.requireInt(key: String): Int =
        int(key) ?: throw IllegalArgumentException("Missing required integer '$key'")

    /** Clamp untrusted numeric arguments instead of letting them throw. */
    fun clamp(value: Int, minimum: Int, maximum: Int): Int = value.coerceIn(minimum, maximum)

    fun JsonObject.nullSafe(key: String): JsonElement = this[key] ?: JsonNull
}
