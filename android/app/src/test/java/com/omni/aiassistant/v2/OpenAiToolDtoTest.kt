package com.omni.aiassistant.v2

import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.network.OpenAiChatRequest
import com.omni.aiassistant.core.network.OpenAiChatResponse
import com.omni.aiassistant.core.network.OpenAiFunctionCall
import com.omni.aiassistant.core.network.OpenAiMessage
import com.omni.aiassistant.core.network.OpenAiTool
import com.omni.aiassistant.core.network.OpenAiToolCall
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiToolDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    @Test
    fun requestSerializesToolDefinitionsAndToolChoice() {
        val parameters = ToolSchema.objectOf(
            properties = mapOf(
                "number" to ToolSchema.string("Recipient phone number"),
                "body" to ToolSchema.string("Message body"),
            ),
            required = listOf("number", "body"),
        )
        val spec = ToolSpec(
            name = "sms_send",
            description = "Send an SMS after confirmation",
            category = ToolCategory.COMMUNICATION,
            risk = ToolRisk.HIGH,
            parameters = parameters,
        )
        val request = OpenAiChatRequest(
            model = "mock-model",
            messages = listOf(OpenAiMessage(role = "user", content = JsonPrimitive("hello"))),
            tools = listOf(OpenAiTool(type = "function", function = spec.toOpenAiFunction())),
            toolChoice = "auto",
            parallelToolCalls = false,
        )

        val root = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        val tool = root["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        val function = tool["function"]!!.jsonObject
        assertEquals(setOf("name", "description", "parameters"), function.keys)
        assertEquals("sms_send", function["name"]!!.jsonPrimitive.content)
        assertEquals("Send an SMS after confirmation", function["description"]!!.jsonPrimitive.content)
        assertEquals(parameters, function["parameters"]!!.jsonObject)
        assertFalse("category" in function)
        assertFalse("risk" in function)

        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(false, root["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        // encodeDefaults = false must not leak unused/null request fields.
        assertFalse("stream" in root)
        assertFalse("temperature" in root)
        assertFalse("max_tokens" in root)
    }

    @Test
    fun requestOmitsToolsAndToolChoiceWhenNoToolsAreEnabled() {
        val request = OpenAiChatRequest(
            model = "mock-model",
            messages = listOf(OpenAiMessage(role = "user", content = JsonPrimitive("hi"))),
        )

        val root = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertFalse("tools" in root)
        assertFalse("tool_choice" in root)
        assertFalse("stream" in root)
    }

    @Test
    fun assistantMessageSerializesToolCallsAsStringArguments() {
        val arguments = buildJsonObject {
            put("number", "+49123")
            put("body", "hi")
        }
        val message = OpenAiMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                OpenAiToolCall(
                    id = "call_1",
                    type = "function",
                    function = OpenAiFunctionCall(
                        name = "sms_send",
                        arguments = arguments.toString(),
                    ),
                ),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(message)).jsonObject
        assertEquals("assistant", encoded["role"]!!.jsonPrimitive.content)
        assertFalse("content" in encoded)

        val call = encoded["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertFalse("index" in call)

        val function = call["function"]!!.jsonObject
        assertEquals("sms_send", function["name"]!!.jsonPrimitive.content)
        val encodedArguments = function["arguments"]!!.jsonPrimitive
        assertTrue(encodedArguments.isString)
        assertEquals(arguments, json.parseToJsonElement(encodedArguments.content).jsonObject)
    }

    @Test
    fun toolMessageSerializesRoleCallIdAndPayload() {
        val payload = buildJsonObject {
            put("ok", true)
            put("summary", "Sent")
        }
        val message = OpenAiMessage(
            role = "tool",
            content = payload,
            toolCallId = "call_1",
            name = "sms_send",
        )

        val encoded = json.parseToJsonElement(json.encodeToString(message)).jsonObject

        assertEquals("tool", encoded["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", encoded["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sms_send", encoded["name"]!!.jsonPrimitive.content)
        assertEquals(payload, encoded["content"]!!.jsonObject)
        assertFalse("tool_calls" in encoded)
    }

    @Test
    fun responseParsesAssistantToolCallsAndFinishReason() {
        val raw = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "sms_send", "arguments": "{\"body\":\"hi\"}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 3, "completion_tokens": 4}
            }
        """.trimIndent()

        val response = json.decodeFromString<OpenAiChatResponse>(raw)
        val choice = response.choices.single()
        assertEquals("tool_calls", choice.finishReason)

        val call = choice.message!!.toolCalls!!.single()
        assertEquals(0, call.index ?: -1)
        assertEquals("call_1", call.id)
        assertEquals("sms_send", call.function!!.name)
        assertEquals("{\"body\":\"hi\"}", call.function!!.arguments)
        assertEquals(3, response.usage?.promptTokens ?: -1)
        assertEquals(4, response.usage?.completionTokens ?: -1)
    }

    @Test
    fun streamingDeltaParsesPartialToolCallFragments() {
        val raw = """
            {
              "choices": [{
                "delta": {
                  "tool_calls": [{
                    "index": 0,
                    "id": "call_1",
                    "function": {"name": "sms_send", "arguments": "{\"bo"}
                  }]
                },
                "finish_reason": null
              }]
            }
        """.trimIndent()

        val response = json.decodeFromString<OpenAiChatResponse>(raw)
        val delta = response.choices.single().delta!!
        val call = delta.toolCalls!!.single()

        assertEquals(0, call.index ?: -1)
        assertEquals("call_1", call.id)
        assertEquals("sms_send", call.function!!.name)
        assertEquals("{\"bo", call.function!!.arguments)
    }

    @Test
    fun toolCallsRoundTripThroughThePersistenceJsonShape() {
        val calls = listOf(
            ToolCall(
                id = "call_1",
                name = "sms_send",
                arguments = buildJsonObject {
                    put("number", "+49123")
                    put("body", "hi")
                },
            ),
            ToolCall(id = "call_2", name = "device_status"),
        )

        val decoded = json.decodeFromString<List<ToolCall>>(json.encodeToString(calls))

        assertEquals(calls, decoded)
        assertEquals(JsonObject(emptyMap()), decoded[1].arguments)
    }
}
