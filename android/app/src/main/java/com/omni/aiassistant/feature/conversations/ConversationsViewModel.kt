package com.omni.aiassistant.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val search: String = "",
    val showArchived: Boolean = false,
    val error: Throwable? = null,
)

class ConversationsViewModel(
    private val repository: ConversationRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val search = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)
    private val error = MutableStateFlow<Throwable?>(null)

    private val source = showArchived.flatMapLatest { repository.observeConversations(it) }

    val state = combine(source, search, showArchived, error) { list, query, archived, err ->
        val filtered = if (query.isBlank()) list else list.filter { it.title.contains(query, ignoreCase = true) }
        ConversationsUiState(filtered, query, archived, err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun setSearch(value: String) { search.value = value.take(100) }
    fun toggleArchived() { showArchived.value = !showArchived.value }
    fun setShowArchived(value: Boolean) { showArchived.value = value }

    fun create(onCreated: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            val settings = settingsRepository.snapshot()
            val allProfiles = providerRepository.observeProfiles().first()
            val default = settings.defaultProviderId?.let { providerRepository.getProfile(it) }
            val providerId = (default?.takeIf { it.enabled } ?: allProfiles.firstOrNull { it.enabled })?.id
            repository.create(providerId, settings.defaultSystemPrompt).id
        }.onSuccess(onCreated).onFailure { error.value = it }
    }

    fun rename(id: String, title: String) = launchCatching { repository.rename(id, title) }
    fun setPinned(id: String, value: Boolean) = launchCatching { repository.setPinned(id, value) }
    fun setArchived(id: String, value: Boolean) = launchCatching { repository.setArchived(id, value) }
    fun delete(id: String) = launchCatching { repository.deleteConversation(id) }
    fun clearAll() = launchCatching { repository.clearAll() }
    fun clearError() { error.value = null }

    private fun launchCatching(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error.value = it }
    }
}
