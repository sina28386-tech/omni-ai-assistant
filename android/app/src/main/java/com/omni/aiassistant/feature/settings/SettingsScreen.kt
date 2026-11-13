package com.omni.aiassistant.feature.settings

import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.R
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.ui.AppErrorDialog
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniGroup
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniListItem
import com.omni.aiassistant.core.ui.OmniPage
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSegmented
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniSwitchRow
import com.omni.aiassistant.core.ui.OmniTextField
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.device.AndroidTtsController
import com.omni.aiassistant.device.tools.ToolRegistry
import com.omni.aiassistant.domain.model.AppLanguage
import com.omni.aiassistant.domain.model.ThemeMode
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class BackupAction { EXPORT, RESTORE }

private enum class SettingsOperation { BACKUP_EXPORT, BACKUP_RESTORE, DIAGNOSTICS, DELETE }

/**
 * [onOpenThisPhone] is optional because the destination is owned by the app
 * shell. When it is null the permission link is omitted instead of rendering a
 * dead row; wire it to `navController.navigate("devices")` in the shell.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onOpenThisPhone: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolRegistry: ToolRegistry = koinInject()
    val specsByName = remember(toolRegistry) { toolRegistry.specs().associateBy { it.name } }
    val userVerbs by viewModel.userVerbs.collectAsStateWithLifecycle()
    val userFacts by viewModel.userFacts.collectAsStateWithLifecycle()
    val userStyle by viewModel.userStyle.collectAsStateWithLifecycle()
    val tts = remember { AndroidTtsController(context) }
    var availableVoices by remember { mutableStateOf(emptyList<Voice>()) }
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var resetTrustConfirm by remember { mutableStateOf(false) }
    var activeOperation by remember { mutableStateOf<SettingsOperation?>(null) }
    var hermesUrlDraft by remember(state.settings.hermesUrl) { mutableStateOf(state.settings.hermesUrl) }
    var hermesTokenDraft by remember(state.settings.hermesToken) { mutableStateOf(state.settings.hermesToken) }
    var tokenVisible by remember { mutableStateOf(false) }
    var bridgeSaveRequested by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val backupFileName = stringResource(R.string.backup_file_name)
    val diagnosticsFileName = stringResource(R.string.diagnostics_file_name)

    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    LaunchedEffect(state.settings.ttsLanguageTag) {
        repeat(6) {
            delay(250)
            availableVoices = tts.availableVoices(state.settings.ttsLanguageTag)
            if (availableVoices.isNotEmpty()) return@LaunchedEffect
        }
    }
    LaunchedEffect(state.busy) {
        if (!state.busy) activeOperation = null
    }

    val normalizedBridgeUrl = normalizeHermesUrl(hermesUrlDraft)
    val bridgeUrlValid = normalizedBridgeUrl != null
    val bridgeSaved = bridgeSaveRequested &&
        state.settings.hermesUrl == normalizedBridgeUrl &&
        state.settings.hermesToken == hermesTokenDraft.trim().take(MAX_HERMES_TOKEN_LENGTH)
    LaunchedEffect(bridgeSaved) {
        if (bridgeSaved) {
            delay(2500)
            bridgeSaveRequested = false
        }
    }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingExportPassword
        if (uri != null && password != null) {
            viewModel.exportBackup(uri, password)
        } else {
            activeOperation = null
        }
        pendingExportPassword = null
        backupPassword = ""
    }
    val backupOpenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            backupAction = BackupAction.RESTORE
            backupPassword = ""
        }
    }
    val diagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportDiagnostics(uri)
        } else {
            activeOperation = null
        }
    }

    AppErrorDialog(state.error, viewModel::clearStatus)

    if (backupAction == BackupAction.EXPORT) {
        PasswordDialog(
            title = stringResource(R.string.create_backup),
            description = stringResource(R.string.settings_password_export_body),
            password = backupPassword,
            onPassword = { backupPassword = it.take(256) },
            onDismiss = { backupAction = null; backupPassword = "" },
            onConfirm = {
                if (backupPassword.length >= 10) {
                    pendingExportPassword = backupPassword
                    backupAction = null
                    activeOperation = SettingsOperation.BACKUP_EXPORT
                    backupCreateLauncher.launch(backupFileName)
                }
            },
        )
    }
    if (backupAction == BackupAction.RESTORE && pendingRestoreUri != null && !restoreConfirm) {
        PasswordDialog(
            title = stringResource(R.string.restore_backup),
            description = stringResource(R.string.settings_password_restore_body),
            password = backupPassword,
            onPassword = { backupPassword = it.take(256) },
            onDismiss = { backupAction = null; pendingRestoreUri = null; backupPassword = "" },
            onConfirm = { if (backupPassword.length >= 10) restoreConfirm = true },
        )
    }
    if (restoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.restore_confirm_title),
            body = stringResource(R.string.restore_warning),
            confirmLabel = stringResource(R.string.settings_restore_confirm_action),
            danger = true,
            onDismiss = {
                restoreConfirm = false
                backupAction = null
                pendingRestoreUri = null
                backupPassword = ""
            },
            onConfirm = {
                val uri = pendingRestoreUri
                val password = backupPassword
                restoreConfirm = false
                backupAction = null
                pendingRestoreUri = null
                backupPassword = ""
                if (uri != null) {
                    activeOperation = SettingsOperation.BACKUP_RESTORE
                    viewModel.restoreBackup(uri, password)
                }
            },
        )
    }
    if (deleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.delete_all_title),
            body = stringResource(R.string.delete_all_body),
            confirmLabel = stringResource(R.string.yes_delete),
            danger = true,
            onDismiss = { deleteConfirm = false },
            onConfirm = {
                deleteConfirm = false
                activeOperation = SettingsOperation.DELETE
                viewModel.deleteAllData()
            },
        )
    }
    if (resetTrustConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_trusted_reset_title),
            body = stringResource(R.string.settings_trusted_reset_body),
            confirmLabel = stringResource(R.string.settings_trusted_reset_action),
            danger = false,
            onDismiss = { resetTrustConfirm = false },
            onConfirm = {
                resetTrustConfirm = false
                viewModel.resetTrustedTools()
            },
        )
    }

    val noticeText = state.notice?.let { notice ->
        stringResource(
            when (notice) {
                SettingsNotice.BACKUP_CREATED -> R.string.backup_success
                SettingsNotice.BACKUP_RESTORED -> R.string.restore_success
                SettingsNotice.DIAGNOSTICS_EXPORTED -> R.string.diagnostics_success
                SettingsNotice.DATA_DELETED -> R.string.delete_all_success
                SettingsNotice.WAKE_PERMISSION_DENIED -> R.string.wake_permission_denied_notice
            },
        )
    }
    val closeLabel = stringResource(R.string.close)
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbarHostState.showSnackbar(
                message = noticeText,
                actionLabel = closeLabel,
                duration = SnackbarDuration.Short,
            )
            viewModel.clearStatus()
        }
    }

    val busyLabel = stringResource(
        when (activeOperation) {
            SettingsOperation.BACKUP_EXPORT -> R.string.settings_backup_creating
            SettingsOperation.BACKUP_RESTORE -> R.string.settings_backup_restoring
            SettingsOperation.DIAGNOSTICS -> R.string.settings_diagnostics_exporting
            SettingsOperation.DELETE -> R.string.settings_deleting
            null -> R.string.settings_working
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        OmniPage(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = OmniSpacing.xl,
                    bottom = OmniSpacing.giant * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxl),
            ) {
                item {
                    ScreenHeader(
                        eyebrow = stringResource(R.string.settings_eyebrow),
                        title = stringResource(R.string.settings),
                        subtitle = stringResource(R.string.settings_subtitle),
                    )
                }
                item {
                    AssistantSection(
                        state = state,
                        viewModel = viewModel,
                        onOpenThisPhone = onOpenThisPhone,
                    )
                }
                item { ModelsSection(state = state, viewModel = viewModel) }
                item { VoiceSection(state = state, viewModel = viewModel, voices = availableVoices) }
                item {
                    BridgeSection(
                        urlDraft = hermesUrlDraft,
                        onUrlDraft = { hermesUrlDraft = it.take(MAX_HERMES_URL_LENGTH) },
                        tokenDraft = hermesTokenDraft,
                        onTokenDraft = { hermesTokenDraft = it.take(MAX_HERMES_TOKEN_LENGTH) },
                        tokenVisible = tokenVisible,
                        onToggleToken = { tokenVisible = !tokenVisible },
                        saved = bridgeSaved,
                        onSave = {
                            val normalizedUrl = normalizeHermesUrl(hermesUrlDraft)
                            if (normalizedUrl != null) {
                                val cleanToken = hermesTokenDraft.trim().take(MAX_HERMES_TOKEN_LENGTH)
                                hermesUrlDraft = normalizedUrl
                                hermesTokenDraft = cleanToken
                                viewModel.setHermesUrl(normalizedUrl)
                                viewModel.setHermesToken(cleanToken)
                                bridgeSaveRequested = true
                            }
                        },
                    )
                }
                item {
                    PrivacySection(
                        state = state,
                        viewModel = viewModel,
                        specsByName = specsByName,
                        onRequestReset = { resetTrustConfirm = true },
                    )
                }
                item {
                    DataSection(
                        onCreateBackup = { if (!state.busy) backupAction = BackupAction.EXPORT },
                        onRestore = {
                            if (!state.busy) {
                                backupOpenLauncher.launch(
                                    arrayOf("application/octet-stream", "application/json", "*/*"),
                                )
                            }
                        },
                        onDiagnostics = {
                            if (!state.busy) {
                                activeOperation = SettingsOperation.DIAGNOSTICS
                                diagnosticsLauncher.launch(diagnosticsFileName)
                            }
                        },
                        onDeleteAll = { if (!state.busy) deleteConfirm = true },
                    )
                }
                item {
                    PersonalSection(
                        viewModel = viewModel,
                        verbs = userVerbs,
                        facts = userFacts,
                        style = userStyle,
                    )
                }
                item { AboutSection(state = state, viewModel = viewModel) }            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = OmniSpacing.screenHorizontal, vertical = OmniSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            AnimatedVisibility(visible = state.busy) {
                BusyBanner(label = busyLabel)
            }
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}

@Composable
private fun AssistantSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenThisPhone: (() -> Unit)?,
) {
    val languageIndex = AppLanguage.entries.indexOf(state.settings.language).coerceAtLeast(0)
    val themeIndex = ThemeMode.entries.indexOf(state.settings.theme).coerceAtLeast(0)
    val languageOptions = listOf(
        stringResource(R.string.language_english),
        stringResource(R.string.language_persian),
    )
    val themeOptions = listOf(
        stringResource(R.string.theme_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )

    SettingsSection(stringResource(R.string.settings_section_assistant)) {
        SegmentedSetting(
            label = stringResource(R.string.language),
            options = languageOptions,
            selectedIndex = languageIndex,
            onSelect = { viewModel.setLanguage(AppLanguage.entries[it]) },
            showDivider = true,
        )
        SegmentedSetting(
            label = stringResource(R.string.theme),
            options = themeOptions,
            selectedIndex = themeIndex,
            onSelect = { viewModel.setTheme(ThemeMode.entries[it]) },
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_agent_mode),
            checked = state.settings.agentMode,
            onCheckedChange = viewModel::setAgentMode,
            subtitle = stringResource(R.string.settings_agent_mode_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_auto_type_apps),
            checked = state.settings.autoTypeInApps,
            onCheckedChange = viewModel::setAutoTypeInApps,
            subtitle = stringResource(R.string.settings_auto_type_apps_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_auto_approve),
            checked = state.settings.autoApproveMediumRisk,
            onCheckedChange = viewModel::setAutoApproveMediumRisk,
            subtitle = stringResource(R.string.settings_auto_approve_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_full_control),
            checked = state.settings.fullPhoneControl,
            onCheckedChange = viewModel::setFullPhoneControl,
            subtitle = stringResource(R.string.settings_full_control_summary),
            showDivider = true,
        )
        GroupRow(showDivider = onOpenThisPhone != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_full_control_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onOpenThisPhone != null) {
            OmniListItem(
                title = stringResource(R.string.settings_manage_phone),
                subtitle = stringResource(R.string.settings_manage_phone_subtitle),
                trailing = { NavChevron() },
                onClick = onOpenThisPhone,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun ModelsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val imageQuality = stringResource(R.string.settings_image_quality_value, state.settings.imageQuality)
    SettingsSection(stringResource(R.string.settings_section_models)) {
        TextSetting(
            label = stringResource(R.string.default_model),
            value = state.settings.defaultModelId,
            onValueChange = viewModel::setDefaultModel,
            supporting = stringResource(R.string.default_model_hint),
            showDivider = true,
            placeholder = stringResource(R.string.settings_model_placeholder),
            textDirection = TextDirection.Ltr,
        )
        NumberSetting(
            title = stringResource(R.string.max_context_messages),
            value = state.settings.maxContextMessages,
            range = 1..100,
            step = 1,
            supporting = stringResource(R.string.settings_max_context_supporting),
            onValue = viewModel::setMaxContext,
            showDivider = true,
        )
        NumberSetting(
            title = stringResource(R.string.default_max_tokens),
            value = state.settings.defaultMaxOutputTokens,
            range = 64..32_768,
            step = 512,
            supporting = stringResource(R.string.settings_max_tokens_supporting),
            onValue = viewModel::setMaxTokens,
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.credit_saver),
            checked = state.settings.creditSaver,
            onCheckedChange = viewModel::setCreditSaver,
            subtitle = stringResource(R.string.credit_saver_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.streaming),
            checked = state.settings.streaming,
            onCheckedChange = viewModel::setStreaming,
            subtitle = stringResource(R.string.settings_streaming_summary),
            showDivider = true,
        )
        SliderSetting(
            title = stringResource(R.string.image_quality),
            value = state.settings.imageQuality.toFloat(),
            valueText = imageQuality,
            range = 40f..95f,
            steps = 10,
            supporting = stringResource(R.string.settings_image_quality_description),
            startLabel = stringResource(R.string.settings_image_quality_low),
            endLabel = stringResource(R.string.settings_image_quality_high),
            onValue = { viewModel.setImageQuality(it.toInt()) },
            showDivider = false,
        )
    }
}

@Composable
private fun VoiceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    voices: List<Voice>,
) {
    val context = LocalContext.current
    val automatic = stringResource(R.string.automatic)
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    val selectedName = state.settings.ttsVoiceName
    val voiceSubtitle = when {
        selectedName != null -> selectedName
        voices.isEmpty() -> stringResource(R.string.settings_voice_empty)
        else -> automatic
    }
    val speedValue = stringResource(R.string.settings_value_multiplier, state.settings.ttsSpeed)
    val pitchValue = stringResource(R.string.settings_value_decimal, state.settings.ttsPitch)

    SettingsSection(stringResource(R.string.settings_section_voice)) {
        // The microphone foreground service only works with the runtime
        // permission in place, so the switch asks for it first and only
        // enables the feature when Android grants it.
        val wakePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            val microphone = granted[android.Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            if (microphone) {
                viewModel.setWakeWordEnabled(true)
            } else {
                viewModel.setWakeWordEnabled(false)
                viewModel.setNotice(SettingsNotice.WAKE_PERMISSION_DENIED)
            }
        }
        OmniSwitchRow(
            title = stringResource(R.string.wake_enable_title),
            checked = state.settings.wakeWordEnabled,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    viewModel.setWakeWordEnabled(false)
                } else {
                    val missing = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missing += android.Manifest.permission.RECORD_AUDIO
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missing += android.Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (missing.isEmpty()) {
                        viewModel.setWakeWordEnabled(true)
                    } else {
                        wakePermissionLauncher.launch(missing.toTypedArray())
                    }
                }
            },
            subtitle = stringResource(R.string.wake_enable_summary),
            showDivider = true,
        )
        TextSetting(
            label = stringResource(R.string.wake_phrase_title),
            value = state.settings.wakeWordPhrase,
            onValueChange = viewModel::setWakeWordPhrase,
            supporting = stringResource(R.string.wake_phrase_summary),
            showDivider = true,
            placeholder = stringResource(R.string.wake_phrase_placeholder),
        )
        OmniListItem(
            title = stringResource(R.string.wake_mic_permission_title),
            subtitle = stringResource(R.string.wake_mic_permission_body),
            showDivider = true,
        )
        OmniListItem(
            title = stringResource(R.string.wake_notification_permission_title),
            subtitle = stringResource(R.string.wake_notification_permission_body),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.auto_tts),
            checked = state.settings.autoTts,
            onCheckedChange = viewModel::setAutoTts,
            subtitle = stringResource(R.string.settings_auto_tts_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.auto_send_voice),
            checked = state.settings.autoSendVoice,
            onCheckedChange = viewModel::setAutoSendVoice,
            subtitle = stringResource(R.string.settings_auto_send_summary),
            showDivider = true,
        )
        TextSetting(
            label = stringResource(R.string.voice_input_language),
            value = state.settings.voiceLanguageTag,
            onValueChange = viewModel::setVoiceLanguage,
            supporting = stringResource(R.string.settings_language_tag_supporting),
            showDivider = true,
            placeholder = stringResource(R.string.settings_language_tag_placeholder),
            textDirection = TextDirection.Ltr,
        )
        TextSetting(
            label = stringResource(R.string.tts_language),
            value = state.settings.ttsLanguageTag,
            onValueChange = viewModel::setTtsLanguage,
            supporting = stringResource(R.string.settings_tts_language_supporting),
            showDivider = true,
            placeholder = stringResource(R.string.settings_language_tag_placeholder),
            textDirection = TextDirection.Ltr,
        )
        Box(Modifier.fillMaxWidth()) {
            OmniListItem(
                title = stringResource(R.string.tts_voice),
                subtitle = voiceSubtitle,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { voiceMenuExpanded = true },
                showDivider = true,
            )
            DropdownMenu(
                expanded = voiceMenuExpanded,
                onDismissRequest = { voiceMenuExpanded = false },
                modifier = Modifier.widthIn(min = 220.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(automatic) },
                    onClick = {
                        voiceMenuExpanded = false
                        viewModel.setTtsVoice(null)
                    },
                    leadingIcon = if (selectedName == null) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
                voices.forEach { voice ->
                    val selected = selectedName == voice.name
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxs)) {
                                Text(
                                    text = voice.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = voice.locale.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = {
                            voiceMenuExpanded = false
                            viewModel.setTtsVoice(voice.name)
                        },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        SliderSetting(
            title = stringResource(R.string.tts_speed),
            value = state.settings.ttsSpeed,
            valueText = speedValue,
            range = 0.5f..2f,
            supporting = null,
            startLabel = stringResource(R.string.settings_speed_slow),
            endLabel = stringResource(R.string.settings_speed_fast),
            onValue = viewModel::setTtsSpeed,
            showDivider = true,
        )
        SliderSetting(
            title = stringResource(R.string.tts_pitch),
            value = state.settings.ttsPitch,
            valueText = pitchValue,
            range = 0.5f..2f,
            supporting = null,
            startLabel = stringResource(R.string.settings_pitch_low),
            endLabel = stringResource(R.string.settings_pitch_high),
            onValue = viewModel::setTtsPitch,
            showDivider = false,
        )
    }
}

@Composable
private fun BridgeSection(
    urlDraft: String,
    onUrlDraft: (String) -> Unit,
    tokenDraft: String,
    onTokenDraft: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleToken: () -> Unit,
    saved: Boolean,
    onSave: () -> Unit,
) {
    val normalized = normalizeHermesUrl(urlDraft)
    val valid = normalized != null
    val invalid = urlDraft.isNotBlank() && !valid
    val cleartext = normalized?.let(::isCleartextHermesUrl) == true

    SettingsSection(stringResource(R.string.settings_section_bridge)) {
        GroupRow(showDivider = true) {
            Text(
                text = stringResource(R.string.settings_bridge_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupRow(showDivider = true) {
            Text(
                text = stringResource(R.string.settings_bridge_url),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OmniTextField(
                value = urlDraft,
                onValueChange = onUrlDraft,
                placeholder = stringResource(R.string.settings_bridge_url_placeholder),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            Text(
                text = stringResource(
                    if (invalid) R.string.settings_bridge_url_invalid else R.string.settings_bridge_url_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        GroupRow(showDivider = true) {
            Text(
                text = stringResource(R.string.settings_bridge_token),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SecretField(
                value = tokenDraft,
                onValueChange = onTokenDraft,
                placeholder = stringResource(R.string.settings_bridge_token_placeholder),
                visible = tokenVisible,
                onToggleVisibility = onToggleToken,
                showDescription = stringResource(R.string.settings_show_token),
                hideDescription = stringResource(R.string.settings_hide_token),
            )
            Text(
                text = stringResource(R.string.settings_bridge_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cleartext) {
            GroupRow(showDivider = true) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_bridge_http_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        GroupRow(showDivider = false) {
            OmniSecondaryButton(
                text = stringResource(R.string.settings_bridge_save),
                onClick = onSave,
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            )
            if (saved) {
                Text(
                    text = stringResource(R.string.settings_bridge_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun PrivacySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    specsByName: Map<String, ToolSpec>,
    onRequestReset: () -> Unit,
) {
    val trusted = state.settings.trustedTools.sorted()
    SettingsSection(stringResource(R.string.settings_section_privacy)) {
        OmniSwitchRow(
            title = stringResource(R.string.settings_show_tool_activity),
            checked = state.settings.showToolActivity,
            onCheckedChange = viewModel::setShowToolActivity,
            subtitle = stringResource(R.string.settings_show_tool_activity_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_tool_audit),
            checked = state.settings.toolAuditEnabled,
            onCheckedChange = viewModel::setToolAuditEnabled,
            subtitle = stringResource(R.string.settings_tool_audit_summary),
            showDivider = true,
        )
        OmniListItem(
            title = stringResource(R.string.settings_trusted_tools),
            subtitle = stringResource(
                if (trusted.isEmpty()) R.string.settings_trusted_empty else R.string.settings_trusted_tools_summary,
            ),
            showDivider = trusted.isNotEmpty(),
        )
        trusted.forEach { name ->
            TrustedToolRow(
                name = name,
                spec = specsByName[name],
                showDivider = true,
                onRemove = { viewModel.removeTrustedTool(name) },
            )
        }
        if (trusted.isNotEmpty()) {
            OmniListItem(
                title = stringResource(R.string.settings_trusted_reset),
                subtitle = stringResource(R.string.settings_trusted_reset_summary, trusted.size),
                onClick = onRequestReset,
                danger = true,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun TrustedToolRow(
    name: String,
    spec: ToolSpec?,
    showDivider: Boolean,
    onRemove: () -> Unit,
) {
    val displayName = prettyToolName(name)
    val riskText = if (spec != null) {
        riskLabel(spec.risk)
    } else {
        stringResource(R.string.settings_risk_unknown)
    }
    val removeLabel = stringResource(R.string.settings_trusted_remove, displayName)
    OmniListItem(
        title = displayName,
        subtitle = stringResource(R.string.settings_trusted_risk_line, riskText, name),
        trailing = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = removeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        showDivider = showDivider,
    )
}

@Composable
private fun PersonalSection(
    viewModel: SettingsViewModel,
    verbs: List<com.omni.aiassistant.core.agent.UserVerb>,
    facts: List<com.omni.aiassistant.core.agent.UserFact>,
    style: com.omni.aiassistant.core.agent.UserStyle,
) {
    var phrase by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(com.omni.aiassistant.core.agent.VerbAction.SCROLL_DOWN) }
    var menuOpen by remember { mutableStateOf(false) }
    val actionLabels = listOf(
        com.omni.aiassistant.core.agent.VerbAction.SCROLL_DOWN to R.string.verb_action_scroll_down,
        com.omni.aiassistant.core.agent.VerbAction.SCROLL_UP to R.string.verb_action_scroll_up,
        com.omni.aiassistant.core.agent.VerbAction.SCREENSHOT to R.string.verb_action_screenshot,
        com.omni.aiassistant.core.agent.VerbAction.READ_SCREEN to R.string.verb_action_read_screen,
        com.omni.aiassistant.core.agent.VerbAction.OPEN_APP to R.string.verb_action_open_app,
        com.omni.aiassistant.core.agent.VerbAction.SAY to R.string.verb_action_say,
    )
    SettingsSection(stringResource(R.string.settings_section_personal)) {
        OmniListItem(
            title = stringResource(R.string.settings_verbs_title),
            subtitle = stringResource(
                if (verbs.isEmpty()) R.string.settings_verbs_empty else R.string.settings_verbs_summary,
            ),
            showDivider = verbs.isNotEmpty() || phrase.isNotEmpty(),
        )
        verbs.forEach { verb ->
            val removeLabel = stringResource(R.string.settings_verb_remove, verb.phrase)
            OmniListItem(
                title = verb.phrase,
                subtitle = stringResource(
                    actionLabels.firstOrNull { it.first == verb.action }?.second
                        ?: R.string.verb_action_scroll_down,
                ),
                trailing = {
                    IconButton(onClick = { viewModel.removeVerb(verb.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = removeLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                showDivider = true,
            )
        }
        GroupRow(showDivider = facts.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_verb_add_phrase),
                style = MaterialTheme.typography.titleMedium,
            )
            OmniTextField(
                value = phrase,
                onValueChange = { phrase = it.take(40) },
                placeholder = stringResource(R.string.wake_phrase_placeholder),
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            )
            Spacer(Modifier.height(OmniSpacing.xs))
            Box(Modifier.fillMaxWidth()) {
                OmniSecondaryButton(
                    text = stringResource(actionLabels.first { it.first == action }.second),
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    actionLabels.forEach { (value, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                action = value
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(OmniSpacing.xs))
            OmniPrimaryButton(
                text = stringResource(R.string.settings_verb_add),
                enabled = phrase.isNotBlank(),
                onClick = {
                    viewModel.addVerb(phrase, action)
                    phrase = ""
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val styleSummary = buildList {
            if (style.name.isNotBlank()) add(stringResource(R.string.settings_style_name, style.name))
            when (style.language) {
                "fa" -> add(stringResource(R.string.settings_style_lang_fa))
                "en" -> add(stringResource(R.string.settings_style_lang_en))
                "mixed" -> add(stringResource(R.string.settings_style_lang_mixed))
            }
            when (style.formality) {
                "formal" -> add(stringResource(R.string.settings_style_formal))
                "informal" -> add(stringResource(R.string.settings_style_informal))
            }
            when (style.verbosity) {
                "short" -> add(stringResource(R.string.settings_style_short))
                "detailed" -> add(stringResource(R.string.settings_style_detailed))
            }
            when (style.emoji) {
                "never" -> add(stringResource(R.string.settings_style_no_emoji))
                "sometimes" -> add(stringResource(R.string.settings_style_emoji))
            }
        }.joinToString(" • ")
        OmniListItem(
            title = stringResource(R.string.settings_style_title),
            subtitle = styleSummary.ifEmpty { stringResource(R.string.settings_style_empty) },
            showDivider = true,
        )
        if (!style.isEmpty) {
            style.notes.forEach { note ->
                OmniListItem(
                    title = stringResource(R.string.settings_style_note),
                    subtitle = note,
                    showDivider = true,
                )
            }
            OmniListItem(
                title = stringResource(R.string.settings_style_clear),
                subtitle = stringResource(R.string.settings_style_clear_summary),
                onClick = viewModel::forgetLearnedStyle,
                danger = true,
                showDivider = true,
            )
        }
        OmniListItem(
            title = stringResource(R.string.settings_memory_title),
            subtitle = stringResource(
                if (facts.isEmpty()) R.string.settings_memory_empty else R.string.settings_memory_summary,
            ),
            showDivider = facts.isNotEmpty(),
        )
        facts.forEach { fact ->
            val removeLabel = stringResource(R.string.settings_memory_remove, fact.key)
            OmniListItem(
                title = fact.key,
                subtitle = fact.value,
                trailing = {
                    IconButton(onClick = { viewModel.forgetFact(fact.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = removeLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                showDivider = true,
            )
        }
        if (facts.isNotEmpty()) {
            OmniListItem(
                title = stringResource(R.string.settings_memory_clear),
                subtitle = stringResource(R.string.settings_memory_clear_summary),
                onClick = viewModel::forgetAllFacts,
                danger = true,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun DataSection(
    onCreateBackup: () -> Unit,
    onRestore: () -> Unit,
    onDiagnostics: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    SettingsSection(stringResource(R.string.settings_section_data)) {
        OmniListItem(
            title = stringResource(R.string.create_backup),
            subtitle = stringResource(R.string.settings_backup_summary),
            leading = { OmniIconWell(icon = Icons.Outlined.Backup) },
            trailing = { NavChevron() },
            onClick = onCreateBackup,
            showDivider = true,
        )
        OmniListItem(
            title = stringResource(R.string.restore_backup),
            subtitle = stringResource(R.string.settings_restore_summary),
            leading = { OmniIconWell(icon = Icons.Outlined.Restore) },
            trailing = { NavChevron() },
            onClick = onRestore,
            showDivider = true,
        )
        OmniListItem(
            title = stringResource(R.string.export_diagnostics),
            subtitle = stringResource(R.string.settings_diagnostics_summary),
            leading = { OmniIconWell(icon = Icons.Outlined.Description) },
            trailing = { NavChevron() },
            onClick = onDiagnostics,
            showDivider = true,
        )
        OmniListItem(
            title = stringResource(R.string.delete_all_data),
            subtitle = stringResource(R.string.settings_delete_summary),
            leading = {
                OmniIconWell(
                    icon = Icons.Outlined.DeleteForever,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            trailing = { NavChevron() },
            onClick = onDeleteAll,
            showDivider = false,
            danger = true,
        )
    }
}

@Composable
private fun AboutSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    SettingsSection(stringResource(R.string.settings_section_about)) {
        OmniListItem(
            title = stringResource(R.string.app_version),
            subtitle = stringResource(R.string.settings_app_version_value, state.appVersion),
            showDivider = true,
        )
        InfoBlock(
            title = stringResource(R.string.settings_privacy_title),
            body = stringResource(R.string.settings_privacy_summary),
            showDivider = true,
        )
        InfoBlock(
            title = stringResource(R.string.settings_tools_title),
            body = stringResource(R.string.settings_tools_summary),
            showDivider = true,
        )
        OmniSwitchRow(
            title = stringResource(R.string.settings_local_development_title),
            checked = state.settings.localDevelopmentEndpoint,
            onCheckedChange = { checked ->
                if (BuildConfig.DEBUG) viewModel.setLocalDevelopment(checked)
            },
            subtitle = stringResource(
                if (BuildConfig.DEBUG) R.string.local_development_warning else R.string.settings_debug_only,
            ),
            enabled = BuildConfig.DEBUG,
            showDivider = false,
        )
    }
}

@Composable
private fun InfoBlock(
    title: String,
    body: String,
    showDivider: Boolean,
) {
    GroupRow(showDivider = showDivider) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String,
    showDivider: Boolean,
    placeholder: String = "",
    textDirection: TextDirection = TextDirection.Content,
) {
    GroupRow(showDivider = showDivider) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OmniTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = textDirection),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        )
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberSetting(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    supporting: String,
    onValue: (Int) -> Unit,
    showDivider: Boolean,
) {
    val decreaseLabel = stringResource(R.string.settings_decrease_named, title)
    val increaseLabel = stringResource(R.string.settings_increase_named, title)
    GroupRow(showDivider = showDivider) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onValue((value - step).coerceIn(range.first, range.last)) },
                enabled = value > range.first,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = decreaseLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { onValue((value + step).coerceIn(range.first, range.last)) },
                enabled = value < range.last,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = increaseLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    supporting: String?,
    startLabel: String?,
    endLabel: String?,
    showDivider: Boolean,
    steps: Int = 0,
) {
    GroupRow(showDivider = showDivider) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
        )
        if (startLabel != null && endLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = startLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = endLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SegmentedSetting(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    showDivider: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Three labels no longer fit comfortably once the system font grows or
        // the window is very narrow. A menu keeps every option readable instead
        // of ellipsizing the segmented control.
        val useMenu = fontScale > 1.2f || maxWidth < 250.dp
        if (useMenu) {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                OmniListItem(
                    title = label,
                    subtitle = options.getOrNull(selectedIndex).orEmpty(),
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = { expanded = true },
                    showDivider = showDivider,
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.widthIn(min = 220.dp),
                ) {
                    options.forEachIndexed { index, option ->
                        val selected = index == selectedIndex
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(index)
                            },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        } else {
            GroupRow(showDivider = showDivider) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OmniSegmented(
                    options = options,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    showDescription: String,
    hideDescription: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textDirection = TextDirection.Ltr,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OmniRadius.medium))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(OmniRadius.medium),
                    )
                    .padding(
                        start = OmniSpacing.md,
                        end = OmniSpacing.xs,
                        top = OmniSpacing.xs,
                        bottom = OmniSpacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) hideDescription else showDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun GroupRow(
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OmniSpacing.lg, vertical = OmniSpacing.md),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            content = content,
        )
        if (showDivider) Hairline(startPadding = OmniSpacing.lg)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
        SectionLabel(
            text = title,
            modifier = Modifier.semantics { heading() },
        )
        OmniGroup(content = content)
    }
}

@Composable
private fun NavChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun riskLabel(risk: ToolRisk): String = stringResource(
    when (risk) {
        ToolRisk.LOW -> R.string.settings_risk_low
        ToolRisk.MEDIUM -> R.string.settings_risk_medium
        ToolRisk.HIGH -> R.string.settings_risk_high
    },
)

private fun prettyToolName(name: String): String = name
    .replace('_', ' ')
    .replace('-', ' ')
    .trim()
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

@Composable
private fun PasswordDialog(
    title: String,
    description: String,
    password: String,
    onPassword: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val tooShort = password.isNotEmpty() && password.length < 10
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.md),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.backup_password),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SecretField(
                    value = password,
                    onValueChange = onPassword,
                    placeholder = stringResource(R.string.backup_password_hint),
                    visible = visible,
                    onToggleVisibility = { visible = !visible },
                    showDescription = stringResource(R.string.settings_show_password),
                    hideDescription = stringResource(R.string.settings_hide_password),
                )
                Text(
                    text = stringResource(
                        if (tooShort) R.string.password_required else R.string.backup_password_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooShort) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.length >= 10,
            ) {
                Text(stringResource(R.string.settings_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (danger) Icons.Outlined.Warning else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            ) {
                Text(body)
                if (danger) {
                    Text(
                        text = stringResource(R.string.settings_cannot_undo),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun BusyBanner(label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OmniRadius.medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OmniSpacing.lg, vertical = OmniSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = label,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
