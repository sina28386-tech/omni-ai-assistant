package com.omni.aiassistant.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.aiassistant.core.util.Ids
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.bots.BotProfiles
import com.omni.aiassistant.domain.bots.BotValidationException
import com.omni.aiassistant.domain.bots.BotValidationIssue
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Only presentation data crosses into Compose; credentials stay in repositories. */
data class BotItem(
    val id: String,
    val name: String,
    val instructions: String,
    val modelId: String,
    val enabled: Boolean,
    val supportsTools: Boolean,
    val hasConversation: Boolean,
)

data class BotModelChoice(val id: String, val name: String, val modelId: String)

data class BotsUiState(
    val bots: List<BotItem> = emptyList(),
    val models: List<BotModelChoice> = emptyList(),
    val agentMode: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val validation: BotValidationIssue? = null,
    val operationFailed: Boolean = false,
)

private data class BotOperation(
    val busy: Boolean = false,
    val validation: BotValidationIssue? = null,
    val failed: Boolean = false,
)

class BotsViewModel(
    private val providers: ProviderRepository,
    private val conversations: ConversationRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val operation = MutableStateFlow(BotOperation())
    private val operationGate = Mutex()

    val state = combine(
        providers.observeProfiles(),
        conversations.observeConversations(),
        settings.settings,
        operation,
    ) { profiles, chats, preferences, action ->
        BotsUiState(
            bots = profiles.filter { BotProfiles.isBotId(it.id) }
                .sortedByDescending { it.updatedAt }
                .map { profile ->
                    BotItem(profile.id, profile.name, profile.systemPrompt, profile.modelId,
                        profile.enabled, profile.supportsTools,
                        BotProfiles.latestConversation(chats, profile.id) != null)
                },
            models = profiles.filter(BotProfiles::canUseAsBase)
                .map { BotModelChoice(it.id, it.name, it.modelId) },
            agentMode = preferences.agentMode,
            loading = false,
            busy = action.busy,
            validation = action.validation,
            operationFailed = action.failed,
        )
    }.catch {
        if (it is CancellationException) throw it
        emit(BotsUiState(loading = false, operationFailed = true))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BotsUiState())

    fun save(
        botId: String?,
        baseProviderId: String?,
        name: String,
        instructions: String,
        onSaved: () -> Unit,
    ) = perform {
        val now = System.currentTimeMillis()
        val profile = if (botId == null) {
            val base = baseProviderId?.let { providers.getProfile(it) }
                ?: throw BotValidationException(BotValidationIssue.PROVIDER_UNAVAILABLE)
            BotProfiles.create(base, "bot-${Ids.newId()}", name, instructions, now)
        } else {
            BotProfiles.edit(requireBot(botId), name, instructions, now)
        }
        providers.upsert(profile)
        // Do not call ProvidersViewModel.save: its first-provider behavior can
        // change the global default. A bot is always scoped to its own chat.
        onSaved()
    }

    fun openConversation(botId: String, onOpen: (String) -> Unit) = perform {
        val bot = requireBot(botId)
        if (!bot.enabled || bot.modelId.isBlank()) {
            throw BotValidationException(BotValidationIssue.PROVIDER_UNAVAILABLE)
        }
        val existing = BotProfiles.latestConversation(
            conversations.observeConversations().first(), bot.id,
        )
        // Blank conversation override deliberately follows the encrypted bot
        // profile's role through ChatViewModel's existing prompt precedence.
        val conversation = existing ?: conversations.create(bot.id, "")
        onOpen(conversation.id)
    }

    fun delete(botId: String, onDeleted: () -> Unit) = perform {
        val bot = requireBot(botId)
        // Keep history. The confirmation explains that old chats need another
        // provider after their bot configuration is removed.
        providers.delete(bot.id)
        onDeleted()
    }

    fun clearError() {
        operation.update { it.copy(validation = null, failed = false) }
    }

    private suspend fun requireBot(id: String): ProviderProfile {
        if (!BotProfiles.isBotId(id)) throw BotValidationException(BotValidationIssue.INVALID_BOT_ID)
        return providers.getProfile(id)
            ?: throw BotValidationException(BotValidationIssue.INVALID_BOT_ID)
    }

    private fun perform(block: suspend () -> Unit) = viewModelScope.launch {
        if (!operationGate.tryLock()) return@launch
        operation.value = BotOperation(busy = true)
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: BotValidationException) {
            operation.update { it.copy(validation = invalid.issue) }
        } catch (_: Exception) {
            // Never put provider errors, endpoints, prompts or credentials in UI errors.
            operation.update { it.copy(failed = true) }
        } finally {
            operation.update { it.copy(busy = false) }
            operationGate.unlock()
        }
    }
}
