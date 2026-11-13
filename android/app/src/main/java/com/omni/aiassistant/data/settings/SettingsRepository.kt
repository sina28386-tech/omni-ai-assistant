package com.omni.aiassistant.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.domain.model.AppLanguage
import com.omni.aiassistant.domain.model.AppSettings
import com.omni.aiassistant.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "omni_settings")

class SettingsRepository(private val context: Context, private val aead: KeystoreAead) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val defaultProvider = stringPreferencesKey("default_provider")
        val defaultModel = stringPreferencesKey("default_model")
        val defaultPrompt = stringPreferencesKey("default_prompt")
        val voiceLanguage = stringPreferencesKey("voice_language")
        val ttsLanguage = stringPreferencesKey("tts_language")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsSpeed = floatPreferencesKey("tts_speed")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val autoTts = booleanPreferencesKey("auto_tts")
        val autoSendVoice = booleanPreferencesKey("auto_send_voice")
        val creditSaver = booleanPreferencesKey("credit_saver")
        val maxContext = intPreferencesKey("max_context")
        val defaultMaxTokens = intPreferencesKey("default_max_tokens")
        val imageQuality = intPreferencesKey("image_quality")
        val streaming = booleanPreferencesKey("streaming")
        val localDevelopment = booleanPreferencesKey("local_development")
        val commandTarget = stringPreferencesKey("command_target")
        val agentMode = booleanPreferencesKey("agent_mode")
        val autoApproveMedium = booleanPreferencesKey("auto_approve_medium")
        val autoTypeInApps = booleanPreferencesKey("auto_type_in_apps")
        val trustedTools = stringSetPreferencesKey("trusted_tools")
        val fullPhoneControl = booleanPreferencesKey("full_phone_control")
        val toolAudit = booleanPreferencesKey("tool_audit")
        val showToolActivity = booleanPreferencesKey("show_tool_activity")
        val hermesUrl = stringPreferencesKey("hermes_url")
        val hermesToken = stringPreferencesKey("hermes_token")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val wakeWordPhrase = stringPreferencesKey("wake_word_phrase")
        val maxToolRounds = intPreferencesKey("max_tool_rounds")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::toSettings)

    suspend fun snapshot(): AppSettings = toSettings(context.settingsDataStore.data.first())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(toSettings(prefs))
            prefs[Keys.theme] = next.theme.name
            prefs[Keys.language] = next.language.name
            next.defaultProviderId?.let { prefs[Keys.defaultProvider] = it } ?: prefs.remove(Keys.defaultProvider)
            prefs[Keys.defaultModel] = next.defaultModelId
            prefs[Keys.defaultPrompt] = aead.encrypt(next.defaultSystemPrompt)
            prefs[Keys.voiceLanguage] = next.voiceLanguageTag
            prefs[Keys.ttsLanguage] = next.ttsLanguageTag
            next.ttsVoiceName?.let { prefs[Keys.ttsVoice] = it } ?: prefs.remove(Keys.ttsVoice)
            prefs[Keys.ttsSpeed] = next.ttsSpeed
            prefs[Keys.ttsPitch] = next.ttsPitch
            prefs[Keys.autoTts] = next.autoTts
            prefs[Keys.autoSendVoice] = next.autoSendVoice
            prefs[Keys.creditSaver] = next.creditSaver
            prefs[Keys.maxContext] = next.maxContextMessages
            prefs[Keys.defaultMaxTokens] = next.defaultMaxOutputTokens
            prefs[Keys.imageQuality] = next.imageQuality
            prefs[Keys.streaming] = next.streaming
            prefs[Keys.localDevelopment] = next.localDevelopmentEndpoint
            prefs[Keys.commandTarget] = next.commandTarget.name
            prefs[Keys.agentMode] = next.agentMode
            prefs[Keys.autoApproveMedium] = next.autoApproveMediumRisk
            prefs[Keys.autoTypeInApps] = next.autoTypeInApps
            prefs[Keys.trustedTools] = next.trustedTools
            prefs[Keys.fullPhoneControl] = next.fullPhoneControl
            prefs[Keys.toolAudit] = next.toolAuditEnabled
            prefs[Keys.showToolActivity] = next.showToolActivity
            prefs[Keys.hermesUrl] = next.hermesUrl.trim()
            prefs[Keys.hermesToken] = aead.encrypt(next.hermesToken)
            prefs[Keys.wakeWordEnabled] = next.wakeWordEnabled
            prefs[Keys.wakeWordPhrase] = next.wakeWordPhrase.trim().take(40)
            prefs[Keys.maxToolRounds] = next.maxToolRounds.coerceIn(2, 40)
        }
    }

    suspend fun replace(settings: AppSettings) = update { settings }

    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    private fun toSettings(prefs: Preferences): AppSettings = AppSettings(
        theme = prefs[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        language = prefs[Keys.language]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.ENGLISH,
        defaultProviderId = prefs[Keys.defaultProvider],
        defaultModelId = prefs[Keys.defaultModel].orEmpty(),
        defaultSystemPrompt = prefs[Keys.defaultPrompt]?.let { runCatching { aead.decrypt(it) }.getOrDefault("") }.orEmpty(),
        voiceLanguageTag = prefs[Keys.voiceLanguage] ?: "fa-IR",
        ttsLanguageTag = prefs[Keys.ttsLanguage] ?: "fa-IR",
        ttsVoiceName = prefs[Keys.ttsVoice],
        ttsSpeed = (prefs[Keys.ttsSpeed] ?: 1f).coerceIn(0.5f, 2f),
        ttsPitch = (prefs[Keys.ttsPitch] ?: 1f).coerceIn(0.5f, 2f),
        autoTts = prefs[Keys.autoTts] ?: false,
        autoSendVoice = prefs[Keys.autoSendVoice] ?: false,
        creditSaver = prefs[Keys.creditSaver] ?: false,
        maxContextMessages = (prefs[Keys.maxContext] ?: 16).coerceIn(1, 100),
        defaultMaxOutputTokens = (prefs[Keys.defaultMaxTokens] ?: 1024).coerceIn(64, 32_768),
        imageQuality = (prefs[Keys.imageQuality] ?: 82).coerceIn(40, 95),
        streaming = prefs[Keys.streaming] ?: true,
        localDevelopmentEndpoint = prefs[Keys.localDevelopment] ?: false,
        commandTarget = prefs[Keys.commandTarget]?.let { runCatching { com.omni.aiassistant.domain.model.CommandTarget.valueOf(it) }.getOrNull() }
            ?: com.omni.aiassistant.domain.model.CommandTarget.AUTO,
        agentMode = prefs[Keys.agentMode] ?: true,
        autoApproveMediumRisk = prefs[Keys.autoApproveMedium] ?: false,
        autoTypeInApps = prefs[Keys.autoTypeInApps] ?: false,
        trustedTools = prefs[Keys.trustedTools] ?: emptySet(),
        fullPhoneControl = prefs[Keys.fullPhoneControl] ?: false,
        toolAuditEnabled = prefs[Keys.toolAudit] ?: true,
        showToolActivity = prefs[Keys.showToolActivity] ?: true,
        hermesUrl = prefs[Keys.hermesUrl] ?: "http://10.0.2.2:8765",
        hermesToken = prefs[Keys.hermesToken]?.let { runCatching { aead.decrypt(it) }.getOrDefault("") }.orEmpty(),
        wakeWordEnabled = prefs[Keys.wakeWordEnabled] ?: false,
        wakeWordPhrase = prefs[Keys.wakeWordPhrase] ?: "آرمان",
        maxToolRounds = (prefs[Keys.maxToolRounds] ?: 12).coerceIn(2, 40),
    )
}
