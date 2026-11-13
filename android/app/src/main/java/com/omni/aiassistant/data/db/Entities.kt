package com.omni.aiassistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "provider_profiles", indices = [Index("updatedAt")])
data class ProviderProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val encryptedApiKey: String,
    val modelId: String,
    val organizationId: String?,
    val encryptedSystemPrompt: String,
    val temperature: Double,
    val maxOutputTokens: Int,
    val timeoutSeconds: Int,
    val supportsVision: Boolean,
    val supportsStreaming: Boolean,
    val enabled: Boolean,
    val encryptedCustomHeaders: String,
    val endpointPath: String,
    val tokenParameter: String,
    val createdAt: Long,
    val updatedAt: Long,
    val supportsTools: Boolean = true,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("providerProfileId"), Index("updatedAt"), Index(value = ["pinned", "updatedAt"])],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val encryptedTitle: String,
    val providerProfileId: String?,
    val encryptedSystemPrompt: String,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index(value = ["conversationId", "sortIndex"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val encryptedContent: String,
    val status: String,
    val sortIndex: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val errorCode: String?,
    val encryptedErrorDetails: String?,
    /** Encrypted JSON array of `ToolCall` for an assistant message. */
    val encryptedToolCallsJson: String = "",
    /** Set on role=TOOL: the call this result answers. */
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
)
