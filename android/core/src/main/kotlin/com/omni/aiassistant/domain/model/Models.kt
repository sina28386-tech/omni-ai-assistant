package com.omni.aiassistant.domain.model

import kotlinx.serialization.Serializable

enum class ProviderType { NVIDIA, OPENAI, GROQ, OPENROUTER, GEMINI, OLLAMA, VLLM, CUSTOM }
enum class TokenParameter { AUTO, MAX_TOKENS, MAX_COMPLETION_TOKENS }
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }
enum class MessageStatus { SENDING, STREAMING, COMPLETE, FAILED, CANCELED }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage { ENGLISH, PERSIAN }

/** Where device/agent commands prefer to run. PHONE-only skips PC polling. */
enum class CommandTarget { AUTO, PHONE, PC }

data class ProviderProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val organizationId: String?,
    val systemPrompt: String,
    val temperature: Double,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val supportsVision: Boolean,
    val supportsStreaming: Boolean,
    val enabled: Boolean,
    val customHeaders: Map<String, String>,
    val endpointPath: String,
    val tokenParameter: TokenParameter,
    val createdAt: Long,
    val updatedAt: Long,
    /** Provider/models that support OpenAI-style function calling. */
    val supportsTools: Boolean = true,
)

data class Conversation(
    val id: String,
    val title: String,
    val providerProfileId: String?,
    val systemPrompt: String,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Attachment(
    val id: String,
    val messageId: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val sortIndex: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val errorCode: String? = null,
    val errorDetails: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Assistant message that requested one or more tools. */
    val toolCalls: List<com.omni.aiassistant.core.agent.ToolCall> = emptyList(),
    /** Set on [MessageRole.TOOL] messages: the call this result belongs to. */
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val defaultProviderId: String? = null,
    val defaultModelId: String = "",
    val defaultSystemPrompt: String = "",
    val voiceLanguageTag: String = "fa-IR",
    val ttsLanguageTag: String = "fa-IR",
    val ttsVoiceName: String? = null,
    val ttsSpeed: Float = 1f,
    val ttsPitch: Float = 1f,
    val autoTts: Boolean = false,
    val autoSendVoice: Boolean = false,
    val creditSaver: Boolean = false,
    val maxContextMessages: Int = 16,
    val defaultMaxOutputTokens: Int = 1024,
    val imageQuality: Int = 82,
    val streaming: Boolean = true,
    val localDevelopmentEndpoint: Boolean = false,
    val commandTarget: CommandTarget = CommandTarget.AUTO,
    /** Master switch for provider function calling. */
    val agentMode: Boolean = true,
    /** Skip the confirmation sheet for MEDIUM-risk tools (never for HIGH). */
    val autoApproveMediumRisk: Boolean = false,
    val autoTypeInApps: Boolean = false,
    /** Tools the user explicitly chose "Always allow" for, by tool name. */
    val trustedTools: Set<String> = emptySet(),
    /**
     * Enables the Accessibility driver (tap/type/scroll/screen reading) and
     * notification access. Off by default: these are the two most invasive
     * capabilities in the app.
     */
    val fullPhoneControl: Boolean = false,
    /** Persist a local audit trail of every executed tool. */
    val toolAuditEnabled: Boolean = true,
    /** Show the tool timeline cards in chat. */
    val showToolActivity: Boolean = true,
    /** Hermes PC bridge endpoint; configurable so it works on a real phone. */
    val hermesUrl: String = "http://10.0.2.2:8765",
    val hermesToken: String = "",
    /**
     * Keeps a microphone foreground service alive so Arman can be woken up
     * by voice while the screen is off. Off by default because it always
     * holds the microphone.
     */
    val wakeWordEnabled: Boolean = false,
    /** The word that wakes Arman up. */
    val wakeWordPhrase: String = "آرمان",
    /**
     * How many tool steps one request may take. A long request ("find the
     * file, send it, then check Telegram, then check Twitter") needs more than
     * the default six steps.
     */
    val maxToolRounds: Int = 12,
)

data class PreparedAttachment(
    val id: String,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
)

data class AiUsage(val promptTokens: Int?, val completionTokens: Int?)

data class AiCompletion(
    val text: String,
    val usage: AiUsage?,
    val toolCalls: List<com.omni.aiassistant.core.agent.ToolCall> = emptyList(),
)

sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class ToolCallsReady(val calls: List<com.omni.aiassistant.core.agent.ToolCall>) : AiStreamEvent
    data class Completed(val usage: AiUsage?) : AiStreamEvent
}
