package com.omni.aiassistant.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_profiles ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM provider_profiles ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ProviderProfileEntity>

    @Query("SELECT * FROM provider_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderProfileEntity?

    @Upsert
    suspend fun upsert(entity: ProviderProfileEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ProviderProfileEntity>)

    @Query("DELETE FROM provider_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM provider_profiles")
    suspend fun deleteAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = :archived ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    fun observeRecent(archived: Boolean = false, limit: Int = 200): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ConversationEntity>)

    @Query("UPDATE conversations SET encryptedTitle = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET providerProfileId = :providerId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setProvider(id: String, providerId: String?, updatedAt: Long)

    @Query("UPDATE conversations SET encryptedSystemPrompt = :prompt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSystemPrompt(id: String, prompt: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE conversations SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortIndex DESC LIMIT :limit) ORDER BY sortIndex ASC")
    fun observeByConversation(conversationId: String, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortIndex DESC LIMIT :limit")
    suspend fun getRecent(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT COALESCE(MAX(sortIndex), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSortIndex(conversationId: String): Long

    @Upsert
    suspend fun upsert(entity: MessageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query("UPDATE messages SET encryptedContent = :content, status = :status, updatedAt = :updatedAt, promptTokens = :promptTokens, completionTokens = :completionTokens, errorCode = :errorCode, encryptedErrorDetails = :errorDetails WHERE id = :id")    suspend fun updateContent(
        id: String,
        content: String,
        status: String,
        updatedAt: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        errorCode: String?,
        errorDetails: String?,
    )

    @Query("UPDATE messages SET encryptedToolCallsJson = :calls WHERE id = :id")
    suspend fun setToolCalls(id: String, calls: String)

    /**
     * On process start, no generation can still be running. Leaving rows in
     * SENDING/STREAMING made the UI show a spinner forever after a crash.
     */
    @Query("UPDATE messages SET status = 'FAILED', errorCode = COALESCE(errorCode, 'INTERRUPTED') WHERE status IN ('SENDING', 'STREAMING')")
    suspend fun failInterrupted(): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sortIndex > :sortIndex")
    suspend fun deleteAfter(conversationId: String, sortIndex: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface AttachmentDao {
    @Query("SELECT a.* FROM attachments a INNER JOIN (SELECT id, sortIndex FROM messages WHERE conversationId = :conversationId ORDER BY sortIndex DESC LIMIT :limit) m ON m.id = a.messageId ORDER BY m.sortIndex ASC, a.id ASC")
    fun observeForConversation(conversationId: String, limit: Int = 500): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId IN (:messageIds)")
    suspend fun getForMessages(messageIds: List<String>): List<AttachmentEntity>

    @Query("SELECT a.* FROM attachments a INNER JOIN messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId ORDER BY a.id LIMIT :limit OFFSET :offset")
    suspend fun pageForConversation(conversationId: String, limit: Int, offset: Int): List<AttachmentEntity>

    @Query("SELECT a.* FROM attachments a INNER JOIN messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId AND m.sortIndex > :sortIndex ORDER BY a.id LIMIT :limit OFFSET :offset")
    suspend fun pageAfter(conversationId: String, sortIndex: Long, limit: Int, offset: Int): List<AttachmentEntity>

    @Query("SELECT * FROM attachments ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()
}
