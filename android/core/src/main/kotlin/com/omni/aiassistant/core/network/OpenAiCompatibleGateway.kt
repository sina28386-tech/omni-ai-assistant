package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.logging.SafeLogger
import com.omni.aiassistant.domain.model.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible gateway for every JVM platform (Android + desktop).
 * Uses java.util.Base64 instead of the Android framework encoder.
 */
class OpenAiCompatibleGateway(
    private val retrofitFactory: RetrofitServiceFactory,
    private val baseClient: OkHttpClient,
    private val json: Json,
    private val attachmentSource: AttachmentByteSource? = null,
    private val logger: SafeLogger = SafeLogger(),
    /** Platform policy: true only for Android debug builds and for desktop local development. */
    private val platformCleartextAllowed: Boolean = false,
) : AiProviderGateway {

    /** Platform hook supplying attachment bytes from a stored path (sandboxed per platform). */
    fun interface AttachmentByteSource {
        fun read(localPath: String): ByteArray
    }

    override suspend fun complete(
        profile: ProviderProfile,
        systemPrompt: String,
        messages: List<ChatMessage>,
        localDevelopmentEnabled: Boolean,
        tools: List<ToolSpec>,
    ): AiCompletion {
        EndpointValidator.validate(profile, localDevelopmentEnabled, platformCleartextAllowed)
        return withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(profile, systemPrompt, messages, streaming = false, tools = tools)
                val response = retrofitFactory.api(profile.timeoutSeconds).chat(
                    EndpointValidator.buildEndpoint(profile.baseUrl, profile.endpointPath),
                    headers(profile),
                    request,
                )
                if (!response.isSuccessful) throw parseHttpError(response.code(), response.errorBody()?.string())
                val body = response.body() ?: throw AppError.Provider("Provider returned an empty response")
                body.error?.let { throw ApiErrorMapper.fromHttp(response.code(), it.message) }
                val message = body.choices.firstOrNull()?.message
                    ?: throw AppError.Provider("Provider response contained no assistant message")
                val calls = ToolCallAccumulator(json).apply { accept(message.toolCalls) }.build()
                val text = message.content.orEmpty()
                if (text.isBlank() && calls.isEmpty()) {
                    throw AppError.Provider("Provider response contained no assistant message")
                }
                AiCompletion(text, body.usage.toDomain(), calls)
            } catch (t: Throwable) {
                val mapped = ApiErrorMapper.fromThrowable(t)
                logger.warn("AI", "${profile.type}: ${mapped::class.simpleName}")
                throw mapped
            }
        }
    }

    override fun stream(
        profile: ProviderProfile,
        systemPrompt: String,
        messages: List<ChatMessage>,
        localDevelopmentEnabled: Boolean,
        tools: List<ToolSpec>,
    ): Flow<AiStreamEvent> = channelFlow {
        EndpointValidator.validate(profile, localDevelopmentEnabled, platformCleartextAllowed)
        val requestDto = buildRequest(profile, systemPrompt, messages, streaming = true, tools = tools)
        val client = baseClient.newBuilder()
            .connectTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val requestBody = withContext(Dispatchers.Default) {
            json.encodeToString(requestDto).toRequestBody(JSON_MEDIA)
        }
        val httpRequest = Request.Builder()
            .url(EndpointValidator.buildEndpoint(profile.baseUrl, profile.endpointPath))
            .headers(okhttp3.Headers.headersOf(*headers(profile).flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(requestBody)
            .build()
        val call = client.newCall(httpRequest)
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw parseHttpError(response.code, response.body?.string())
                    val source = response.body?.source() ?: throw AppError.Provider("Provider returned an empty streaming response")
                    var finalUsage: AiUsage? = null
                    var toolCallsEmitted = false
                    val accumulator = ToolCallAccumulator(json)
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isBlank()) continue
                        if (payload == "[DONE]") break
                        val chunk = try {
                            json.decodeFromString<OpenAiChatResponse>(payload)
                        } catch (error: Throwable) {
                            logger.warn("SSE", "Skipped malformed provider chunk: ${error::class.simpleName}")
                            continue
                        }
                        chunk.error?.let { throw AppError.Provider(it.message ?: "Provider streaming error") }
                        chunk.usage?.toDomain()?.let { finalUsage = it }
                        chunk.choices.forEach { choice ->
                            choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { send(AiStreamEvent.Delta(it)) }
                            choice.delta?.toolCalls?.let { accumulator.accept(it) }
                            if (choice.finishReason == "tool_calls" && !toolCallsEmitted) {
                                val calls = accumulator.build()
                                if (calls.isNotEmpty()) {
                                    toolCallsEmitted = true
                                    send(AiStreamEvent.ToolCallsReady(calls))
                                }
                            }
                        }
                    }
                    // Some compatible servers never send finish_reason. Emit any
                    // accumulated calls exactly once before completing.
                    if (!toolCallsEmitted) {
                        val calls = accumulator.build()
                        if (calls.isNotEmpty()) {
                            toolCallsEmitted = true
                            send(AiStreamEvent.ToolCallsReady(calls))
                        }
                    }
                    send(AiStreamEvent.Completed(finalUsage))
                    close()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val mapped = ApiErrorMapper.fromThrowable(t)
                logger.warn("AI", "stream ${profile.type}: ${mapped::class.simpleName}")
                close(mapped)
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    override suspend fun testConnection(profile: ProviderProfile, localDevelopmentEnabled: Boolean): AiCompletion {
        val testProfile = profile.copy(maxOutputTokens = minOf(profile.maxOutputTokens, 16), temperature = 0.0)
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = "connection-test",
            conversationId = "connection-test",
            role = MessageRole.USER,
            content = "Reply with OK.",
            status = MessageStatus.COMPLETE,
            sortIndex = 1,
            createdAt = now,
            updatedAt = now,
        )
        return complete(testProfile, "", listOf(message), localDevelopmentEnabled, emptyList())
    }

    private suspend fun buildRequest(
        profile: ProviderProfile,
        systemPrompt: String,
        messages: List<ChatMessage>,
        streaming: Boolean,
        tools: List<ToolSpec>,
    ): OpenAiChatRequest {
        val dtoMessages = mutableListOf<OpenAiMessage>()
        systemPrompt.trim().takeIf { it.isNotEmpty() }?.let {
            dtoMessages += OpenAiMessage("system", JsonPrimitive(it))
        }
        for (message in messages) {
            when (message.role) {
                MessageRole.SYSTEM -> Unit
                MessageRole.TOOL -> {
                    // A tool result must always be paired with its call id,
                    // otherwise OpenAI-compatible servers reject the transcript.
                    val callId = message.toolCallId ?: continue
                    dtoMessages += OpenAiMessage(
                        role = "tool",
                        content = JsonPrimitive(message.content),
                        toolCallId = callId,
                        // Not part of the standard OpenAI tool-message schema:
                        // strict compatible providers reject it. The id already
                        // identifies the call and its function name lives in the
                        // preceding assistant tool_calls array.
                        name = null,
                    )
                }
                MessageRole.ASSISTANT -> {
                    if (message.status != MessageStatus.COMPLETE) continue
                    if (message.content.isBlank() && message.toolCalls.isEmpty()) continue
                    dtoMessages += OpenAiMessage(
                        role = "assistant",
                        content = message.content.takeIf { it.isNotBlank() }?.let(::JsonPrimitive),
                        toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { it.toDto() },
                    )
                }
                MessageRole.USER -> dtoMessages += OpenAiMessage(
                    role = "user",
                    content = contentElement(message),
                )
            }
        }
        val openAiReasoningStyle = profile.type == ProviderType.OPENAI && (
            profile.modelId.startsWith("o", ignoreCase = true) || profile.modelId.startsWith("gpt-5", ignoreCase = true)
        )
        val useCompletion = when (profile.tokenParameter) {
            TokenParameter.MAX_COMPLETION_TOKENS -> true
            TokenParameter.MAX_TOKENS -> false
            TokenParameter.AUTO -> openAiReasoningStyle
        }
        val streamOptionsSupported = profile.type in setOf(
            ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.NVIDIA, ProviderType.GROQ,
        )
        val toolDtos = tools.takeIf { it.isNotEmpty() }?.map {
            OpenAiTool(type = "function", function = it.toOpenAiFunction())
        }
        return OpenAiChatRequest(
            model = profile.modelId,
            messages = dtoMessages,
            temperature = if (openAiReasoningStyle) null else profile.temperature,
            stream = streaming,
            maxTokens = if (useCompletion) null else profile.maxOutputTokens,
            maxCompletionTokens = if (useCompletion) profile.maxOutputTokens else null,
            streamOptions = if (streaming && streamOptionsSupported) StreamOptions() else null,
            tools = toolDtos,
            toolChoice = if (toolDtos != null) "auto" else null,
        )
    }

    private suspend fun contentElement(message: ChatMessage): JsonElement {
        if (message.attachments.isEmpty()) return JsonPrimitive(message.content)
        if (message.role != MessageRole.USER) return JsonPrimitive(message.content)

        val elements = mutableListOf<JsonElement>()
        if (message.content.isNotBlank()) {
            elements += buildJsonObject {
                put("type", "text")
                put("text", message.content)
            }
        }
        for (attachment in message.attachments) {
            val encoded = withContext(Dispatchers.IO) {
                val source = attachmentSource ?: throw AppError.Validation("Attachment storage is unavailable")
                val bytes = source.read(attachment.localPath)
                try {
                    if (bytes.size > 8 * 1024 * 1024) {
                        throw AppError.Validation("Attachment exceeds safe upload size")
                    }
                    Base64.getEncoder().encodeToString(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            elements += buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", "data:${attachment.mimeType};base64,$encoded")
                }
            }
        }
        return JsonArray(elements)
    }

    private fun ToolCall.toDto(): OpenAiToolCall = OpenAiToolCall(
        id = id,
        type = "function",
        function = OpenAiFunctionCall(name = name, arguments = arguments.toString()),
    )

    private fun headers(profile: ProviderProfile): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer ${profile.apiKey}")
        if (profile.type == ProviderType.OPENAI && !profile.organizationId.isNullOrBlank()) {
            if (profile.organizationId.startsWith("proj_")) put("OpenAI-Project", profile.organizationId)
            else put("OpenAI-Organization", profile.organizationId)
        }
        profile.customHeaders.forEach { (key, value) -> put(key.trim(), value.trim()) }
    }

    private fun parseHttpError(code: Int, raw: String?): AppError {
        val message = raw?.let { body ->
            runCatching { json.decodeFromString<OpenAiChatResponse>(body).error?.message }.getOrNull()
                ?: runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return ApiErrorMapper.fromHttp(code, message)
    }

    private fun OpenAiUsage?.toDomain(): AiUsage? = this?.let { AiUsage(it.promptTokens, it.completionTokens) }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Accumulates streamed `tool_calls` fragments. OpenAI sends the name once and
 * the JSON arguments in arbitrary slices, so parsing must wait until the
 * stream finishes; a partial `{"city":` would otherwise crash the executor.
 */
internal class ToolCallAccumulator(private val json: Json) {
    private data class Partial(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val parts = mutableMapOf<Int, Partial>()

    fun accept(calls: List<OpenAiToolCall>?) {
        calls?.forEach { call ->
            val index = call.index
                ?: call.id?.let { id -> parts.entries.firstOrNull { it.value.id == id }?.key }
                ?: parts.size
            val part = parts.getOrPut(index) { Partial() }
            call.id?.let { part.id = it }
            call.function?.name?.takeIf { it.isNotBlank() }?.let { part.name = it }
            call.function?.arguments?.let { part.arguments.append(it) }
        }
    }

    fun build(): List<ToolCall> = parts.entries.sortedBy { it.key }.mapNotNull { (index, part) ->
        val name = part.name ?: return@mapNotNull null
        val raw = part.arguments.toString().trim()
        val arguments = if (raw.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        }
        ToolCall(
            id = part.id ?: "call_${index}_$name",
            name = name,
            arguments = arguments,
        )
    }
}
