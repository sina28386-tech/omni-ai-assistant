package com.omni.aiassistant.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.security.Hashing
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.core.security.PortableBackupCipher
import com.omni.aiassistant.data.attachment.AttachmentStore
import com.omni.aiassistant.data.db.*
import com.omni.aiassistant.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val providerDao: ProviderDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val aead: KeystoreAead,
    private val cipher: PortableBackupCipher,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
) {
    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        if (password.size < 10) throw AppError.Validation("Backup password must be at least 10 characters")
        val providers = loadPaged { limit, offset -> providerDao.page(limit, offset) }
        val conversations = loadPaged { limit, offset -> conversationDao.page(limit, offset) }
        val messages = loadPaged { limit, offset -> messageDao.page(limit, offset) }
        val attachments = loadPaged { limit, offset -> attachmentDao.page(limit, offset) }
        var rawAttachmentBytes = 0L
        val backupAttachments = attachments.map { entity ->
            val file = attachmentStore.safeFile(entity.localPath)
            val bytes = file.readBytes()
            if (bytes.size.toLong() != entity.sizeBytes) throw AppError.Backup("Attachment size mismatch: ${entity.id}")
            if (Hashing.sha256Hex(bytes) != entity.sha256) throw AppError.Backup("Attachment integrity check failed: ${entity.id}")
            rawAttachmentBytes += bytes.size
            if (rawAttachmentBytes > MAX_RAW_ATTACHMENTS) throw AppError.Backup("Attachments exceed the 40 MB portable-backup limit")
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            bytes.fill(0)
            BackupAttachment(
                id = entity.id,
                messageId = entity.messageId,
                mimeType = entity.mimeType,
                sizeBytes = entity.sizeBytes,
                width = entity.width,
                height = entity.height,
                sha256 = entity.sha256,
                base64Data = base64,
            )
        }
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            settings = settingsRepository.snapshot(),
            providers = providers.map { e ->
                BackupProvider(
                    e.id, e.name, e.type, e.baseUrl, aead.decrypt(e.encryptedApiKey), e.modelId, e.organizationId,
                    aead.decrypt(e.encryptedSystemPrompt), e.temperature, e.maxOutputTokens, e.timeoutSeconds,
                    e.supportsVision, e.supportsStreaming, e.enabled,
                    json.decodeFromString(aead.decrypt(e.encryptedCustomHeaders)), e.endpointPath, e.tokenParameter, e.createdAt, e.updatedAt,
                    e.supportsTools,
                )
            },
            conversations = conversations.map { e ->
                BackupConversation(e.id, aead.decrypt(e.encryptedTitle), e.providerProfileId, aead.decrypt(e.encryptedSystemPrompt), e.pinned, e.archived, e.createdAt, e.updatedAt)
            },
            messages = messages.map { e ->
                BackupMessage(
                    e.id,
                    e.conversationId,
                    e.role,
                    aead.decrypt(e.encryptedContent),
                    e.status,
                    e.sortIndex,
                    e.createdAt,
                    e.updatedAt,
                    e.promptTokens,
                    e.completionTokens,
                    e.errorCode,
                    e.encryptedErrorDetails?.let(aead::decrypt),
                    e.encryptedToolCallsJson.takeIf(String::isNotEmpty)?.let(aead::decrypt).orEmpty(),
                    e.toolCallId,
                    e.toolName,
                )
            },
            attachments = backupAttachments,
        )
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        try {
            if (plaintext.size > MAX_PLAINTEXT_BACKUP) throw AppError.Backup("Backup is too large")
            val encrypted = cipher.encrypt(plaintext, password)
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encrypted) }
                    ?: throw AppError.Backup("Unable to open backup destination")
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun restore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
            readLimited(input, MAX_ENCRYPTED_BACKUP)
        } ?: throw AppError.Backup("Unable to open backup file")
        val plaintext = try {
            cipher.decrypt(encrypted, password)
        } finally {
            encrypted.fill(0)
        }
        val payload = try {
            json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw AppError.Backup("Backup payload is invalid", t)
        } finally {
            plaintext.fill(0)
        }
        validate(payload)

        val staging = File(context.filesDir, "attachments_restore_${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw AppError.Backup("Unable to create restore staging directory")
        try {
            val newAttachments = payload.attachments.map { item ->
                val bytes = Base64.decode(item.base64Data, Base64.NO_WRAP)
                if (bytes.size.toLong() != item.sizeBytes || Hashing.sha256Hex(bytes) != item.sha256) {
                    throw AppError.Backup("Attachment integrity check failed: ${item.id}")
                }
                val extension = if (item.mimeType == "image/jpeg") ".jpg" else ".bin"
                val stagedFile = File(staging, item.id + extension)
                if (stagedFile.canonicalFile.parentFile != staging.canonicalFile) {
                    throw AppError.Backup("Invalid attachment ID: ${item.id}")
                }
                stagedFile.writeBytes(bytes)
                bytes.fill(0)
                val finalPath = File(attachmentStore.rootDirectory(), stagedFile.name).absolutePath
                AttachmentEntity(item.id, item.messageId, finalPath, item.mimeType, item.sizeBytes, item.width, item.height, item.sha256)
            }
            val providerEntities = payload.providers.map { p ->
                ProviderProfileEntity(
                    p.id, p.name, p.type, p.baseUrl, aead.encrypt(p.apiKey), p.modelId, p.organizationId,
                    aead.encrypt(p.systemPrompt), p.temperature, p.maxOutputTokens, p.timeoutSeconds, p.supportsVision,
                    p.supportsStreaming, p.enabled, aead.encrypt(json.encodeToString(p.customHeaders)), p.endpointPath,
                    p.tokenParameter, p.createdAt, p.updatedAt, p.supportsTools,
                )
            }
            val conversationEntities = payload.conversations.map { c ->
                ConversationEntity(c.id, aead.encrypt(c.title), c.providerProfileId, aead.encrypt(c.systemPrompt), c.pinned, c.archived, c.createdAt, c.updatedAt)
            }
            val messageEntities = payload.messages.map { m ->
                MessageEntity(
                    m.id,
                    m.conversationId,
                    m.role,
                    aead.encrypt(m.content),
                    m.status,
                    m.sortIndex,
                    m.createdAt,
                    m.updatedAt,
                    m.promptTokens,
                    m.completionTokens,
                    m.errorCode,
                    m.errorDetails?.let(aead::encrypt),
                    m.toolCallsJson.takeIf(String::isNotEmpty)?.let(aead::encrypt).orEmpty(),
                    m.toolCallId,
                    m.toolName,
                )
            }

            // Snapshot the current encrypted database rows/settings before the swap so a failure
            // after the database transaction (for example a DataStore write failure) can still
            // roll the entire restore back coherently.
            val previousProviders = loadPaged { limit, offset -> providerDao.page(limit, offset) }
            val previousConversations = loadPaged { limit, offset -> conversationDao.page(limit, offset) }
            val previousMessages = loadPaged { limit, offset -> messageDao.page(limit, offset) }
            val previousAttachments = loadPaged { limit, offset -> attachmentDao.page(limit, offset) }
            val previousSettings = settingsRepository.snapshot()

            val root = attachmentStore.rootDirectory()
            val old = File(context.filesDir, "attachments_before_restore_${UUID.randomUUID()}")
            if (root.exists() && !root.renameTo(old)) throw AppError.Backup("Unable to stage current attachments")
            if (!staging.renameTo(root)) {
                old.renameTo(root)
                throw AppError.Backup("Unable to activate restored attachments")
            }
            try {
                db.withTransaction {
                    attachmentDao.deleteAll()
                    messageDao.deleteAll()
                    conversationDao.deleteAll()
                    providerDao.deleteAll()
                    providerDao.upsertAll(providerEntities)
                    conversationDao.upsertAll(conversationEntities)
                    messageDao.upsertAll(messageEntities)
                    attachmentDao.upsertAll(newAttachments)
                }
                settingsRepository.replace(payload.settings)
                old.deleteRecursively()
            } catch (restoreError: Throwable) {
                val rollback = runCatching {
                    db.withTransaction {
                        attachmentDao.deleteAll()
                        messageDao.deleteAll()
                        conversationDao.deleteAll()
                        providerDao.deleteAll()
                        providerDao.upsertAll(previousProviders)
                        conversationDao.upsertAll(previousConversations)
                        messageDao.upsertAll(previousMessages)
                        attachmentDao.upsertAll(previousAttachments)
                    }
                    settingsRepository.replace(previousSettings)
                    root.deleteRecursively()
                    if (old.exists() && !old.renameTo(root)) throw AppError.Backup("Unable to restore previous attachment directory")
                }
                if (rollback.isFailure) {
                    throw AppError.Backup("Restore failed and rollback could not be completed safely", restoreError)
                }
                throw restoreError
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validate(payload: BackupPayload) {
        if (payload.schemaVersion != 1) throw AppError.Backup("Unsupported backup schema")
        if (payload.providers.size > 2_000 || payload.conversations.size > 20_000 || payload.messages.size > 200_000 || payload.attachments.size > 20_000) {
            throw AppError.Backup("Backup item counts exceed safety limits")
        }
        ensureUnique(payload.providers.map { it.id }, "provider")
        ensureUnique(payload.conversations.map { it.id }, "conversation")
        ensureUnique(payload.messages.map { it.id }, "message")
        ensureUnique(payload.attachments.map { it.id }, "attachment")
        val providerIds = payload.providers.mapTo(hashSetOf()) { it.id }
        val conversationIds = payload.conversations.mapTo(hashSetOf()) { it.id }
        val messageIds = payload.messages.mapTo(hashSetOf()) { it.id }
        payload.providers.forEach { p ->
            runCatching { com.omni.aiassistant.domain.model.ProviderType.valueOf(p.type) }
                .getOrElse { throw AppError.Backup("Backup contains an unknown provider type") }
            runCatching { com.omni.aiassistant.domain.model.TokenParameter.valueOf(p.tokenParameter) }
                .getOrElse { throw AppError.Backup("Backup contains an unknown token parameter") }
            if (p.name.length > 200 || p.baseUrl.length > 2_000 || p.modelId.length > 500 || p.apiKey.length > 32_000 || p.systemPrompt.length > 200_000) {
                throw AppError.Backup("Provider fields exceed safety limits")
            }
        }
        payload.conversations.forEach { c ->
            if (c.providerProfileId != null && c.providerProfileId !in providerIds) throw AppError.Backup("Conversation references a missing provider")
            if (c.title.length > 500 || c.systemPrompt.length > 200_000) throw AppError.Backup("Conversation fields exceed safety limits")
        }
        payload.messages.forEach { m ->
            runCatching { com.omni.aiassistant.domain.model.MessageRole.valueOf(m.role) }
                .getOrElse { throw AppError.Backup("Backup contains an unknown message role") }
            runCatching { com.omni.aiassistant.domain.model.MessageStatus.valueOf(m.status) }
                .getOrElse { throw AppError.Backup("Backup contains an unknown message status") }
            if (m.conversationId !in conversationIds) throw AppError.Backup("Message references a missing conversation")
            if (m.content.length > 2_000_000) throw AppError.Backup("A message exceeds the safe restore size")
            if (m.toolCallsJson.length > 65_536) throw AppError.Backup("Tool calls exceed the safe restore size")
            if ((m.toolCallId?.length ?: 0) > 512 || (m.toolName?.length ?: 0) > 64) {
                throw AppError.Backup("A tool reference exceeds the safe restore size")
            }
        }
        var rawTotal = 0L
        payload.attachments.forEach { a ->
            if (a.messageId !in messageIds) throw AppError.Backup("Attachment references a missing message")
            if (a.sizeBytes !in 0..MAX_SINGLE_ATTACHMENT) throw AppError.Backup("Attachment exceeds the safe restore size")
            val maxBase64Chars = ((a.sizeBytes + 2L) / 3L) * 4L + 16L
            if (a.base64Data.length.toLong() > maxBase64Chars) throw AppError.Backup("Attachment encoding exceeds the safe restore size")
            rawTotal += a.sizeBytes
        }
        if (rawTotal > MAX_RAW_ATTACHMENTS) throw AppError.Backup("Backup attachments exceed safety limits")
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw AppError.Backup("Backup file is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ensureUnique(ids: List<String>, type: String) {
        // IDs become attachment file names during restore, so they must be a
        // strict, path-safe alphabet. Reject traversal before any file write.
        if (ids.any { !SAFE_ID.matches(it) } || ids.toSet().size != ids.size) {
            throw AppError.Backup("Invalid or duplicate $type IDs")
        }
    }

    private suspend fun <T> loadPaged(loader: suspend (Int, Int) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = loader(PAGE_SIZE, offset)
            out += page
            if (page.size < PAGE_SIZE) return out
            offset += page.size
            if (out.size > MAX_ITEMS_PER_TABLE) throw AppError.Backup("Too many records to back up safely")
        }
    }

    companion object {
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private const val PAGE_SIZE = 500
        private const val MAX_ITEMS_PER_TABLE = 200_000
        private const val MAX_RAW_ATTACHMENTS = 40L * 1024 * 1024
        private const val MAX_SINGLE_ATTACHMENT = 8L * 1024 * 1024
        private const val MAX_PLAINTEXT_BACKUP = 60 * 1024 * 1024
        private const val MAX_ENCRYPTED_BACKUP = 64 * 1024 * 1024
    }
}
