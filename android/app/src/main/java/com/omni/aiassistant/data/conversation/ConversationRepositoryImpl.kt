package com.omni.aiassistant.data.conversation

import androidx.room.withTransaction
import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.core.util.Ids
import com.omni.aiassistant.data.attachment.AttachmentStore
import com.omni.aiassistant.data.db.*
import com.omni.aiassistant.domain.model.*
import com.omni.aiassistant.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepositoryImpl(
    private val db: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val aead: KeystoreAead,
    private val attachmentStore: AttachmentStore,
) : ConversationRepository {
    private val toolJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun observeConversations(archived: Boolean): Flow<List<Conversation>> =
        conversationDao.observeRecent(archived).map { list -> list.map(::conversationToDomain) }

    override fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.let(::conversationToDomain) }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        combine(
            messageDao.observeByConversation(conversationId),
            attachmentDao.observeForConversation(conversationId),
        ) { messages, attachments ->
            val grouped = attachments.groupBy { it.messageId }
            messages.map { message -> messageToDomain(message, grouped[message.id].orEmpty()) }
        }

    override suspend fun create(providerId: String?, systemPrompt: String): Conversation {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = Ids.newId(),
            encryptedTitle = aead.encrypt(""),
            providerProfileId = providerId,
            encryptedSystemPrompt = aead.encrypt(systemPrompt),
            pinned = false,
            archived = false,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsert(entity)
        return conversationToDomain(entity)
    }

    override suspend fun get(id: String): Conversation? = conversationDao.getById(id)?.let(::conversationToDomain)

    override suspend fun rename(id: String, title: String) {
        val cleaned = title.trim().take(120)
        if (cleaned.isBlank()) throw AppError.Validation("Conversation title cannot be empty")
        conversationDao.rename(id, aead.encrypt(cleaned), System.currentTimeMillis())
    }

    override suspend fun setProvider(id: String, providerId: String?) =
        conversationDao.setProvider(id, providerId, System.currentTimeMillis())

    override suspend fun setSystemPrompt(id: String, prompt: String) =
        conversationDao.setSystemPrompt(id, aead.encrypt(prompt.take(20_000)), System.currentTimeMillis())

    override suspend fun setPinned(id: String, pinned: Boolean) =
        conversationDao.setPinned(id, pinned, System.currentTimeMillis())

    override suspend fun setArchived(id: String, archived: Boolean) =
        conversationDao.setArchived(id, archived, System.currentTimeMillis())

    override suspend fun deleteConversation(id: String) {
        val attachments = loadAttachmentPages { limit, offset -> attachmentDao.pageForConversation(id, limit, offset) }
        db.withTransaction { conversationDao.delete(id) }
        attachments.forEach { attachmentStore.deletePath(it.localPath) }
    }

    override suspend fun clearAll() {
        db.withTransaction { conversationDao.deleteAll() }
        attachmentStore.clearAll()
    }

    override suspend fun addUserMessage(
        conversationId: String,
        content: String,
        attachments: List<PreparedAttachment>,
    ): ChatMessage {
        if (content.isBlank() && attachments.isEmpty()) throw AppError.Validation("Message is empty")
        val now = System.currentTimeMillis()
        lateinit var entity: MessageEntity
        db.withTransaction {
            val conversation = conversationDao.getById(conversationId) ?: throw AppError.Database("Conversation does not exist")
            val next = messageDao.maxSortIndex(conversationId) + 1
            entity = MessageEntity(
                id = Ids.newId(),
                conversationId = conversationId,
                role = MessageRole.USER.name,
                encryptedContent = aead.encrypt(content.trim()),
                status = MessageStatus.COMPLETE.name,
                sortIndex = next,
                createdAt = now,
                updatedAt = now,
                promptTokens = null,
                completionTokens = null,
                errorCode = null,
                encryptedErrorDetails = null,
            )
            messageDao.upsert(entity)
            attachments.forEach { item ->
                attachmentDao.upsert(
                    AttachmentEntity(
                        id = item.id,
                        messageId = entity.id,
                        localPath = item.filePath,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        width = item.width,
                        height = item.height,
                        sha256 = item.sha256,
                    ),
                )
            }
            conversationDao.touch(conversationId, now)
            val currentTitle = runCatching { aead.decrypt(conversation.encryptedTitle) }.getOrDefault("")
            if (currentTitle.isBlank()) {
                val derived = content.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "📷" }
                conversationDao.rename(conversationId, aead.encrypt(derived), now)
            }
        }
        return messageToDomain(
            entity,
            attachments.map { AttachmentEntity(it.id, entity.id, it.filePath, it.mimeType, it.sizeBytes, it.width, it.height, it.sha256) },
        )
    }

    override suspend fun addAssistantPlaceholder(conversationId: String): ChatMessage {
        val now = System.currentTimeMillis()
        val entity = db.withTransaction {
            val next = messageDao.maxSortIndex(conversationId) + 1
            MessageEntity(
                id = Ids.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT.name,
                encryptedContent = aead.encrypt(""),
                status = MessageStatus.SENDING.name,
                sortIndex = next,
                createdAt = now,
                updatedAt = now,
                promptTokens = null,
                completionTokens = null,
                errorCode = null,
                encryptedErrorDetails = null,
            ).also {
                messageDao.upsert(it)
                conversationDao.touch(conversationId, now)
            }
        }
        return messageToDomain(entity, emptyList())
    }

    override suspend fun updateAssistant(
        messageId: String,
        content: String,
        status: MessageStatus,
        usage: AiUsage?,
        errorCode: String?,
        errorDetails: String?,
    ) {
        val current = messageDao.getById(messageId) ?: return
        if (current.role != MessageRole.ASSISTANT.name) throw AppError.Validation("Only assistant messages can be updated by generation")
        val now = System.currentTimeMillis()
        messageDao.updateContent(
            id = messageId,
            content = aead.encrypt(content),
            status = status.name,
            updatedAt = now,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            errorCode = errorCode,
            errorDetails = errorDetails?.let(aead::encrypt),
        )
        conversationDao.touch(current.conversationId, now)
    }

    override suspend fun setAssistantToolCalls(messageId: String, calls: List<ToolCall>) {
        val current = messageDao.getById(messageId) ?: return
        if (current.role != MessageRole.ASSISTANT.name) return
        messageDao.setToolCalls(messageId, aead.encrypt(toolJson.encodeToString(calls)))
    }

    override suspend fun addToolResult(
        conversationId: String,
        call: ToolCall,
        modelPayload: String,
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val entity = db.withTransaction {
            val next = messageDao.maxSortIndex(conversationId) + 1
            MessageEntity(
                id = Ids.newId(),
                conversationId = conversationId,
                role = MessageRole.TOOL.name,
                encryptedContent = aead.encrypt(modelPayload.take(MAX_TOOL_PAYLOAD_CHARS)),
                status = MessageStatus.COMPLETE.name,
                sortIndex = next,
                createdAt = now,
                updatedAt = now,
                promptTokens = null,
                completionTokens = null,
                errorCode = null,
                encryptedErrorDetails = null,
                encryptedToolCallsJson = "",
                toolCallId = call.id,
                toolName = call.name,
            ).also {
                messageDao.upsert(it)
                conversationDao.touch(conversationId, now)
            }
        }
        return messageToDomain(entity, emptyList())
    }

    override suspend fun editUserMessageAndDeleteFollowing(messageId: String, newContent: String) {        val cleaned = newContent.trim()
        if (cleaned.isBlank()) throw AppError.Validation("Message cannot be empty")
        val current = messageDao.getById(messageId) ?: throw AppError.Validation("Message not found")
        if (current.role != MessageRole.USER.name) throw AppError.Validation("Only user messages can be edited")
        val files = loadAttachmentPages { limit, offset ->
            attachmentDao.pageAfter(current.conversationId, current.sortIndex, limit, offset)
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            messageDao.deleteAfter(current.conversationId, current.sortIndex)
            messageDao.updateContent(
                id = current.id,
                content = aead.encrypt(cleaned),
                status = MessageStatus.COMPLETE.name,
                updatedAt = now,
                promptTokens = null,
                completionTokens = null,
                errorCode = null,
                errorDetails = null,
            )
            conversationDao.touch(current.conversationId, now)
        }
        files.forEach { attachmentStore.deletePath(it.localPath) }
    }

    override suspend fun deleteMessage(messageId: String) {
        val attachments = attachmentDao.getForMessages(listOf(messageId))
        messageDao.delete(messageId)
        attachments.forEach { attachmentStore.deletePath(it.localPath) }
    }

    override suspend fun recentContext(conversationId: String, limit: Int): List<ChatMessage> {
        val messages = messageDao.getRecent(conversationId, limit.coerceIn(1, 100)).reversed()
        val attachments = if (messages.isEmpty()) emptyList() else attachmentDao.getForMessages(messages.map { it.id })
        val grouped = attachments.groupBy { it.messageId }
        return messages.map { messageToDomain(it, grouped[it.id].orEmpty()) }
    }

    private suspend fun loadAttachmentPages(loader: suspend (Int, Int) -> List<AttachmentEntity>): List<AttachmentEntity> {
        val out = mutableListOf<AttachmentEntity>()
        var offset = 0
        while (true) {
            val page = loader(500, offset)
            out += page
            if (page.size < 500) return out
            offset += page.size
            if (out.size > 100_000) throw AppError.Database("Attachment count exceeds safe deletion limit")
        }
    }

    fun conversationToDomain(entity: ConversationEntity): Conversation = Conversation(
        id = entity.id,
        title = aead.decrypt(entity.encryptedTitle),
        providerProfileId = entity.providerProfileId,
        systemPrompt = aead.decrypt(entity.encryptedSystemPrompt),
        pinned = entity.pinned,
        archived = entity.archived,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    fun messageToDomain(entity: MessageEntity, attachments: List<AttachmentEntity>): ChatMessage = ChatMessage(
        id = entity.id,
        conversationId = entity.conversationId,
        role = MessageRole.valueOf(entity.role),
        content = aead.decrypt(entity.encryptedContent),
        status = MessageStatus.valueOf(entity.status),
        sortIndex = entity.sortIndex,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        promptTokens = entity.promptTokens,
        completionTokens = entity.completionTokens,
        errorCode = entity.errorCode,
        errorDetails = entity.encryptedErrorDetails?.let(aead::decrypt),
        attachments = attachments.map {
            Attachment(it.id, it.messageId, it.localPath, it.mimeType, it.sizeBytes, it.width, it.height, it.sha256)
        },
        toolCalls = decodeToolCalls(entity.encryptedToolCallsJson),
        toolCallId = entity.toolCallId,
        toolName = entity.toolName,
    )

    private fun decodeToolCalls(encrypted: String): List<ToolCall> {
        if (encrypted.isBlank()) return emptyList()
        return runCatching {
            toolJson.decodeFromString<List<ToolCall>>(aead.decrypt(encrypted))
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * A single tool result can contain a notification dump or a file list.
         * Cap it before it reaches both the encrypted DB blob and the provider
         * context window; the executor already trims, this is the last fence.
         */
        const val MAX_TOOL_PAYLOAD_CHARS = 16_000
    }
}
