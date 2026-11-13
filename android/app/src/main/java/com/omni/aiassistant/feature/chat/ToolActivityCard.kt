package com.omni.aiassistant.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omni.aiassistant.R
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniStatusTone
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusDot
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.device.tools.ToolConfirmation
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val ToolLabelResources: Map<String, Int> = mapOf(
    "apps_list" to R.string.chat_tool_apps_list,
    "app_open" to R.string.chat_tool_app_open,
    "app_info" to R.string.chat_tool_app_info,
    "app_uninstall" to R.string.chat_tool_app_uninstall,
    "app_settings" to R.string.chat_tool_app_settings,
    "call_place" to R.string.chat_tool_call_place,
    "call_log" to R.string.chat_tool_call_log,
    "sms_send" to R.string.chat_tool_sms_send,
    "sms_read" to R.string.chat_tool_sms_read,
    "sms_search" to R.string.chat_tool_sms_search,
    "contacts_search" to R.string.chat_tool_contacts_search,
    "contact_create" to R.string.chat_tool_contact_create,
    "file_open" to R.string.chat_tool_file_open,
    "file_share" to R.string.chat_tool_file_share,
    "file_delete" to R.string.chat_tool_file_delete,
    "file_move" to R.string.chat_tool_file_move,
    "file_copy" to R.string.chat_tool_file_copy,
    "file_rename" to R.string.chat_tool_file_rename,
    "folder_create" to R.string.chat_tool_folder_create,
    "file_read_text" to R.string.chat_tool_file_read_text,
    "file_info" to R.string.chat_tool_file_info,
    "storage_info" to R.string.chat_tool_storage_info,
    "files_search" to R.string.chat_tool_files_search,
    "files_recent" to R.string.chat_tool_files_recent,
    "volume_set" to R.string.chat_tool_volume_set,
    "ringer_set" to R.string.chat_tool_ringer_set,
    "media_control" to R.string.chat_tool_media_control,
    "tts_speak" to R.string.chat_tool_tts_speak,
    "alarm_set" to R.string.chat_tool_alarm_set,
    "timer_set" to R.string.chat_tool_timer_set,
    "calendar_events" to R.string.chat_tool_calendar_events,
    "calendar_create" to R.string.chat_tool_calendar_create,
    "dnd_set" to R.string.chat_tool_dnd_set,
    "location_get" to R.string.chat_tool_location_get,
    "clipboard_get" to R.string.chat_tool_clipboard_get,
    "clipboard_set" to R.string.chat_tool_clipboard_set,
    "wifi_panel" to R.string.chat_tool_wifi_panel,
    "bluetooth_panel" to R.string.chat_tool_bluetooth_panel,
    "screen_read" to R.string.chat_tool_screen_read,
    "ui_tap" to R.string.chat_tool_ui_tap,
    "ui_type" to R.string.chat_tool_ui_type,
    "ui_scroll" to R.string.chat_tool_ui_scroll,
    "ui_global" to R.string.chat_tool_ui_global,
    "screenshot" to R.string.chat_tool_screenshot,
    "notifications_read" to R.string.chat_tool_notifications_read,
    "notifications_dismiss" to R.string.chat_tool_notifications_dismiss,
    "notifications_reply" to R.string.chat_tool_notifications_reply,
    "device_status" to R.string.chat_tool_device_status,
    "brightness_set" to R.string.chat_tool_brightness_set,
    "rotation_set" to R.string.chat_tool_rotation_set,
    "flashlight" to R.string.chat_tool_flashlight,
    "share_text" to R.string.chat_tool_share_text,
    "open_url" to R.string.chat_tool_open_url,
    "web_search" to R.string.chat_tool_web_search,
    "maps_search" to R.string.chat_tool_maps_search,
    "email_compose" to R.string.chat_tool_email_compose,
    "media_search" to R.string.chat_tool_media_search,
    "media_play" to R.string.chat_tool_media_play,
    "media_stop" to R.string.chat_tool_media_stop,
)

private val ArgumentLabelResources: Map<String, Int> = mapOf(
    "action" to R.string.chat_arg_action,
    "app" to R.string.chat_arg_app,
    "body" to R.string.chat_arg_body,
    "contact_name" to R.string.chat_arg_contact_name,
    "direction" to R.string.chat_arg_direction,
    "email" to R.string.chat_arg_email,
    "end_iso" to R.string.chat_arg_end_iso,
    "filter" to R.string.chat_arg_filter,
    "from" to R.string.chat_arg_from,
    "hour" to R.string.chat_arg_hour,
    "key" to R.string.chat_arg_key,
    "kind" to R.string.chat_arg_kind,
    "label" to R.string.chat_arg_label,
    "language" to R.string.chat_arg_language,
    "limit" to R.string.chat_arg_limit,
    "location" to R.string.chat_arg_location,
    "max_chars" to R.string.chat_arg_max_chars,
    "minute" to R.string.chat_arg_minute,
    "mode" to R.string.chat_arg_mode,
    "name" to R.string.chat_arg_name,
    "new_name" to R.string.chat_arg_new_name,
    "notes" to R.string.chat_arg_notes,
    "number" to R.string.chat_arg_number,
    "on" to R.string.chat_arg_on,
    "path" to R.string.chat_arg_path,
    "percent" to R.string.chat_arg_percent,
    "phone" to R.string.chat_arg_phone,
    "precise" to R.string.chat_arg_precise,
    "query" to R.string.chat_arg_query,
    "range" to R.string.chat_arg_range,
    "seconds" to R.string.chat_arg_seconds,
    "start_iso" to R.string.chat_arg_start_iso,
    "stream" to R.string.chat_arg_stream,
    "subject" to R.string.chat_arg_subject,
    "text" to R.string.chat_arg_text,
    "title" to R.string.chat_arg_title,
    "to" to R.string.chat_arg_to,
    "url" to R.string.chat_arg_url,
    "x" to R.string.chat_arg_x,
    "y" to R.string.chat_arg_y,
)

private val HighRiskTools = setOf(
    "ui_tap",
    "ui_type",
    "ui_scroll",
    "ui_global",
    "screenshot",
    "notifications_reply",
    "sms_send",
    "call_place",
    "app_uninstall",
    "file_delete",
)

private val MediumRiskTools = setOf(
    "notifications_dismiss",
    "contact_create",
    "file_open",
    "file_move",
    "file_copy",
    "file_rename",
    "ringer_set",
    "calendar_create",
    "brightness_set",
    "rotation_set",
    "dnd_set",
)

private enum class ToolRunStatus {
    SUCCESS,
    FAILED,
    DENIED,
    PERMISSION,
    DISABLED,
    UNKNOWN,
}

private data class ToolResultSnapshot(
    val status: ToolRunStatus,
    val summary: String?,
    val detail: String?,
    val permission: String?,
)

@Composable
internal fun toolDisplayName(name: String?): String {
    if (name.isNullOrBlank()) return stringResource(R.string.chat_tool_unknown)
    val res = ToolLabelResources[name]
    return if (res != null) stringResource(res) else humanize(name)
}

private fun humanize(value: String): String =
    value.replace('_', ' ').trim().replaceFirstChar { it.uppercase(Locale.getDefault()) }

private fun toolIcon(name: String?): ImageVector = when (name) {
    null -> Icons.Outlined.Build
    "call_place", "call_log" -> Icons.Outlined.Call
    "sms_send", "sms_read", "sms_search" -> Icons.Outlined.Sms
    "contacts_search", "contact_create" -> Icons.Outlined.Person
    "apps_list", "app_open", "app_info", "app_uninstall", "app_settings" -> Icons.Outlined.Apps
    "file_open", "file_share", "file_delete", "file_move", "file_copy", "file_rename",
    "folder_create", "file_read_text", "file_info", "storage_info", "files_search",
    "files_recent" -> Icons.Outlined.Folder
    "screen_read", "ui_tap", "ui_type", "ui_scroll" -> Icons.Outlined.TouchApp
    "ui_global" -> Icons.Outlined.Settings
    "screenshot" -> Icons.Outlined.PhotoCamera
    "notifications_read", "notifications_dismiss", "notifications_reply" -> Icons.Outlined.Notifications
    "volume_set", "ringer_set", "media_control", "tts_speak" -> Icons.Outlined.VolumeUp
    "alarm_set", "timer_set" -> Icons.Outlined.Alarm
    "calendar_events", "calendar_create" -> Icons.Outlined.CalendarMonth
    "dnd_set" -> Icons.Outlined.DoNotDisturbOn
    "location_get" -> Icons.Outlined.LocationOn
    "clipboard_get", "clipboard_set" -> Icons.Outlined.ContentPaste
    "wifi_panel" -> Icons.Outlined.Wifi
    "bluetooth_panel" -> Icons.Outlined.Bluetooth
    "brightness_set" -> Icons.Outlined.BrightnessMedium
    "rotation_set" -> Icons.Outlined.StayCurrentPortrait
    "flashlight" -> Icons.Outlined.FlashlightOn
    "share_text" -> Icons.Outlined.Share
    "open_url" -> Icons.Outlined.OpenInNew
    "web_search" -> Icons.Outlined.Public
    "maps_search" -> Icons.Outlined.Map
    "email_compose" -> Icons.Outlined.Email
    "device_status" -> Icons.Outlined.Smartphone
    else -> Icons.Outlined.Build
}

private fun toolRisk(name: String?): ToolRisk? = when {
    name == null -> null
    name in HighRiskTools -> ToolRisk.HIGH
    name in MediumRiskTools -> ToolRisk.MEDIUM
    name in ToolLabelResources -> ToolRisk.LOW
    else -> null
}

private fun ToolRisk.labelRes(): Int = when (this) {
    ToolRisk.HIGH -> R.string.chat_risk_high
    ToolRisk.MEDIUM -> R.string.chat_risk_medium
    ToolRisk.LOW -> R.string.chat_risk_low
}

private fun ToolRisk.confirmationTone(): OmniStatusTone = when (this) {
    ToolRisk.HIGH -> OmniStatusTone.ERROR
    ToolRisk.MEDIUM -> OmniStatusTone.WARNING
    ToolRisk.LOW -> OmniStatusTone.NEUTRAL
}

private fun ToolRunStatus.labelRes(): Int = when (this) {
    ToolRunStatus.SUCCESS -> R.string.chat_tool_status_done
    ToolRunStatus.FAILED -> R.string.chat_tool_status_failed
    ToolRunStatus.DENIED -> R.string.chat_tool_status_denied
    ToolRunStatus.PERMISSION -> R.string.chat_tool_status_permission
    ToolRunStatus.DISABLED -> R.string.chat_tool_status_disabled
    ToolRunStatus.UNKNOWN -> R.string.chat_tool_status_finished
}

private fun ToolRunStatus.tone(): OmniStatusTone = when (this) {
    ToolRunStatus.SUCCESS -> OmniStatusTone.SUCCESS
    ToolRunStatus.FAILED -> OmniStatusTone.ERROR
    ToolRunStatus.PERMISSION -> OmniStatusTone.WARNING
    ToolRunStatus.DENIED, ToolRunStatus.DISABLED, ToolRunStatus.UNKNOWN -> OmniStatusTone.NEUTRAL
}

private fun parseToolPayload(raw: String): ToolResultSnapshot {
    val text = raw.trim()
    if (text.isEmpty()) {
        return ToolResultSnapshot(ToolRunStatus.UNKNOWN, null, null, null)
    }
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
        .getOrNull()
    if (obj == null) {
        return ToolResultSnapshot(
            status = ToolRunStatus.UNKNOWN,
            summary = text.lineSequence().firstOrNull()?.take(180),
            detail = text.take(1200),
            permission = null,
        )
    }
    fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
    fun bool(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.booleanOrNull
    val summary = string("summary")
    val error = string("error")
    val permission = string("permission_required")
    val denied = bool("denied") == true
    val disabled = bool("disabled") == true
    val ok = bool("ok")
    val status = when {
        denied -> ToolRunStatus.DENIED
        permission != null -> ToolRunStatus.PERMISSION
        disabled -> ToolRunStatus.DISABLED
        ok == true -> ToolRunStatus.SUCCESS
        ok == false -> ToolRunStatus.FAILED
        else -> ToolRunStatus.UNKNOWN
    }
    val detail = when (status) {
        ToolRunStatus.SUCCESS -> summary
        ToolRunStatus.FAILED -> error ?: summary
        ToolRunStatus.PERMISSION -> error ?: summary
        ToolRunStatus.DISABLED -> error ?: summary
        ToolRunStatus.DENIED -> error ?: summary
        ToolRunStatus.UNKNOWN -> summary ?: error
    }?.take(1200)
    return ToolResultSnapshot(status, summary?.take(180), detail, permission)
}

@Composable
internal fun ToolActivityCard(
    messageId: String,
    toolName: String?,
    payload: String,
    arguments: JsonObject,
    modifier: Modifier = Modifier,
) {
    val result = remember(payload) { parseToolPayload(payload) }
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val label = toolDisplayName(toolName)
    val risk = toolRisk(toolName)
    val statusLabel = stringResource(result.status.labelRes())
    val rawSummary = result.summary
        ?: result.detail?.lineSequence()?.firstOrNull()?.take(180)
        ?: statusLabel
    val summary = localizedToolSummary(payload, rawSummary)
    val stateText = stringResource(
        if (expanded) R.string.chat_tool_collapse else R.string.chat_tool_expand,
    )
    val statusColor = if (result.status == ToolRunStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = stateText },
        shape = RoundedCornerShape(OmniRadius.medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = toolIcon(toolName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(OmniSpacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(tone = result.status.tone())
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(OmniSpacing.xxs))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 26.dp, top = OmniSpacing.xxs),
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 26.dp, top = OmniSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
                ) {
                    if (risk != null) {
                        Text(
                            text = stringResource(
                                R.string.chat_tool_risk_label,
                                stringResource(risk.labelRes()),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result.permission?.let { permission ->
                        Text(
                            text = stringResource(
                                R.string.chat_tool_permission_required,
                                capabilityLabel(permission),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    val detail = result.detail
                    if (detail != null && detail != summary) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (arguments.isNotEmpty()) {
                        Spacer(Modifier.height(OmniSpacing.xxs))
                        Text(
                            text = stringResource(R.string.chat_tool_arguments),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ToolArgumentList(
                            arguments = arguments,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            maxValueLines = 4,
                        )
                    }
                    if (
                        risk == null &&
                        result.permission == null &&
                        (detail == null || detail == summary) &&
                        arguments.isEmpty()
                    ) {
                        Text(
                            text = stringResource(R.string.chat_tool_no_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolArgumentList(
    arguments: JsonObject,
    modifier: Modifier = Modifier,
    maxValueLines: Int = 4,
) {
    val entries = arguments.entries.filter { it.value !== JsonNull }
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_confirm_no_arguments),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
        entries.forEachIndexed { index, entry ->
            Column {
                Text(
                    text = argumentLabel(entry.key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(OmniSpacing.xxs))
                Text(
                    text = entry.value.displayValue(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = maxValueLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index != entries.lastIndex) Hairline()
        }
    }
}

@Composable
private fun argumentLabel(key: String): String {
    val res = ArgumentLabelResources[key]
    return if (res != null) stringResource(res) else humanize(key)
}

private fun JsonElement.displayValue(): String = when (this) {
    JsonNull -> "—"
    is JsonPrimitive -> content
    is JsonArray -> joinToString(", ") { it.displayValue() }
    is JsonObject -> toString()
}

@Composable
private fun capabilityLabel(permission: String): String {
    val normalized = permission.substringAfterLast('.').uppercase(Locale.ROOT)
    val res = when {
        "SMS" in normalized -> R.string.chat_cap_sms
        "CALL" in normalized || "PHONE" in normalized -> R.string.chat_cap_call
        "CONTACT" in normalized -> R.string.chat_cap_contacts
        "NOTIFICATION" in normalized -> R.string.chat_cap_notifications
        "ACCESSIBILITY" in normalized -> R.string.chat_cap_accessibility
        "LOCATION" in normalized -> R.string.chat_cap_location
        "MICROPHONE" in normalized || "RECORD_AUDIO" in normalized -> R.string.chat_cap_microphone
        "CAMERA" in normalized -> R.string.chat_cap_camera
        "STORAGE" in normalized || "MEDIA" in normalized -> R.string.chat_cap_storage
        else -> null
    }
    return if (res != null) stringResource(res) else humanize(permission.substringAfterLast('.'))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolConfirmationSheet(
    request: ToolConfirmation,
    onDecision: (Boolean, Boolean) -> Unit,
) {
    var decided by remember(request.call.id) { mutableStateOf(false) }
    val decide: (Boolean, Boolean) -> Unit = { allow, always ->
        if (!decided) {
            decided = true
            onDecision(allow, always)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val risk = request.spec.risk
    val label = toolDisplayName(request.spec.name)
    val noteRes = when (risk) {
        ToolRisk.HIGH -> R.string.chat_confirm_high_note
        ToolRisk.MEDIUM -> R.string.chat_confirm_medium_note
        ToolRisk.LOW -> R.string.chat_confirm_low_note
    }
    val capabilityRes = when (request.spec.category) {
        ToolCategory.SCREEN -> R.string.chat_confirm_capability_screen
        ToolCategory.COMMUNICATION -> R.string.chat_confirm_capability_comm
        ToolCategory.FILES -> R.string.chat_confirm_capability_files
        ToolCategory.APPS -> R.string.chat_confirm_capability_apps
        ToolCategory.DEVICE, ToolCategory.SYSTEM, ToolCategory.MEDIA -> R.string.chat_confirm_capability_device
        ToolCategory.WEB, ToolCategory.PC -> R.string.chat_confirm_capability_default
    }

    ModalBottomSheet(
        onDismissRequest = { decide(false, false) },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = OmniRadius.xl, topEnd = OmniRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = OmniSpacing.md, bottom = OmniSpacing.xs)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OmniSpacing.xl)
                .padding(bottom = OmniSpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                OmniIconWell(
                    icon = toolIcon(request.spec.name),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(OmniSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_confirm_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(OmniSpacing.xxs))
                    Text(
                        text = stringResource(R.string.chat_confirm_body, label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(OmniSpacing.md))
            StatusPill(
                text = stringResource(risk.labelRes()),
                tone = risk.confirmationTone(),
            )
            Spacer(Modifier.height(OmniSpacing.sm))
            Text(
                text = stringResource(noteRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(OmniSpacing.sm))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(OmniSpacing.sm))
                Text(
                    text = stringResource(capabilityRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(OmniSpacing.lg))
            SectionLabel(text = stringResource(R.string.chat_confirm_arguments))
            Spacer(Modifier.height(OmniSpacing.sm))
            if (request.call.arguments.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_confirm_no_arguments),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ToolArgumentList(
                        arguments = request.call.arguments,
                        maxValueLines = Int.MAX_VALUE,
                    )
                }
            }
            Spacer(Modifier.height(OmniSpacing.lg))
            Text(
                text = stringResource(R.string.chat_confirm_always_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(OmniSpacing.sm))
            OmniTextButton(
                text = stringResource(R.string.chat_confirm_deny),
                onClick = { decide(false, false) },
                modifier = Modifier.fillMaxWidth(),
            )
            OmniSecondaryButton(
                text = stringResource(R.string.chat_confirm_once),
                onClick = { decide(true, false) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_confirm_always),
                onClick = { decide(true, true) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
