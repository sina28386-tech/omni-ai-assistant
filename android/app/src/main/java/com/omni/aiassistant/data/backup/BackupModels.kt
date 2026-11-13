package com.omni.aiassistant.data.backup

import com.omni.aiassistant.domain.model.AppSettings
import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val settings: AppSettings,
    val providers: List<BackupProvider>,
    val conversations: List<BackupConversation>,
    val messages: List<BackupMessage>,
    val attachments: List<BackupAttachment>,
)

@Serializable
data class BackupProvider(
    val id: String,
    val name: String,
    val type: String,
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
    val tokenParameter: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Added in V2; absent in older backups and therefore defaults to true. */
    val supportsTools: Boolean = true,
)

@Serializable
data class BackupConversation(
    val id: String,
    val title: String,
    val providerProfileId: String?,
    val systemPrompt: String,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val status: String,
    val sortIndex: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val errorCode: String?,
    val errorDetails: String?,
    /** Decrypted OpenAI tool calls requested by this assistant turn (V2). */
    val toolCallsJson: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Serializable
data class BackupAttachment(
    val id: String,
    val messageId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val base64Data: String,
)
