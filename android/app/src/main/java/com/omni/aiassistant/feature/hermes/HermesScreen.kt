package com.omni.aiassistant.feature.hermes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.ui.OmniGroup
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniListItem
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniStatusTone
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.HermesBridge
import com.omni.aiassistant.device.PcStatus
import com.omni.aiassistant.domain.model.AppSettings
import com.omni.aiassistant.domain.model.CommandTarget
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * PC console. Every value shown here comes from the saved settings or from a
 * real bridge response; the token itself is never rendered or logged. There is
 * no free-form command box: only the actions in [PcAction] can be sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesScreen(onOpenSettings: () -> Unit = {}) {
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )
    val scope = rememberCoroutineScope()

    val normalizedUrl = remember(settings.hermesUrl) {
        HermesBridge.normalizeBaseUrl(settings.hermesUrl)
    }
    val token = settings.hermesToken.trim()
    val configured = normalizedUrl != null && token.isNotEmpty()
    val phoneOnly = settings.commandTarget == CommandTarget.PHONE

    var pcStatus by remember { mutableStateOf<PcStatus?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<PcAction?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PcAction?>(null) }

    val setupRequired = stringResource(R.string.hermes_action_needs_setup)
    val unknownError = stringResource(R.string.hermes_error_unknown)
    val runningLabel = stringResource(R.string.hermes_executing)
    val clearLabel = stringResource(R.string.clear)

    fun redact(text: String): String =
        if (token.isEmpty()) text else text.replace(token, "***")

    fun testConnection() {
        val url = normalizedUrl
        if (url == null || token.isEmpty() || checking) return
        checking = true
        checkError = null
        scope.launch {
            HermesBridge.check(url, token)
                .onSuccess {
                    pcStatus = it
                    checkError = null
                }
                .onFailure {
                    pcStatus = null
                    checkError = redact(
                        it.message?.take(240).orEmpty().ifBlank { unknownError },
                    )
                }
            checking = false
        }
    }

    fun runAction(action: PcAction) {
        if (running != null) return
        val url = normalizedUrl
        if (url == null || token.isEmpty()) {
            actionError = setupRequired
            return
        }
        running = action
        result = null
        actionError = null
        scope.launch {
            HermesBridge.execute(
                baseUrl = url,
                action = action.id,
                payload = action.payload,
                token = token,
            )
                .onSuccess { result = redact(it).take(8_000) }
                .onFailure {
                    actionError = redact(
                        it.message?.take(400).orEmpty().ifBlank { unknownError },
                    )
                }
            running = null
        }
    }

    fun requestAction(action: PcAction) {
        if (running != null) return
        if (!configured) {
            actionError = setupRequired
            return
        }
        if (action.requiresConfirmation) {
            pendingAction = action
        } else {
            runAction(action)
        }
    }

    LaunchedEffect(normalizedUrl, token, settings.commandTarget) {
        pcStatus = null
        checkError = null
        if (configured && !phoneOnly) testConnection()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (configured && !phoneOnly) testConnection()
    }

    val connected = pcStatus
    val connectionError = checkError
    val statusLabel = when {
        !configured -> stringResource(R.string.devices_status_not_configured)
        checking -> stringResource(R.string.hermes_testing)
        connected != null -> stringResource(R.string.devices_status_connected)
        connectionError != null -> stringResource(R.string.hermes_status_failed)
        phoneOnly -> stringResource(R.string.hermes_status_not_checked)
        else -> stringResource(R.string.hermes_status_ready)
    }
    val statusTone = when {
        !configured -> OmniStatusTone.NEUTRAL
        checking -> OmniStatusTone.NEUTRAL
        connected != null -> OmniStatusTone.SUCCESS
        connectionError != null -> OmniStatusTone.ERROR
        else -> OmniStatusTone.NEUTRAL
    }
    val statusTitle = when {
        !configured -> stringResource(R.string.hermes_status_not_configured)
        checking -> stringResource(R.string.hermes_checking)
        connected != null -> stringResource(R.string.hermes_pc_connected)
        connectionError != null -> stringResource(R.string.hermes_pc_disconnected)
        phoneOnly -> stringResource(R.string.hermes_status_paused)
        else -> stringResource(R.string.hermes_status_ready)
    }
    val statusBody = when {
        !configured -> stringResource(R.string.hermes_status_not_configured_body)
        checking -> stringResource(R.string.hermes_testing)
        connectionError != null -> stringResource(R.string.hermes_execute_failed, connectionError)
        connected != null -> {
            val systemLine = stringResource(
                R.string.hermes_pc_system_line,
                connected.system,
                connected.node,
            )
            val files = pluralStringResource(
                R.plurals.hermes_desktop_file_count,
                connected.desktopFiles,
                connected.desktopFiles,
            )
            "$systemLine · $files"
        }
        phoneOnly -> stringResource(R.string.hermes_phone_mode)
        else -> stringResource(R.string.hermes_status_ready_body)
    }
    val statusBodyColor = if (connectionError != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val urlLabel = normalizedUrl ?: stringResource(
        if (settings.hermesUrl.isBlank()) R.string.hermes_config_not_set
        else R.string.hermes_config_invalid,
    )
    val testLabel = when {
        checking -> stringResource(R.string.hermes_testing)
        connected != null -> stringResource(R.string.hermes_test_again)
        else -> stringResource(R.string.hermes_test)
    }
    val panelBody = actionError ?: result
    val panelIsError = actionError != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScreenHeader(
                title = stringResource(R.string.hermes_title),
                subtitle = stringResource(R.string.hermes_subtitle),
            )
            Text(
                text = stringResource(R.string.hermes_trusted_network_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.hermes_connection_section))
            OmniGroup {
                OmniListItem(
                    title = stringResource(R.string.hermes_config_url),
                    subtitle = urlLabel,
                    leading = { OmniIconWell(Icons.Outlined.Link) },
                    showDivider = true,
                )
                OmniListItem(
                    title = stringResource(R.string.hermes_config_token),
                    subtitle = stringResource(
                        if (token.isNotEmpty()) R.string.hermes_config_token_set
                        else R.string.hermes_config_token_missing,
                    ),
                    leading = { OmniIconWell(Icons.Outlined.Lock) },
                    showDivider = !configured,
                )
                if (!configured) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.hermes_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.hermes_setup_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OmniSecondaryButton(
                            text = stringResource(R.string.hermes_setup_open_settings),
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.hermes_token_never_shown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.hermes_status_section))
            OmniGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OmniIconWell(
                        icon = Icons.Outlined.DesktopWindows,
                        tint = if (connected != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = statusBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusBodyColor,
                        )
                    }
                    StatusPill(text = statusLabel, tone = statusTone)
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    OmniSecondaryButton(
                        text = testLabel,
                        onClick = { testConnection() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = configured && !checking,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.hermes_actions_section))
            OmniGroup {
                PcAction.entries.forEachIndexed { index, action ->
                    OmniListItem(
                        title = stringResource(action.title),
                        subtitle = stringResource(action.subtitle),
                        leading = { OmniIconWell(action.icon) },
                        trailing = {
                            if (running == action) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .semantics { contentDescription = runningLabel },
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        onClick = { requestAction(action) },
                        showDivider = index != PcAction.entries.lastIndex,
                    )
                }
            }
            Text(
                text = stringResource(R.string.hermes_risk_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (panelBody != null) {
            SectionLabel(
                text = stringResource(
                    if (panelIsError) R.string.hermes_action_failed
                    else R.string.hermes_result_title,
                ),
                action = {
                    OmniTextButton(
                        text = clearLabel,
                        onClick = {
                            result = null
                            actionError = null
                        },
                    )
                },
            )
            OmniGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = panelBody,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = if (panelIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (panelIsError) {
                        Text(
                            text = stringResource(R.string.hermes_connection_failed_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val confirmation = pendingAction
    if (confirmation != null) {
        ModalBottomSheet(onDismissRequest = { pendingAction = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.hermes_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusPill(
                    text = stringResource(R.string.hermes_risk_high),
                    tone = OmniStatusTone.ERROR,
                )
                Text(
                    text = stringResource(
                        confirmation.confirmation ?: R.string.hermes_confirm_generic,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OmniPrimaryButton(
                    text = stringResource(R.string.hermes_run_on_pc),
                    onClick = {
                        pendingAction = null
                        runAction(confirmation)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OmniTextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { pendingAction = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private enum class PcAction(
    val id: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val icon: ImageVector,
    val payload: String = "",
    val requiresConfirmation: Boolean = false,
    @StringRes val confirmation: Int? = null,
) {
    SYS_INFO(
        id = "sys_info",
        title = R.string.hermes_sys_info,
        subtitle = R.string.hermes_action_sys_info_sub,
        icon = Icons.Outlined.Info,
    ),
    DESKTOP_FILES(
        id = "desktop_files",
        title = R.string.hermes_desktop_files,
        subtitle = R.string.hermes_action_desktop_files_sub,
        icon = Icons.Outlined.FolderOpen,
    ),
    OPEN_APP(
        id = "open_app",
        title = R.string.hermes_action_open_notepad,
        subtitle = R.string.hermes_action_open_notepad_sub,
        icon = Icons.Outlined.Apps,
        payload = "notepad",
        requiresConfirmation = true,
        confirmation = R.string.hermes_confirm_open_app,
    ),
    SCREENSHOT(
        id = "screenshot",
        title = R.string.hermes_action_screenshot,
        subtitle = R.string.hermes_action_screenshot_sub,
        icon = Icons.Outlined.PhotoCamera,
        requiresConfirmation = true,
        confirmation = R.string.hermes_confirm_screenshot,
    ),
    LIST_PROCESSES(
        id = "list_processes",
        title = R.string.hermes_action_processes,
        subtitle = R.string.hermes_action_processes_sub,
        icon = Icons.Outlined.Memory,
    ),
    DISK_INFO(
        id = "disk_info",
        title = R.string.hermes_action_disk,
        subtitle = R.string.hermes_action_disk_sub,
        icon = Icons.Outlined.Storage,
    ),
}
