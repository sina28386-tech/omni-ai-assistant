package com.omni.aiassistant.v2

import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.network.OpenAiChatResponse
import com.omni.aiassistant.core.network.OpenAiChoice
import com.omni.aiassistant.core.network.OpenAiCompatibleGateway
import com.omni.aiassistant.core.network.OpenAiDelta
import com.omni.aiassistant.core.network.OpenAiFunctionCall
import com.omni.aiassistant.core.network.OpenAiResponseMessage
import com.omni.aiassistant.core.network.OpenAiToolCall
import com.omni.aiassistant.core.network.OpenAiUsage
import com.omni.aiassistant.core.network.RetrofitServiceFactory
import com.omni.aiassistant.domain.model.AiStreamEvent
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiToolCallGatewayTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    private lateinit var server: MockWebServer
    private lateinit var gateway: OpenAiCompatibleGateway
    private lateinit var profile: ProviderProfile

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5L, TimeUnit.SECONDS)
            .readTimeout(5L, TimeUnit.SECONDS)
            .build()
        gateway = OpenAiCompatibleGateway(
            retrofitFactory = RetrofitServiceFactory(client, json),
            baseClient = client,
            json = json,
            platformCleartextAllowed = true,
        )
        profile = ProviderProfile(
            id = "mock",
            name = "Mock",
            type = ProviderType.CUSTOM,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "",
            modelId = "mock-model",
            organizationId = null,
            systemPrompt = "",
            temperature = 0.2,
            maxOutputTokens = 256,
            timeoutSeconds = 10,
            supportsVision = false,
            supportsStreaming = true,
            enabled = true,
            customHeaders = emptyMap(),
            endpointPath = "/chat/completions",
            tokenParameter = TokenParameter.AUTO,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun completeSendsToolSchemasAndPairedToolTranscript() = runBlocking {
        server.enqueue(jsonResponse(chatResponse(content = "done")))

        val arguments = buildJsonObject {
            put("number", "+49123")
            put("body", "hi")
        }
        val call = ToolCall(id = "call_1", name = "sms_send", arguments = arguments)
        val completion = gateway.complete(
            profile = profile,
            systemPrompt = "System prompt",
            messages = listOf(
                message("u1", MessageRole.USER, "Send hi"),
                message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(call)),
                message(
                    "t1",
                    MessageRole.TOOL,
                    """{"ok":true}""",
                    toolCallId = "call_1",
                    toolName = "sms_send",
                ),
            ),
            localDevelopmentEnabled = true,
            tools = listOf(smsSpec()),
        )

        assertEquals("done", completion.text)
        val root = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "tool"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )

        val assistant = messages[2]
        assertFalse("content" in assistant)
        val assistantCall = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", assistantCall["id"]!!.jsonPrimitive.content)
        assertEquals("function", assistantCall["type"]!!.jsonPrimitive.content)
        assertFalse("index" in assistantCall)

        val function = assistantCall["function"]!!.jsonObject
        assertEquals("sms_send", function["name"]!!.jsonPrimitive.content)
        assertTrue(function["arguments"]!!.jsonPrimitive.isString)
        assertEquals(
            arguments,
            json.parseToJsonElement(function["arguments"]!!.jsonPrimitive.content).jsonObject,
        )

        val tool = messages[3]
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", tool["content"]!!.jsonPrimitive.content)
        // Strict OpenAI-compatible providers reject a legacy `name` field on
        // role=tool messages; the call id is the only required reference.
        assertFalse("name" in tool)

        val toolDefinition = root["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", toolDefinition["type"]!!.jsonPrimitive.content)
        assertEquals(
            "sms_send",
            toolDefinition["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun completeDropsToolMessagesThatHaveNoCallId() = runBlocking {
        server.enqueue(jsonResponse(chatResponse(content = "ok")))

        gateway.complete(
            profile = profile,
            systemPrompt = "",
            messages = listOf(
                message("u1", MessageRole.USER, "hello"),
                message("orphan", MessageRole.TOOL, "orphan payload", toolName = "sms_send"),
                message("a1", MessageRole.ASSISTANT, "answer"),
            ),
            localDevelopmentEnabled = true,
            tools = emptyList(),
        )

        val root = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val roles = root["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }

        assertEquals(listOf("user", "assistant"), roles)
        assertFalse("tools" in root)
    }

    @Test
    fun completeAccumulatesFragmentedToolCallsAndUsage() = runBlocking {
        val response = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    message = OpenAiResponseMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            OpenAiToolCall(
                                index = 0,
                                id = "call_1",
                                type = "function",
                                function = OpenAiFunctionCall(
                                    name = "sms_send",
                                    arguments = "{\"number\":\"+49",
                                ),
                            ),
                            OpenAiToolCall(
                                index = 0,
                                function = OpenAiFunctionCall(arguments = "123\",\"body\":\"hi\"}"),
                            ),
                        ),
                    ),
                ),
            ),
            usage = OpenAiUsage(promptTokens = 11, completionTokens = 7),
        )
        server.enqueue(jsonResponse(response))

        val completion = gateway.complete(
            profile = profile,
            systemPrompt = "",
            messages = listOf(message("u1", MessageRole.USER, "hi")),
            localDevelopmentEnabled = true,
            tools = emptyList(),
        )

        assertEquals("", completion.text)
        val call = completion.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("sms_send", call.name)
        assertEquals("+49123", call.arguments["number"]!!.jsonPrimitive.content)
        assertEquals("hi", call.arguments["body"]!!.jsonPrimitive.content)
        assertEquals(11, completion.usage?.promptTokens ?: -1)
        assertEquals(7, completion.usage?.completionTokens ?: -1)
    }

    @Test
    fun completeOrdersCallsByIndexAndRepairsMissingIdsAndBadArguments() = runBlocking {
        val response = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    message = OpenAiResponseMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            OpenAiToolCall(
                                index = 1,
                                id = "call_b",
                                type = "function",
                                function = OpenAiFunctionCall(name = "second", arguments = "{}"),
                            ),
                            OpenAiToolCall(
                                index = 0,
                                function = OpenAiFunctionCall(name = "first", arguments = "{broken"),
                            ),
                            OpenAiToolCall(
                                index = 2,
                                id = "call_c",
                                type = "function",
                                function = OpenAiFunctionCall(arguments = "{}"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        server.enqueue(jsonResponse(response))

        val completion = gateway.complete(
            profile = profile,
            systemPrompt = "",
            messages = listOf(message("u1", MessageRole.USER, "hi")),
            localDevelopmentEnabled = true,
            tools = emptyList(),
        )

        assertEquals(listOf("first", "second"), completion.toolCalls.map { it.name })
        assertEquals("call_0_first", completion.toolCalls[0].id)
        assertEquals("call_b", completion.toolCalls[1].id)
        assertEquals(JsonObject(emptyMap()), completion.toolCalls[0].arguments)
        assertEquals(JsonObject(emptyMap()), completion.toolCalls[1].arguments)
    }

    @Test
    fun streamEmitsAccumulatedToolCallsOnceOnFinishReason() = runBlocking {
        val firstChunk = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    delta = OpenAiDelta(
                        role = "assistant",
                        content = "Working",
                        toolCalls = listOf(
                            OpenAiToolCall(
                                index = 0,
                                id = "call_1",
                                type = "function",
                                function = OpenAiFunctionCall(
                                    name = "sms_send",
                                    arguments = "{\"body\":\"",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val secondChunk = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    delta = OpenAiDelta(
                        toolCalls = listOf(
                            OpenAiToolCall(
                                index = 0,
                                function = OpenAiFunctionCall(arguments = "hi\"}"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val finishChunk = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(delta = OpenAiDelta(), finishReason = "tool_calls"),
            ),
        )
        val usageChunk = OpenAiChatResponse(
            choices = emptyList(),
            usage = OpenAiUsage(promptTokens = 3, completionTokens = 4),
        )
        server.enqueue(
            sseResponse(
                sse(firstChunk) + sse(secondChunk) + sse(finishChunk) + sse(usageChunk) + SSE_DONE,
            ),
        )

        val events = withTimeout(10_000) {
            gateway.stream(
                profile = profile,
                systemPrompt = "System",
                messages = listOf(message("u1", MessageRole.USER, "hi")),
                localDevelopmentEnabled = true,
                tools = listOf(smsSpec()),
            ).toList()
        }

        assertEquals(
            listOf("Working"),
            events.filterIsInstance<AiStreamEvent.Delta>().map { it.text },
        )
        val ready = events.filterIsInstance<AiStreamEvent.ToolCallsReady>()
        assertEquals(1, ready.size)
        val call = ready.single().calls.single()
        assertEquals("call_1", call.id)
        assertEquals("sms_send", call.name)
        assertEquals("hi", call.arguments["body"]!!.jsonPrimitive.content)

        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
        assertEquals(3, completed.usage?.promptTokens ?: -1)
        assertEquals(4, completed.usage?.completionTokens ?: -1)
        assertTrue(events.last() is AiStreamEvent.Completed)
    }

    @Test
    fun streamFallsBackWhenFinishReasonNeverArrivesAndSkipsMalformedChunks() = runBlocking {
        val firstChunk = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    delta = OpenAiDelta(
                        toolCalls = listOf(
                            OpenAiToolCall(
                                index = 0,
                                id = "call_1",
                                type = "function",
                                function = OpenAiFunctionCall(
                                    name = "device_status",
                                    arguments = "{}",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        server.enqueue(
            sseResponse("data: {not-json}\n\n" + sse(firstChunk) + SSE_DONE),
        )

        val events = withTimeout(10_000) {
            gateway.stream(
                profile = profile,
                systemPrompt = "",
                messages = listOf(message("u1", MessageRole.USER, "status")),
                localDevelopmentEnabled = true,
                tools = emptyList(),
            ).toList()
        }

        val ready = events.filterIsInstance<AiStreamEvent.ToolCallsReady>()
        assertEquals(1, ready.size)
        assertEquals("device_status", ready.single().calls.single().name)
        assertTrue(events.last() is AiStreamEvent.Completed)
    }

    private fun chatResponse(
        content: String? = null,
        toolCalls: List<OpenAiToolCall>? = null,
    ): OpenAiChatResponse = OpenAiChatResponse(
        choices = listOf(
            OpenAiChoice(
                message = OpenAiResponseMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                ),
            ),
        ),
    )

    private fun jsonResponse(response: OpenAiChatResponse): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(json.encodeToString(response))

    private fun sse(response: OpenAiChatResponse): String =
        "data: ${json.encodeToString(response)}\n\n"

    private fun sseResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    private fun smsSpec(): ToolSpec = ToolSpec(
        name = "sms_send",
        description = "Send an SMS",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "number" to ToolSchema.string("Number"),
                "body" to ToolSchema.string("Body"),
            ),
            required = listOf("number", "body"),
        ),
    )

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
    ): ChatMessage = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        content = content,
        status = status,
        sortIndex = 1L,
        createdAt = 1L,
        updatedAt = 1L,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        toolName = toolName,
    )

    private companion object {
        const val SSE_DONE = "data: [DONE]\n\n"
    }
}
