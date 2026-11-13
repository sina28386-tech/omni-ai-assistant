package com.omni.aiassistant.feature.home

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.feature.conversations.ConversationsViewModel
import com.omni.aiassistant.feature.providers.ProvidersViewModel
import com.omni.aiassistant.feature.settings.SettingsViewModel
import java.util.Calendar
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private data class HomeStarter(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val prompt: Int,
    val icon: ImageVector,
)

private val HomeStarters = listOf(
    HomeStarter(R.string.home_refinement_files_title, R.string.home_refinement_files_body,
        R.string.home_prompt_files, Icons.Outlined.FolderOpen),
    HomeStarter(R.string.home_refinement_apps_title, R.string.home_refinement_apps_body,
        R.string.home_prompt_apps, Icons.Outlined.Apps),
    HomeStarter(R.string.home_refinement_alarm_title, R.string.home_refinement_alarm_body,
        R.string.home_prompt_alarm, Icons.Outlined.Alarm),
)

@Composable
fun HomeDashboard(
    onNewChat: () -> Unit,
    onStartPrompt: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenAllChats: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenBots: () -> Unit = onOpenDevices,
    onSubmitPrompt: (String) -> Unit = onStartPrompt,
    settingsViewModel: SettingsViewModel = koinViewModel(),
    providersViewModel: ProvidersViewModel = koinViewModel(),
    conversationsViewModel: ConversationsViewModel = koinViewModel(),
) {
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val providersState by providersViewModel.state.collectAsStateWithLifecycle()
    val conversationsState by conversationsViewModel.state.collectAsStateWithLifecycle()
    val capabilityCenter: CapabilityCenter = koinInject()
    var capabilityEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) capabilityEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityGranted = remember(capabilityEpoch) {
        capabilityCenter.isGranted(Capability.ACCESSIBILITY)
    }
    // Selection is a configuration fact. It does not establish network reachability.
    val selectedProvider = providersState.profiles.firstOrNull {
        it.id == providersState.activeProviderId && it.enabled && it.modelId.isNotBlank()
    }
    val providerTitle = selectedProvider?.let { it.name.ifBlank { it.modelId } }
        ?: stringResource(R.string.home_refinement_choose_model)
    val accessDescription = when {
        !settingsState.settings.agentMode -> R.string.home_refinement_access_agent_off
        !settingsState.settings.fullPhoneControl -> R.string.home_refinement_access_control_off
        !accessibilityGranted -> R.string.home_refinement_access_permission_needed
        else -> R.string.home_refinement_access_enabled
    }
    val recent = remember(conversationsState.conversations) {
        conversationsState.conversations.filterNot { it.archived }
            .sortedByDescending { it.updatedAt }.take(3)
    }
    var draft by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val submitDraft: () -> Unit = {
        val prompt = draft.trim()
        if (prompt.isNotEmpty()) {
            onSubmitPrompt(prompt)
            draft = ""
            focusManager.clearFocus()
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars).imePadding(),
            contentPadding = PaddingValues(
                horizontal = OmniSpacing.screenHorizontal,
                vertical = OmniSpacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "hero") {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    HomeHeader(providerTitle, selectedProvider != null, onOpenProviders)
                    Spacer(Modifier.height(OmniSpacing.xxxl))
                    HomeHeading()
                    Spacer(Modifier.height(OmniSpacing.xl))
                    HomeComposer(draft, { draft = it }, submitDraft, onOpenVoice)
                    TextButton(onClick = onOpenBots, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.bots_workspace_title))
                    }
                }
            }
            item(key = "starters") {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    HomeSectionTitle(stringResource(R.string.home_section_device))
                    Spacer(Modifier.height(OmniSpacing.sm))
                    HomeStarters.forEach { starter ->
                        val prompt = stringResource(starter.prompt)
                        HomeActionRow(
                            title = stringResource(starter.title),
                            description = stringResource(starter.description),
                            icon = starter.icon,
                            onClick = { onStartPrompt(prompt) },
                        )
                        Spacer(Modifier.height(OmniSpacing.sm))
                    }
                }
            }
            item(key = "access") {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    HomeActionRow(
                        title = stringResource(R.string.home_refinement_access_title),
                        description = stringResource(accessDescription),
                        icon = Icons.Outlined.Settings,
                        onClick = onOpenDevices,
                    )
                }
            }
            item(key = "recent") {
                RecentSection(recent, onOpenChat, onOpenAllChats, onNewChat)
            }
        }
    }
}

@Composable
private fun HomeHeader(providerTitle: String, modelSelected: Boolean, onOpenProviders: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Let the model name breathe on small phones and when text is enlarged.
        if (maxWidth < 340.dp || fontScale > 1.25f) {
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.md)) {
                HomeWordmark()
                HomeModelSelector(providerTitle, modelSelected, onOpenProviders,
                    modifier = Modifier.fillMaxWidth(), expanded = true)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.md)) {
                HomeWordmark(Modifier.weight(1f))
                HomeModelSelector(providerTitle, modelSelected, onOpenProviders,
                    modifier = Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun HomeWordmark(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
        Box(Modifier.size(28.dp).border(3.dp, colors.primary, CircleShape),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(19.dp).border(1.dp, colors.primary.copy(alpha = 0.5f), CircleShape))
        }
        Text(stringResource(R.string.home_wordmark),
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.sp),
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun HomeModelSelector(
    providerTitle: String,
    modelSelected: Boolean,
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val actionLabel = stringResource(R.string.home_refinement_model_action, providerTitle)
    Column(modifier, horizontalAlignment = if (expanded) Alignment.Start else Alignment.End) {
        Surface(
            onClick = onOpenProviders,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = actionLabel },
            shape = CircleShape,
            color = colors.background,
            border = BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) colors.primary else colors.outline),
        ) {
            Row(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xs)) {
                Text(providerTitle, Modifier.weight(1f).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(OmniSpacing.xs))
        Text(stringResource(if (modelSelected) R.string.home_refinement_model_selected
            else R.string.home_refinement_model_setup),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant)
    }
}

@Composable
private fun HomeHeading() {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..16 -> R.string.home_greeting_afternoon
        in 17..21 -> R.string.home_greeting_evening
        else -> R.string.home_greeting_night
    }
    Text(stringResource(greeting), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(OmniSpacing.sm))
    Text(stringResource(R.string.home_refinement_headline),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun HomeComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val fieldLabel = stringResource(R.string.home_composer_field)
    val canSend = draft.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, if (focused) colors.primary else colors.outline),
    ) {
        Column(Modifier.padding(OmniSpacing.md)) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .padding(horizontal = OmniSpacing.xs, vertical = OmniSpacing.sm)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = fieldLabel },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.onSurface,
                    textAlign = TextAlign.Start,
                    textDirection = TextDirection.Content,
                ),
                cursorBrush = SolidColor(colors.primary),
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (draft.isEmpty()) Text(
                            stringResource(R.string.home_refinement_composer_hint),
                            modifier = Modifier.clearAndSetSemantics {},
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(OmniSpacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onOpenVoice,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.primary, contentColor = colors.onPrimary),
                ) {
                    Icon(Icons.Outlined.Mic, stringResource(R.string.home_refinement_voice_action),
                        Modifier.size(24.dp))
                }
                FilledIconButton(
                    onClick = onSubmit,
                    enabled = canSend,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.primary, contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceContainer,
                        disabledContentColor = colors.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.home_cd_send),
                        Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeActionRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            if (focused) colors.primary else colors.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(OmniSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.md)) {
            Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
                Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeSectionTitle(title: String) {
    Text(title, modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun RecentSection(
    conversations: List<Conversation>,
    onOpenChat: (String) -> Unit,
    onOpenAllChats: () -> Unit,
    onNewChat: () -> Unit,
) {
    val untitledChat = stringResource(R.string.home_untitled_chat)
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        if (fontScale > 1.25f) {
            HomeSectionTitle(stringResource(R.string.home_refinement_recent_title))
            if (conversations.isNotEmpty()) OmniTextButton(
                text = stringResource(R.string.home_see_all),
                onClick = onOpenAllChats,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                Box(Modifier.weight(1f)) {
                    HomeSectionTitle(stringResource(R.string.home_refinement_recent_title))
                }
                if (conversations.isNotEmpty()) OmniTextButton(
                    text = stringResource(R.string.home_see_all),
                    onClick = onOpenAllChats,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Spacer(Modifier.height(OmniSpacing.sm))
        if (conversations.isEmpty()) {
            Text(stringResource(R.string.home_recent_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OmniTextButton(stringResource(R.string.home_new_chat), onNewChat,
                modifier = Modifier.heightIn(min = 48.dp))
        } else {
            conversations.forEach { conversation ->
                HomeActionRow(
                    title = conversation.title.ifBlank { untitledChat },
                    description = recentTimeLabel(conversation.updatedAt),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    onClick = { onOpenChat(conversation.id) },
                )
                Spacer(Modifier.height(OmniSpacing.sm))
            }
        }
    }
}

@Composable
private fun recentTimeLabel(updatedAt: Long): String {
    val context = LocalContext.current
    val diff = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    return when {
        diff < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.home_time_just_now)
        diff < DateUtils.HOUR_IN_MILLIS -> stringResource(R.string.home_time_minutes_ago,
            (diff / DateUtils.MINUTE_IN_MILLIS).toInt().coerceAtLeast(1))
        diff < DateUtils.DAY_IN_MILLIS -> stringResource(R.string.home_time_hours_ago,
            (diff / DateUtils.HOUR_IN_MILLIS).toInt().coerceAtLeast(1))
        diff < 2 * DateUtils.DAY_IN_MILLIS -> stringResource(R.string.home_time_yesterday)
        diff < 7 * DateUtils.DAY_IN_MILLIS -> stringResource(R.string.home_time_days_ago,
            (diff / DateUtils.DAY_IN_MILLIS).toInt().coerceAtLeast(2))
        else -> remember(updatedAt, context) {
            DateUtils.formatDateTime(context, updatedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
        }
    }
}
