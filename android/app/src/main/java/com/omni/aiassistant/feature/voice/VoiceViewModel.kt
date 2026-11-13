package com.omni.aiassistant.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


data class VoiceCommand(val conversationId: String, val text: String)

data class VoiceUiState(
    val recognizedText: String = "",
    val loading: Boolean = false,
    val error: Throwable? = null,
    val conversationId: String? = null,
    val pendingCommand: VoiceCommand? = null,
    val settings: com.omni.aiassistant.domain.model.AppSettings = com.omni.aiassistant.domain.model.AppSettings(),
)

class VoiceViewModel(
    private val conversations: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val logger: com.omni.aiassistant.core.logging.SafeLogger,
) : ViewModel() {
    private val local = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = combine(local, settingsRepository.settings) { l, settings -> l.copy(settings = settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())

    fun setRecognizedText(value: String) {
        local.update { it.copy(recognizedText = value.take(100_000), error = null) }
    }

    fun submit() = viewModelScope.launch {
        val text = local.value.recognizedText.trim()
        if (text.isBlank() || local.value.loading || local.value.pendingCommand != null) return@launch
        local.update { it.copy(loading = true, error = null) }
        try {
            val settings = settingsRepository.snapshot()
            val existing = local.value.conversationId?.let { conversations.get(it) }
            val conversation = existing ?: conversations.create(settings.defaultProviderId, settings.defaultSystemPrompt)
            // Speech is an input mode. Chat owns model requests, tools, memory,
            // approval and TTS; never run a separate one-shot provider request.
            local.update {
                it.copy(conversationId = conversation.id, pendingCommand = VoiceCommand(conversation.id, text))
            }
        } catch (cancelled: CancellationException) {
            local.update { it.copy(loading = false) }
            throw cancelled
        } catch (t: Throwable) {
            logger.warn("Voice", t::class.simpleName ?: "UnknownError")
            local.update { it.copy(loading = false, error = t) }
        }
    }

    fun clearError() { local.update { it.copy(error = null) } }

    fun consumeCommand() {
        local.update { it.copy(pendingCommand = null, loading = false) }
    }

    fun setReadAloud(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(autoTts = enabled) }
    }
}
