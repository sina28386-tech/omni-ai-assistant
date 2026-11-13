package com.omni.aiassistant.data.provider

import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.data.db.ProviderDao
import com.omni.aiassistant.data.db.ProviderProfileEntity
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProviderRepositoryImpl(
    private val dao: ProviderDao,
    private val aead: KeystoreAead,
    private val json: Json,
) : ProviderRepository {
    override fun observeProfiles(): Flow<List<ProviderProfile>> = dao.observeAll().map { list -> list.map(::toDomain) }

    override suspend fun getProfile(id: String): ProviderProfile? = dao.getById(id)?.let(::toDomain)

    override suspend fun upsert(profile: ProviderProfile) {
        dao.upsert(toEntity(profile))
    }

    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteAll() = dao.deleteAll()

    fun toDomain(entity: ProviderProfileEntity): ProviderProfile = ProviderProfile(
        id = entity.id,
        name = entity.name,
        type = ProviderType.valueOf(entity.type),
        baseUrl = entity.baseUrl,
        apiKey = aead.decrypt(entity.encryptedApiKey),
        modelId = entity.modelId,
        organizationId = entity.organizationId,
        systemPrompt = aead.decrypt(entity.encryptedSystemPrompt),
        temperature = entity.temperature,
        maxOutputTokens = entity.maxOutputTokens,
        timeoutSeconds = entity.timeoutSeconds,
        supportsVision = entity.supportsVision,
        supportsStreaming = entity.supportsStreaming,
        enabled = entity.enabled,
        customHeaders = json.decodeFromString(aead.decrypt(entity.encryptedCustomHeaders)),
        endpointPath = entity.endpointPath,
        tokenParameter = TokenParameter.valueOf(entity.tokenParameter),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        supportsTools = entity.supportsTools,
    )

    fun toEntity(profile: ProviderProfile): ProviderProfileEntity = ProviderProfileEntity(
        id = profile.id,
        name = profile.name.trim(),
        type = profile.type.name,
        baseUrl = profile.baseUrl.trim().trimEnd('/'),
        encryptedApiKey = aead.encrypt(profile.apiKey),
        modelId = profile.modelId.trim(),
        organizationId = profile.organizationId?.trim()?.takeIf { it.isNotEmpty() },
        encryptedSystemPrompt = aead.encrypt(profile.systemPrompt),
        temperature = profile.temperature.coerceIn(0.0, 2.0),
        maxOutputTokens = profile.maxOutputTokens.coerceIn(1, 65_536),
        timeoutSeconds = profile.timeoutSeconds.coerceIn(5, 300),
        supportsVision = profile.supportsVision,
        supportsStreaming = profile.supportsStreaming,
        enabled = profile.enabled,
        encryptedCustomHeaders = aead.encrypt(json.encodeToString(profile.customHeaders)),
        endpointPath = profile.endpointPath.trim().ifBlank { "/chat/completions" },
        tokenParameter = profile.tokenParameter.name,
        createdAt = profile.createdAt,
        updatedAt = profile.updatedAt,
        supportsTools = profile.supportsTools,
    )
}
