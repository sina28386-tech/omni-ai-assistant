package com.omni.aiassistant.feature.devices

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniGroup
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniListItem
import com.omni.aiassistant.core.ui.OmniStatusTone
import com.omni.aiassistant.core.ui.OmniSwitchRow
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.DeviceInfoProvider
import com.omni.aiassistant.device.HermesBridge
import com.omni.aiassistant.device.PcStatus
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.device.capability.CapabilityStatus
import com.omni.aiassistant.domain.model.AppSettings
import com.omni.aiassistant.feature.providers.ProvidersViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DevicesHub(
    onOpenHermes: () -> Unit,
    onOpenProviders: () -> Unit,
    onFindFile: () -> Unit,
    onOpenBots: () -> Unit,
    onOpenWebWorkspace: () -> Unit,
    providersViewModel: ProvidersViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val capabilities = remember(context.applicationContext) {
        CapabilityCenter(context.applicationContext)
    }
    val settingsRepository: SettingsRepository = koinInject()
    val deviceInfo: DeviceInfoProvider = koinInject()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )
    val providerState by providersViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var statuses by remember { mutableStateOf(capabilities.statuses()) }
    var battery by remember { mutableStateOf(readBattery(context)) }
    var reportExpanded by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }

    var pcStatus by remember { mutableStateOf<PcStatus?>(null) }
    var pcError by remember { mutableStateOf<String?>(null) }
    var pcChecking by remember { mutableStateOf(false) }

    val normalizedUrl = remember(settings.hermesUrl) {
        HermesBridge.normalizeBaseUrl(settings.hermesUrl)
    }
    val hermesToken = settings.hermesToken.trim()
    val pcConfigured = normalizedUrl != null && hermesToken.isNotEmpty()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        statuses = capabilities.statuses()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        statuses = capabilities.statuses()
    }

    fun refreshStatuses() {
        statuses = capabilities.statuses()
        battery = readBattery(context)
    }

    fun requestCapability(capability: Capability) {
        val runtime = capabilities.runtimePermissions(capability).toTypedArray()
        val special = capabilities.settingsIntent(capability)
        when {
            // "All files" is a runtime permission before Android 11 and a
            // special settings screen from Android 11 onward.
            capability == Capability.STORAGE &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                runtime.isNotEmpty() -> permissionLauncher.launch(runtime)

            special != null -> runCatching { settingsLauncher.launch(special) }
            runtime.isNotEmpty() -> permissionLauncher.launch(runtime)
        }
    }

    fun refreshPc() {
        if (!pcConfigured || pcChecking) return
        val url = normalizedUrl ?: return
        pcChecking = true
        scope.launch {
            HermesBridge.check(url, hermesToken)
                .onSuccess {
                    pcStatus = it
                    pcError = null
                }
                .onFailure {
                    pcStatus = null
                    pcError = it.message?.take(240)?.ifBlank { null }
                }
            pcChecking = false
        }
    }

    LaunchedEffect(normalizedUrl, hermesToken) {
        pcStatus = null
        pcError = null
        refreshPc()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshStatuses()
        if (pcConfigured) refreshPc()
    }

    val statusMap = statuses.associateBy { it.capability }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.devices_page_title),
                subtitle = stringResource(R.string.devices_subtitle),
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.devices_this_device_section))
                OmniGroup {
                    DeviceHeroRow(battery)
                    Hairline()
                    OmniSwitchRow(
                        title = stringResource(R.string.devices_master_title),
                        checked = settings.fullPhoneControl,
                        onCheckedChange = { checked ->
                            scope.launch {
                                runCatching {
                                    settingsRepository.update {
                                        it.copy(fullPhoneControl = checked)
                                    }
                                }
                            }
                        },
                        subtitle = stringResource(R.string.devices_master_summary),
                        leading = {
                            OmniIconWell(
                                icon = Icons.Outlined.TouchApp,
                                tint = if (settings.fullPhoneControl) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        showDivider = false,
                    )
                }
            }
        }

        item {
            CapabilityGroup(
                label = stringResource(R.string.devices_group_communication),
                items = COMMUNICATION_CAPABILITIES,
                statuses = statusMap,
                fullPhoneControl = settings.fullPhoneControl,
                canGrant = { capability ->
                    capabilities.runtimePermissions(capability).isNotEmpty() ||
                        capabilities.settingsIntent(capability) != null
                },
                onGrant = { capability -> requestCapability(capability) },
            )
        }

        item {
            CapabilityGroup(
                label = stringResource(R.string.devices_group_content),
                items = CONTENT_CAPABILITIES,
                statuses = statusMap,
                fullPhoneControl = settings.fullPhoneControl,
                canGrant = { capability ->
                    capabilities.runtimePermissions(capability).isNotEmpty() ||
                        capabilities.settingsIntent(capability) != null
                },
                onGrant = { capability -> requestCapability(capability) },
            )
        }

        item {
            CapabilityGroup(
                label = stringResource(R.string.devices_group_system),
                items = SYSTEM_CAPABILITIES,
                statuses = statusMap,
                fullPhoneControl = settings.fullPhoneControl,
                canGrant = { capability ->
                    capabilities.runtimePermissions(capability).isNotEmpty() ||
                        capabilities.settingsIntent(capability) != null
                },
                onGrant = { capability -> requestCapability(capability) },
            )
        }

        item {
            PcBridgeSection(
                normalizedUrl = normalizedUrl,
                tokenSet = hermesToken.isNotEmpty(),
                configured = pcConfigured,
                checking = pcChecking,
                status = pcStatus,
                error = pcError,
                onCheck = { refreshPc() },
                onOpenHermes = onOpenHermes,
            )
        }

        item {
            val active = providerState.profiles.firstOrNull {
                it.id == providerState.activeProviderId
            }
            val ready = active?.enabled == true
            val aiSubtitle = when {
                active != null -> listOfNotNull(
                    active.name.takeIf { it.isNotBlank() },
                    active.modelId.takeIf { it.isNotBlank() },
                ).joinToString(" · ")

                providerState.profiles.isNotEmpty() ->
                    stringResource(R.string.devices_ai_no_active)

                else -> stringResource(R.string.devices_ai_empty)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.devices_ai_section))
                OmniGroup {
                    OmniListItem(
                        title = stringResource(R.string.nav_providers),
                        subtitle = aiSubtitle,
                        leading = { OmniIconWell(Icons.Outlined.Hub) },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusPill(
                                    text = stringResource(
                                        if (ready) R.string.devices_ai_ready
                                        else R.string.devices_ai_needs_setup,
                                    ),
                                    tone = if (ready) OmniStatusTone.SUCCESS
                                    else OmniStatusTone.WARNING,
                                )
                                TrailingArrow()
                            }
                        },
                        onClick = onOpenProviders,
                        showDivider = true,
                    )
                    OmniListItem(
                        title = stringResource(R.string.bots_workspace_title),
                        subtitle = stringResource(R.string.bots_workspace_entry_subtitle),
                        leading = { OmniIconWell(Icons.Outlined.Hub) },
                        trailing = { TrailingArrow() },
                        onClick = onOpenBots,
                        showDivider = true,
                    )
                    OmniListItem(
                        title = stringResource(R.string.web_workspace_title),
                        subtitle = stringResource(R.string.web_workspace_subtitle),
                        leading = { OmniIconWell(Icons.Outlined.Hub) },
                        trailing = { TrailingArrow() },
                        onClick = onOpenWebWorkspace,
                        showDivider = false,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.devices_on_phone_section))
                OmniGroup {
                    OmniListItem(
                        title = stringResource(R.string.devices_report_row),
                        subtitle = stringResource(R.string.devices_report_row_subtitle),
                        leading = { OmniIconWell(Icons.Outlined.Info) },
                        trailing = { TrailingArrow() },
                        onClick = {
                            if (!reportExpanded) report = deviceInfo.report()
                            reportExpanded = !reportExpanded
                        },
                    )
                    OmniListItem(
                        title = stringResource(R.string.devices_find_file_row),
                        subtitle = stringResource(R.string.devices_find_file_subtitle),
                        leading = { OmniIconWell(Icons.Outlined.Search) },
                        trailing = { TrailingArrow() },
                        onClick = onFindFile,
                        showDivider = reportExpanded,
                    )
                    if (reportExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = report,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                            OmniTextButton(
                                text = stringResource(R.string.close),
                                onClick = { reportExpanded = false },
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceHeroRow(battery: BatterySnapshot) {
    val model = Build.MODEL?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.unknown)
    val android = stringResource(R.string.devices_hero_android, Build.VERSION.RELEASE.orEmpty())
    val batteryText = when {
        battery.percent < 0 -> stringResource(R.string.devices_battery_unknown)
        battery.charging -> stringResource(R.string.devices_battery_charging, battery.percent)
        else -> stringResource(R.string.devices_battery, battery.percent)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIconWell(icon = Icons.Outlined.Smartphone)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = model,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.devices_hero_summary, android, batteryText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CapabilityGroup(
    label: String,
    items: List<CapabilityItem>,
    statuses: Map<Capability, CapabilityStatus>,
    fullPhoneControl: Boolean,
    canGrant: (Capability) -> Boolean,
    onGrant: (Capability) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        OmniGroup {
            items.forEachIndexed { index, item ->
                val status = statuses[item.capability]
                val granted = status?.granted == true
                val why = stringResource(item.why)
                val masterHint = stringResource(R.string.devices_master_off_hint)
                val needsMaster = !fullPhoneControl && item.capability in MASTER_GATED
                OmniListItem(
                    title = stringResource(item.title),
                    subtitle = if (needsMaster) "$why\n$masterHint" else why,
                    leading = { OmniIconWell(item.icon) },
                    trailing = {
                        if (granted) {
                            StatusPill(
                                text = stringResource(R.string.devices_status_on),
                                tone = OmniStatusTone.SUCCESS,
                            )
                        } else if (canGrant(item.capability)) {
                            OmniTextButton(
                                text = stringResource(R.string.devices_grant),
                                onClick = { onGrant(item.capability) },
                            )
                        } else {
                            StatusPill(
                                text = stringResource(R.string.devices_status_unavailable),
                                tone = OmniStatusTone.NEUTRAL,
                            )
                        }
                    },
                    showDivider = index != items.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun PcBridgeSection(
    normalizedUrl: String?,
    tokenSet: Boolean,
    configured: Boolean,
    checking: Boolean,
    status: PcStatus?,
    error: String?,
    onCheck: () -> Unit,
    onOpenHermes: () -> Unit,
) {
    val statusLabel = when {
        !configured -> stringResource(R.string.devices_status_not_configured)
        checking -> stringResource(R.string.devices_status_checking)
        status != null -> stringResource(R.string.devices_status_connected)
        error != null -> stringResource(R.string.devices_status_offline)
        else -> stringResource(R.string.devices_status_not_checked)
    }
    val statusTone = when {
        !configured -> OmniStatusTone.NEUTRAL
        checking -> OmniStatusTone.NEUTRAL
        status != null -> OmniStatusTone.SUCCESS
        error != null -> OmniStatusTone.ERROR
        else -> OmniStatusTone.NEUTRAL
    }
    val address = normalizedUrl ?: stringResource(R.string.devices_pc_address_missing)
    val auth = if (tokenSet) {
        stringResource(R.string.devices_pc_auth_ready)
    } else {
        stringResource(R.string.devices_pc_auth_missing)
    }
    val errorText = error?.let { stringResource(R.string.devices_pc_error, it) }
    val checkingText = stringResource(R.string.devices_pc_checking_desc)
    val settingsHint = stringResource(R.string.devices_pc_settings_hint)
    val subtitle = buildString {
        append(address)
        append(" · ")
        append(auth)
        when {
            checking -> append('\n').append(checkingText)
            errorText != null -> append('\n').append(errorText)
            !configured -> append('\n').append(settingsHint)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(
            text = stringResource(R.string.devices_pc_section),
            action = {
                OmniTextButton(
                    text = stringResource(R.string.devices_pc_check),
                    onClick = onCheck,
                    enabled = configured && !checking,
                )
            },
        )
        OmniGroup {
            OmniListItem(
                title = stringResource(R.string.devices_pc_open),
                subtitle = subtitle,
                leading = { OmniIconWell(Icons.Outlined.DesktopWindows) },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusPill(text = statusLabel, tone = statusTone)
                        TrailingArrow()
                    }
                },
                onClick = onOpenHermes,
                showDivider = false,
            )
        }
    }
}

@Composable
private fun TrailingArrow() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

private data class CapabilityItem(
    val capability: Capability,
    @StringRes val title: Int,
    @StringRes val why: Int,
    val icon: ImageVector,
)

private data class BatterySnapshot(
    val percent: Int,
    val charging: Boolean,
)

private val MASTER_GATED = setOf(
    Capability.ACCESSIBILITY,
    Capability.NOTIFICATION_ACCESS,
)

private val COMMUNICATION_CAPABILITIES = listOf(
    CapabilityItem(
        capability = Capability.PHONE,
        title = R.string.devices_cap_phone_title,
        why = R.string.devices_cap_phone_why,
        icon = Icons.Outlined.Phone,
    ),
    CapabilityItem(
        capability = Capability.SMS,
        title = R.string.devices_cap_sms_title,
        why = R.string.devices_cap_sms_why,
        icon = Icons.Outlined.Message,
    ),
    CapabilityItem(
        capability = Capability.CONTACTS,
        title = R.string.devices_cap_contacts_title,
        why = R.string.devices_cap_contacts_why,
        icon = Icons.Outlined.Contacts,
    ),
    CapabilityItem(
        capability = Capability.CALL_LOG,
        title = R.string.devices_cap_call_log_title,
        why = R.string.devices_cap_call_log_why,
        icon = Icons.Outlined.History,
    ),
)

private val CONTENT_CAPABILITIES = listOf(
    CapabilityItem(
        capability = Capability.MEDIA,
        title = R.string.devices_cap_media_title,
        why = R.string.devices_cap_media_why,
        icon = Icons.Outlined.PhotoLibrary,
    ),
    CapabilityItem(
        capability = Capability.STORAGE,
        title = R.string.devices_cap_storage_title,
        why = R.string.devices_cap_storage_why,
        icon = Icons.Outlined.Folder,
    ),
    CapabilityItem(
        capability = Capability.CAMERA,
        title = R.string.devices_cap_camera_title,
        why = R.string.devices_cap_camera_why,
        icon = Icons.Outlined.CameraAlt,
    ),
    CapabilityItem(
        capability = Capability.CALENDAR,
        title = R.string.devices_cap_calendar_title,
        why = R.string.devices_cap_calendar_why,
        icon = Icons.Outlined.CalendarMonth,
    ),
)

private val SYSTEM_CAPABILITIES = listOf(
    CapabilityItem(
        capability = Capability.LOCATION,
        title = R.string.devices_cap_location_title,
        why = R.string.devices_cap_location_why,
        icon = Icons.Outlined.LocationOn,
    ),
    CapabilityItem(
        capability = Capability.WRITE_SETTINGS,
        title = R.string.devices_cap_write_settings_title,
        why = R.string.devices_cap_write_settings_why,
        icon = Icons.Outlined.Settings,
    ),
    CapabilityItem(
        capability = Capability.DND,
        title = R.string.devices_cap_dnd_title,
        why = R.string.devices_cap_dnd_why,
        icon = Icons.Outlined.DoNotDisturbOn,
    ),
    CapabilityItem(
        capability = Capability.ACCESSIBILITY,
        title = R.string.devices_cap_accessibility_title,
        why = R.string.devices_cap_accessibility_why,
        icon = Icons.Outlined.TouchApp,
    ),
    CapabilityItem(
        capability = Capability.NOTIFICATION_ACCESS,
        title = R.string.devices_cap_notification_access_title,
        why = R.string.devices_cap_notification_access_why,
        icon = Icons.Outlined.NotificationsActive,
    ),
    CapabilityItem(
        capability = Capability.NOTIFICATIONS,
        title = R.string.devices_cap_notifications_title,
        why = R.string.devices_cap_notifications_why,
        icon = Icons.Outlined.Notifications,
    ),
)

private fun readBattery(context: Context): BatterySnapshot {
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) {
        (level * 100 / scale).coerceIn(0, 100)
    } else {
        -1
    }
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0
    return BatterySnapshot(percent, charging)
}
