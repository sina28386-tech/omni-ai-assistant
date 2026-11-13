package com.omni.aiassistant.feature.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.network.AiProviderGateway
import com.omni.aiassistant.core.network.EndpointValidator
import com.omni.aiassistant.core.network.ProviderPresets
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.util.Ids
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
import com.omni.aiassistant.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException


data class ProviderDraft(
    val id: String? = null,
    val originalType: ProviderType? = null,
    val name: String = "",
    val type: ProviderType = ProviderType.NVIDIA,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val organizationId: String = "",
    val systemPrompt: String = "",
    val temperature: String = "0.7",
    val maxTokens: String = "1024",
    val timeoutSeconds: String = "60",
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val enabled: Boolean = true,
    val customHeaders: String = "",
    val endpointPath: String = "/chat/completions",
    val tokenParameter: TokenParameter = TokenParameter.AUTO,
)

data class ProvidersUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProviderId: String? = null,
    val localDevelopmentEnabled: Boolean = false,
    val testing: Boolean = false,
    val testSucceeded: Boolean = false,
    val error: Throwable? = null,
    val testResult: ProviderTestResult = ProviderTestResult.IDLE,
    val toolTest: ToolTestResult = ToolTestResult.NOT_RUN,
)

/** Explicit connection-test lifecycle so the editor never infers a result. */
enum class ProviderTestResult { IDLE, RUNNING, SUCCESS, FAILURE }
enum class ToolTestResult { NOT_RUN, CONFIRMED, UNCONFIRMED }

/** Sends only a synthetic tool schema and never executes the returned call. */
internal suspend fun probeToolCalling(gateway: AiProviderGateway, profile: ProviderProfile, localDevelopmentEnabled: Boolean): ToolTestResult {
    if (!profile.supportsTools) return ToolTestResult.NOT_RUN
    return try {
        val now = System.currentTimeMillis()
        val probe = ToolSpec("omni_capability_probe", "A harmless test. Call this tool once.", ToolCategory.DEVICE, ToolRisk.LOW, ToolSchema.objectOf(properties = emptyMap()))
        val message = ChatMessage(id = "tool-probe", conversationId = "tool-probe", role = MessageRole.USER, content = "Call omni_capability_probe now.", status = MessageStatus.COMPLETE, sortIndex = 1, createdAt = now, updatedAt = now)
        val reply = gateway.complete(profile.copy(maxOutputTokens = 128), "This is a tool capability test.", listOf(message), localDevelopmentEnabled, listOf(probe))
        if (reply.toolCalls.any { it.name == probe.name }) ToolTestResult.CONFIRMED else ToolTestResult.UNCONFIRMED
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ToolTestResult.UNCONFIRMED }
}

private data class ProviderStatus(
    val testing: Boolean = false,
    val testSucceeded: Boolean = false,
    val error: Throwable? = null,
    val testResult: ProviderTestResult = ProviderTestResult.IDLE,
    val toolTest: ToolTestResult = ToolTestResult.NOT_RUN,
)

class ProvidersViewModel(
    private val repository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val gateway: AiProviderGateway,
    private val logger: com.omni.aiassistant.core.logging.SafeLogger,
) : ViewModel() {
    private val _status = MutableStateFlow(ProviderStatus())

    val state: StateFlow<ProvidersUiState> = combine(
        repository.observeProfiles(),
        settingsRepository.settings,
        _status,
    ) { profiles, settings, status ->
        ProvidersUiState(
            profiles = profiles,
            activeProviderId = settings.defaultProviderId,
            localDevelopmentEnabled = settings.localDevelopmentEndpoint,
            testing = status.testing,
            testSucceeded = status.testSucceeded,
            error = status.error,
            testResult = status.testResult,
            toolTest = status.toolTest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    init {
        // Older builds auto-created three demo profiles with an embedded
        // provider key. Remove those rows once and never seed credentials
        // automatically again: the app is bring-your-own-key.
        viewModelScope.launch {
            runCatching {
                val legacyIds = setOf("plan-diamond", "plan-pro", "plan-fast")
                val legacy = repository.observeProfiles().first().filter { it.id in legacyIds }
                if (legacy.isEmpty()) return@runCatching
                legacy.forEach { repository.delete(it.id) }
                val active = settingsRepository.settings.first().defaultProviderId
                if (legacy.any { it.id == active }) {
                    settingsRepository.update { it.copy(defaultProviderId = null) }
                }
            }.onFailure {
                logger.warn("Providers", "legacy seed cleanup failed: ${it::class.simpleName}")
            }
        }
    }

    fun newDraft(type: ProviderType = ProviderType.NVIDIA): ProviderDraft {
        val preset = ProviderPresets.forType(type)
        return ProviderDraft(
            type = type,
            name = preset.displayName,
            baseUrl = preset.baseUrl,
            endpointPath = preset.endpointPath,
            supportsStreaming = true,
            // Presets assume OpenAI-style function calling; the editor exposes
            // the switch for models that do not support tools.
            supportsTools = true,
        )
    }

    fun editDraft(profile: ProviderProfile): ProviderDraft = ProviderDraft(
        id = profile.id,
        originalType = profile.type,
        name = profile.name,
        type = profile.type,
        baseUrl = profile.baseUrl,
        apiKey = "",
        modelId = profile.modelId,
        organizationId = profile.organizationId.orEmpty(),
        systemPrompt = profile.systemPrompt,
        temperature = profile.temperature.toString(),
        maxTokens = profile.maxOutputTokens.toString(),
        timeoutSeconds = profile.timeoutSeconds.toString(),
        supportsVision = profile.supportsVision,
        supportsStreaming = profile.supportsStreaming,
        supportsTools = profile.supportsTools,
        enabled = profile.enabled,
        customHeaders = profile.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
        endpointPath = profile.endpointPath,
        tokenParameter = profile.tokenParameter,
    )

    fun applyTypePreset(draft: ProviderDraft, type: ProviderType): ProviderDraft {
        val preset = ProviderPresets.forType(type)
        return draft.copy(
            type = type,
            name = preset.displayName,
            baseUrl = preset.baseUrl,
            endpointPath = preset.endpointPath,
            apiKey = if (draft.originalType == type) draft.apiKey else "",
        )
    }

    fun save(draft: ProviderDraft, onSaved: () -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val profile = buildProfile(draft)
            EndpointValidator.validate(profile, state.value.localDevelopmentEnabled, BuildConfig.ALLOW_LOCAL_CLEARTEXT)
            repository.upsert(profile)
            if (state.value.activeProviderId == null && profile.enabled) {
                settingsRepository.update { it.copy(defaultProviderId = profile.id) }
            }
            _status.value = ProviderStatus()
            onSaved()
        }.onFailure {
            logger.warn("Provider", "save failed: ${it::class.simpleName}")
            _status.value = ProviderStatus(error = it)
        }
    }

    fun test(draft: ProviderDraft) = viewModelScope.launch {
        _status.value = ProviderStatus(testing = true, testResult = ProviderTestResult.RUNNING)
        runCatching {
            val profile = buildProfile(draft)
            EndpointValidator.validate(profile, state.value.localDevelopmentEnabled, BuildConfig.ALLOW_LOCAL_CLEARTEXT)
            gateway.testConnection(profile, state.value.localDevelopmentEnabled)
            probeToolCalling(gateway, profile, state.value.localDevelopmentEnabled)
        }.onSuccess { toolTest ->
            _status.value = ProviderStatus(testSucceeded = true, testResult = ProviderTestResult.SUCCESS, toolTest = toolTest)
        }.onFailure {
            logger.warn("Provider", "connection test failed: ${it::class.simpleName}")
            _status.value = ProviderStatus(error = it, testResult = ProviderTestResult.FAILURE)
        }
    }

    fun setActive(id: String) = viewModelScope.launch {
        val profile = repository.getProfile(id)
        if (profile?.enabled == true) settingsRepository.update { it.copy(defaultProviderId = id) }
    }

    fun delete(id: String) = viewModelScope.launch {
        repository.delete(id)
        if (state.value.activeProviderId == id) settingsRepository.update { it.copy(defaultProviderId = null) }
    }

    fun duplicate(profile: ProviderProfile) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        repository.upsert(profile.copy(id = Ids.newId(), name = "${profile.name} (2)", createdAt = now, updatedAt = now))
    }

    fun clearStatus() { _status.value = ProviderStatus() }

    private suspend fun buildProfile(draft: ProviderDraft): ProviderProfile {
        val existing = draft.id?.let { repository.getProfile(it) }
        val apiKey = when {
            draft.apiKey.isNotBlank() -> draft.apiKey.trim()
            existing != null && existing.type == draft.type && draft.originalType == draft.type -> existing.apiKey
            else -> ""
        }
        val now = System.currentTimeMillis()
        return ProviderProfile(
            id = draft.id ?: Ids.newId(),
            name = draft.name.trim(),
            type = draft.type,
            baseUrl = draft.baseUrl.trim(),
            apiKey = apiKey,
            modelId = draft.modelId.trim(),
            organizationId = draft.organizationId.trim().takeIf(String::isNotBlank),
            systemPrompt = draft.systemPrompt,
            temperature = draft.temperature.toDoubleOrNull() ?: throw AppError.Validation("Temperature must be a number"),
            maxOutputTokens = draft.maxTokens.toIntOrNull() ?: throw AppError.Validation("Maximum tokens must be an integer"),
            timeoutSeconds = draft.timeoutSeconds.toIntOrNull() ?: throw AppError.Validation("Timeout must be an integer"),
            supportsVision = draft.supportsVision,
            supportsStreaming = draft.supportsStreaming,
            supportsTools = draft.supportsTools,
            enabled = draft.enabled,
            customHeaders = parseHeaders(draft.customHeaders),
            endpointPath = draft.endpointPath,
            tokenParameter = draft.tokenParameter,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun parseHeaders(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val index = line.indexOf(':')
            if (index <= 0) throw AppError.Validation("Invalid custom header line: $line")
            val name = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (value.isEmpty()) throw AppError.Validation("Custom header value is empty: $name")
            put(name, value)
        }
    }
}
