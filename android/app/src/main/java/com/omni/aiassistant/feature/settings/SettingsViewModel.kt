package com.omni.aiassistant.feature.settings

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.core.agent.UserFact
import com.omni.aiassistant.core.agent.UserStyle
import com.omni.aiassistant.core.agent.UserVerb
import com.omni.aiassistant.core.agent.VerbAction
import com.omni.aiassistant.core.logging.DiagnosticsExporter
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.data.backup.BackupManager
import com.omni.aiassistant.data.memory.UserMemoryRepository
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.data.style.UserStyleRepository
import com.omni.aiassistant.data.verbs.UserVerbRepository
import com.omni.aiassistant.device.wake.ArmanWakeService
import com.omni.aiassistant.domain.model.AppLanguage
import com.omni.aiassistant.domain.model.AppSettings
import com.omni.aiassistant.domain.model.CommandTarget
import com.omni.aiassistant.domain.model.ThemeMode
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


enum class SettingsNotice {
    BACKUP_CREATED,
    BACKUP_RESTORED,
    DIAGNOSTICS_EXPORTED,
    DATA_DELETED,
    WAKE_PERMISSION_DENIED,
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val busy: Boolean = false,
    val notice: SettingsNotice? = null,
    val error: Throwable? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
)

private data class OperationState(
    val busy: Boolean = false,
    val notice: SettingsNotice? = null,
    val error: Throwable? = null,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val backupManager: BackupManager,
    private val diagnostics: DiagnosticsExporter,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val aead: KeystoreAead,
    private val logger: com.omni.aiassistant.core.logging.SafeLogger,
    private val verbs: UserVerbRepository,
    private val memory: UserMemoryRepository,
    private val style: UserStyleRepository,
    private val appContext: android.content.Context,
) : ViewModel() {
    private val operation = MutableStateFlow(OperationState())

    val state: StateFlow<SettingsUiState> = combine(repository.settings, operation) { settings, op ->
        SettingsUiState(settings, op.busy, op.notice, op.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** The user's own vocabulary, so the settings screen can show and edit it. */
    val userVerbs: StateFlow<List<UserVerb>> = verbs.verbs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything Arman knows about the user. The user may read and delete it. */
    val userFacts: StateFlow<List<UserFact>> = memory.facts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The communication profile learned from the user's own words. */
    val userStyle: StateFlow<UserStyle> = style.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserStyle())

    fun forgetLearnedStyle() = viewModelScope.launch {
        runCatching { style.clear() }.onFailure { recordError(it) }
    }

    fun addVerb(phrase: String, action: VerbAction, target: String? = null) = viewModelScope.launch {
        runCatching { verbs.upsert(phrase, action, target) }
            .onFailure { recordError(it) }
    }

    fun removeVerb(id: String) = viewModelScope.launch {
        runCatching { verbs.remove(id) }.onFailure { recordError(it) }
    }

    fun forgetFact(id: String) = viewModelScope.launch {
        runCatching { memory.forget(id) }.onFailure { recordError(it) }
    }

    fun forgetAllFacts() = viewModelScope.launch {
        runCatching { memory.clear() }.onFailure { recordError(it) }
    }

    fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }
    fun setLanguage(value: AppLanguage) {
        viewModelScope.launch {
            repository.update { it.copy(language = value) }
            val tag = if (value == AppLanguage.PERSIAN) "fa" else "en"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
    fun setDefaultProvider(id: String?) = update { it.copy(defaultProviderId = id) }
    fun setDefaultModel(value: String) = update { it.copy(defaultModelId = value.trim().take(500)) }
    fun setDefaultPrompt(value: String) = update { it.copy(defaultSystemPrompt = value.take(20_000)) }
    fun setVoiceLanguage(value: String) = update { it.copy(voiceLanguageTag = value.take(30)) }
    fun setTtsLanguage(value: String) = update { it.copy(ttsLanguageTag = value.take(30), ttsVoiceName = null) }
    fun setTtsVoice(value: String?) = update { it.copy(ttsVoiceName = value) }
    fun setTtsSpeed(value: Float) = update { it.copy(ttsSpeed = value.coerceIn(0.5f, 2f)) }
    fun setTtsPitch(value: Float) = update { it.copy(ttsPitch = value.coerceIn(0.5f, 2f)) }
    fun setAutoTts(value: Boolean) = update { it.copy(autoTts = value) }
    fun setAutoSendVoice(value: Boolean) = update { it.copy(autoSendVoice = value) }
    fun setCreditSaver(value: Boolean) = update { it.copy(creditSaver = value) }
    fun setMaxContext(value: Int) = update { it.copy(maxContextMessages = value.coerceIn(1, 100)) }
    fun setMaxTokens(value: Int) = update { it.copy(defaultMaxOutputTokens = value.coerceIn(64, 32_768)) }
    fun setImageQuality(value: Int) = update { it.copy(imageQuality = value.coerceIn(40, 95)) }
    fun setStreaming(value: Boolean) = update { it.copy(streaming = value) }
    fun setLocalDevelopment(value: Boolean) = update { it.copy(localDevelopmentEndpoint = BuildConfig.DEBUG && value) }
    fun setCommandTarget(value: CommandTarget) = update { it.copy(commandTarget = value) }

    fun setAgentMode(value: Boolean) = update { it.copy(agentMode = value) }

    fun setAutoApproveMediumRisk(value: Boolean) =
        update { it.copy(autoApproveMediumRisk = value) }

    fun setAutoTypeInApps(value: Boolean) = update { it.copy(autoTypeInApps = value) }

    /**
     * Withdraws Omni's own master switch. Android permissions are intentionally
     * left untouched: revoking them is the system's job, and trying to do it
     * silently here would be both surprising and unreliable across OEMs.
     */
    fun setFullPhoneControl(value: Boolean) = update { it.copy(fullPhoneControl = value) }

    fun setToolAuditEnabled(value: Boolean) = update { it.copy(toolAuditEnabled = value) }

    fun setShowToolActivity(value: Boolean) = update { it.copy(showToolActivity = value) }

    /** Shows a one-off message in the settings snackbar. */
    fun setNotice(notice: SettingsNotice) {
        operation.value = OperationState(notice = notice)
    }

    /**
     * Turns the wake-word listener on or off and starts or stops the
     * microphone service accordingly.
     */
    fun setWakeWordEnabled(value: Boolean) = viewModelScope.launch {
        repository.update { it.copy(wakeWordEnabled = value) }
        val context = appContext
        if (value) {
            ArmanWakeService.start(context)
        } else {
            ArmanWakeService.stop(context)
        }
    }

    fun setWakeWordPhrase(value: String) = update {
        it.copy(wakeWordPhrase = value.trim().take(40).ifBlank { ArmanWakeService.DEFAULT_WAKE_PHRASE })
    }

    /** Blank input restores the built-in address; malformed input is ignored. */
    fun setHermesUrl(value: String) {
        val normalized = normalizeHermesUrl(value) ?: return
        update { it.copy(hermesUrl = normalized) }
    }

    /** The token is stored through the encrypted repository and is never logged. */
    fun setHermesToken(value: String) =
        update { it.copy(hermesToken = value.trim().take(MAX_HERMES_TOKEN_LENGTH)) }

    fun removeTrustedTool(name: String) {
        val clean = name.trim().take(200)
        if (clean.isEmpty()) return
        update { it.copy(trustedTools = it.trustedTools - clean) }
    }

    fun resetTrustedTools() = update { it.copy(trustedTools = emptySet()) }

    fun exportBackup(uri: Uri, password: String) = runOperation(SettingsNotice.BACKUP_CREATED) {
        backupManager.export(uri, password.toCharArray())
    }

    fun restoreBackup(uri: Uri, password: String) = runOperation(SettingsNotice.BACKUP_RESTORED) {
        backupManager.restore(uri, password.toCharArray())
    }

    fun exportDiagnostics(uri: Uri) = runOperation(SettingsNotice.DIAGNOSTICS_EXPORTED) {
        diagnostics.export(uri)
    }

    fun deleteAllData() = runOperation(SettingsNotice.DATA_DELETED) {
        conversations.clearAll()
        providers.deleteAll()
        repository.clear()
        aead.deleteKey()
    }

    fun clearStatus() { operation.value = OperationState() }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { runCatching { repository.update(transform) }.onFailure(::recordError) }
    }

    private fun runOperation(success: SettingsNotice, block: suspend () -> Unit) = viewModelScope.launch {
        operation.value = OperationState(busy = true)
        runCatching { block() }
            .onSuccess { operation.value = OperationState(notice = success) }
            .onFailure { recordError(it) }
    }

    private fun recordError(t: Throwable) {
        logger.warn("Settings", t::class.simpleName ?: "UnknownError")
        operation.value = OperationState(error = t)
    }
}
