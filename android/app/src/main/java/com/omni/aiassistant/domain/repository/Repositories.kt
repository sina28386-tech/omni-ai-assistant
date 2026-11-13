package com.omni.aiassistant.domain.repository

import com.omni.aiassistant.domain.model.*
import com.omni.aiassistant.core.agent.ToolCall
import kotlinx.coroutines.flow.Flow

interface ProviderRepository {
    fun observeProfiles(): Flow<List<ProviderProfile>>
    suspend fun getProfile(id: String): ProviderProfile?
    suspend fun upsert(profile: ProviderProfile)
    suspend fun delete(id: String)
    suspend fun deleteAll()
}

interface ConversationRepository {
    fun observeConversations(archived: Boolean = false): Flow<List<Conversation>>
    fun observeConversation(id: String): Flow<Conversation?>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun create(providerId: String?, systemPrompt: String = ""): Conversation
    suspend fun get(id: String): Conversation?
    suspend fun rename(id: String, title: String)
    suspend fun setProvider(id: String, providerId: String?)
    suspend fun setSystemPrompt(id: String, prompt: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun deleteConversation(id: String)
    suspend fun clearAll()
    suspend fun addUserMessage(conversationId: String, content: String, attachments: List<PreparedAttachment>): ChatMessage
    suspend fun addAssistantPlaceholder(conversationId: String): ChatMessage
    suspend fun updateAssistant(messageId: String, content: String, status: MessageStatus, usage: AiUsage? = null, errorCode: String? = null, errorDetails: String? = null)
    /** Persists the tool calls requested by an assistant message. */
    suspend fun setAssistantToolCalls(messageId: String, calls: List<ToolCall>)
    /** Appends a `role=tool` result message and returns it. */
    suspend fun addToolResult(conversationId: String, call: ToolCall, modelPayload: String): ChatMessage
    suspend fun editUserMessageAndDeleteFollowing(messageId: String, newContent: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun recentContext(conversationId: String, limit: Int): List<ChatMessage>
}
