package com.omni.aiassistant.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.aiassistant.R
import com.omni.aiassistant.core.device.FileRecord
import com.omni.aiassistant.core.ui.AttachmentImage
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.MarkdownMessage
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.device.DeviceAction
import com.omni.aiassistant.device.FileOutcome
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
import com.omni.aiassistant.domain.model.PreparedAttachment
import com.omni.aiassistant.domain.model.ProviderProfile

/* -------------------------------------------------------------------------- */
/* Small helpers                                                              */
/* -------------------------------------------------------------------------- */

internal fun recognizerIntent(languageTag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

/**
 * Tool turns are part of the transcript but only become visible activity cards
 * when the user asked for them. Assistant turns that only carried tool calls
 * (and therefore have no text) are hidden.
 */
internal fun shouldRenderMessage(message: ChatMessage, showToolActivity: Boolean): Boolean =
    when (message.role) {
        MessageRole.TOOL -> showToolActivity
        MessageRole.ASSISTANT -> message.content.isNotBlank() || message.toolCalls.isNotEmpty()
        MessageRole.SYSTEM -> false
        MessageRole.USER -> true
    }

internal fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Omni", text))
}
/**
 * True when the last visible item is (almost) on screen. Used to decide
 * whether new content should keep scrolling the transcript.
 */
internal fun LazyListState.isAtBottom(tolerance: Int = 48): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    val last = layoutInfo.totalItemsCount - 1
    if (lastVisible.index >= last) return true
    return layoutInfo.viewportEndOffset - lastVisible.offset <= tolerance
}

/** Scrolls to the bottom without an animation, used for programmatic follow. */
internal suspend fun LazyListState.snapToBottom(index: Int) {
    scrollToItem(index.coerceAtLeast(0))
}

internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/* -------------------------------------------------------------------------- */
/* Top bar                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
internal fun ChatTopBar(
    title: String,
    provider: ProviderProfile?,
    providers: List<ProviderProfile>,
    providerMenu: Boolean,
    onProviderMenuChange: (Boolean) -> Unit,
    onSelectProvider: (String) -> Unit,
    onManageProviders: () -> Unit,
    onBack: () -> Unit,
    onOpenPrompt: () -> Unit,
    onReadLast: () -> Unit,
    onStopReading: () -> Unit,
    canRead: Boolean,
    autoTts: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = OmniSpacing.sm, end = OmniSpacing.xs),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    // The provider chip *is* the model switcher. A real chip
                    // instead of a bare icon keeps the active model visible.
                    Box {
                        Surface(
                            color = if (provider != null) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (provider != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(OmniRadius.pill),
                            modifier = Modifier
                                .clip(RoundedCornerShape(OmniRadius.pill))
                                .clickable { onProviderMenuChange(true) },
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    start = OmniSpacing.sm,
                                    end = OmniSpacing.sm,
                                    top = 3.dp,
                                    bottom = 3.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Bolt,
                                    contentDescription = null,
                                    tint = if (provider != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = provider?.modelId
                                        ?: stringResource(R.string.chat_no_provider),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = providerMenu,
                            onDismissRequest = { onProviderMenuChange(false) },
                        ) {
                            if (providers.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_no_providers)) },
                                    onClick = {},
                                    enabled = false,
                                )
                            } else {
                                providers.forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(
                                                    R.string.provider_model,
                                                    item.name,
                                                    item.modelId,
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            onProviderMenuChange(false)
                                            onSelectProvider(item.id)
                                        },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_manage_providers)) },
                                onClick = {
                                    onProviderMenuChange(false)
                                    onManageProviders()
                                },
                            )
                        }
                    }
                }
                androidx.compose.material3.IconButton(onClick = onOpenPrompt) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = stringResource(R.string.chat_system_prompt_title),
                    )
                }
                var overflow by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.IconButton(onClick = { overflow = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.details),
                        )
                    }
                    DropdownMenu(
                        expanded = overflow,
                        onDismissRequest = { overflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_read_aloud)) },
                            enabled = canRead,
                            onClick = {
                                overflow = false
                                onReadLast()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_stop_reading)) },
                            enabled = canRead,
                            onClick = {
                                overflow = false
                                onStopReading()
                            },
                        )
                    }
                }
            }
            Hairline()
        }
    }
}

@Composable
internal fun CreditSaverLine(contextMessages: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OmniSpacing.screenHorizontal, vertical = OmniSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(OmniSpacing.xs))
        Text(
            text = stringResource(R.string.chat_credit_saver_line, contextMessages),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Banners                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
internal fun ChatErrorBanner(
    error: Throwable,
    retryTargetId: String?,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    var showDetails by remember(error) { mutableStateOf(false) }
    val isProviderIssue = error is com.omni.aiassistant.core.error.AppError.Authentication ||
        error is com.omni.aiassistant.core.error.AppError.ModelUnavailable
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(OmniRadius.medium),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = OmniSpacing.screenHorizontal, end = OmniSpacing.screenHorizontal, top = OmniSpacing.sm),
    ) {
        Column(Modifier.padding(OmniSpacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(OmniSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_error_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = chatErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.chat_dismiss_error),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                retryTargetId?.let { id ->
                    OmniTextButton(
                        text = stringResource(R.string.chat_retry),
                        onClick = { onRetry(id) },
                    )
                }
                if (isProviderIssue) {
                    OmniTextButton(
                        text = stringResource(R.string.chat_manage_providers),
                        onClick = onOpenProviders,
                    )
                }
                OmniTextButton(
                    text = stringResource(R.string.chat_details),
                    onClick = { showDetails = !showDetails },
                )
            }
            if (showDetails) {
                Box(
                    Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "${error::class.simpleName}: ${error.message.orEmpty().take(600)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = OmniSpacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatNoticeBanner(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(OmniRadius.medium),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = OmniSpacing.screenHorizontal, end = OmniSpacing.screenHorizontal, top = OmniSpacing.sm),
    ) {
        Row(
            modifier = Modifier.padding(OmniSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(OmniSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.chat_dismiss_error),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Empty state                                                                */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChatState(
    hasProvider: Boolean,
    onOpenProviderPicker: () -> Unit,
    onPrompt: (String) -> Unit,
) {
    val prompts = listOf(
        stringResource(R.string.chat_starter_plan),
        stringResource(R.string.chat_starter_explain),
        stringResource(R.string.chat_starter_summarize),
        stringResource(R.string.chat_starter_write),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OmniSpacing.screenHorizontal, vertical = OmniSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(OmniSpacing.xs))
        Text(
            text = if (hasProvider) {
                stringResource(R.string.chat_empty_body)
            } else {
                stringResource(R.string.chat_empty_provider_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasProvider) {
            Spacer(Modifier.height(OmniSpacing.md))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_add_service),
                onClick = onOpenProviderPicker,
            )
        }
        Spacer(Modifier.height(OmniSpacing.xl))
        Text(
            text = stringResource(R.string.chat_suggestion_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(OmniSpacing.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            prompts.forEach { prompt ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(OmniRadius.large),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clickable { onPrompt(prompt) },
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(
                            horizontal = OmniSpacing.md,
                            vertical = OmniSpacing.sm,
                        ),
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Messages                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
internal fun UserMessage(
    message: ChatMessage,
    content: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm)) {
                if (content.isNotBlank()) {
                    SelectionContainerCompat {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                message.attachments.forEach { attachment ->
                    AttachmentPreview(attachment)
                }            }
        }
        MessageActionRow(
            onCopy = onCopy,
            onShare = onShare,
            onEdit = onEdit,
            onDelete = onDelete,
            alignEnd = true,
        )
    }
}

@Composable
internal fun AssistantMessage(
    message: ChatMessage,
    content: String,
    streaming: Boolean,
    isLastMessage: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_brand_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(OmniSpacing.xs))
        if (content.isNotBlank()) {
            MarkdownMessage(content)
        } else if (streaming) {
            Text(
                text = stringResource(R.string.chat_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (message.status == MessageStatus.FAILED) {
            Spacer(Modifier.height(OmniSpacing.xs))
            StatusPill(
                text = stringResource(R.string.chat_status_failed),
                tone = com.omni.aiassistant.core.ui.OmniStatusTone.ERROR,
            )
        }
        if (message.status == MessageStatus.CANCELED) {
            Spacer(Modifier.height(OmniSpacing.xs))
            StatusPill(
                text = stringResource(R.string.chat_status_canceled),
                tone = com.omni.aiassistant.core.ui.OmniStatusTone.WARNING,
            )
        }
        MessageActionRow(
            onCopy = if (content.isNotBlank()) onCopy else null,
            onShare = if (content.isNotBlank()) onShare else null,
            onEdit = null,
            onDelete = onDelete,
            onSpeak = onSpeak,
            onRetry = onRetry,
            onRegenerate = if (isLastMessage) onRegenerate else null,
            alignEnd = false,
        )
    }
}

@Composable
private fun MessageActionRow(
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    alignEnd: Boolean,
) {
    val actions = listOfNotNull(
        onCopy?.let { Triple(R.string.chat_copy, it, Icons.Outlined.ContentCopy) },
        onShare?.let { Triple(R.string.chat_share, it, Icons.Outlined.Details) },
        onSpeak?.let { Triple(R.string.chat_read_aloud, it, Icons.Outlined.VolumeUp) },
        onEdit?.let { Triple(R.string.chat_edit, it, Icons.Outlined.Edit) },
        onRegenerate?.let { Triple(R.string.chat_regenerate, it, Icons.Outlined.Refresh) },
        onRetry?.let { Triple(R.string.chat_retry, it, Icons.Outlined.Refresh) },
        onDelete?.let { Triple(R.string.chat_delete, it, Icons.Outlined.Delete) },
    )
    if (actions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OmniSpacing.xs),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { (labelRes, action, icon) ->
            androidx.compose.material3.IconButton(
                onClick = action,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(labelRes),
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreview(attachment: com.omni.aiassistant.domain.model.Attachment) {
    Spacer(Modifier.height(OmniSpacing.xs))
    AttachmentImage(
        path = attachment.localPath,
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(OmniRadius.small)),
    )
}

/** Selection support without importing the selection API into every call site. */
@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}

/* -------------------------------------------------------------------------- */
/* Live tool strip                                                            */
/* -------------------------------------------------------------------------- */

@Composable
internal fun LiveToolStrip(
    toolName: String?,
    step: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "liveTool")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "liveToolAngle",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(OmniRadius.medium),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                start = OmniSpacing.md,
                end = OmniSpacing.xs,
                top = OmniSpacing.sm,
                bottom = OmniSpacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(angle),
            )
            Spacer(Modifier.width(OmniSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (toolName.isNullOrBlank()) {
                        stringResource(R.string.chat_live_working)
                    } else {
                        stringResource(R.string.chat_live_using, toolName)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.chat_live_step, step),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.chat_live_stop),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Composer                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
internal fun ChatComposer(
    state: ChatUiState,
    focusRequester: FocusRequester,
    showProviderNotice: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onVoice: () -> Unit,
    onVoiceLanguage: (String) -> Unit,
    voiceLanguage: String,
    voiceListening: Boolean,
    voicePartial: String,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onOpenProviderPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
    ) {
        if (voiceListening) {
            Text(
                text = voicePartial.ifBlank { stringResource(R.string.chat_dictation_listening) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = OmniSpacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (showProviderNotice) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = OmniSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_provider_required_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OmniTextButton(
                    text = stringResource(R.string.chat_add_service),
                    onClick = onOpenProviderPicker,
                )
            }
        }
        if (state.pendingAttachments.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                modifier = Modifier.padding(bottom = OmniSpacing.sm),
            ) {
                items(state.pendingAttachments, key = { it.id }) { attachment ->
                    Box {
                    AttachmentImage(
                        path = attachment.filePath,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(OmniRadius.small)),
                    )
                        androidx.compose.material3.IconButton(
                            onClick = { onRemoveAttachment(attachment.id) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.chat_remove_attachment),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(OmniRadius.large),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = OmniSpacing.sm,
                    end = OmniSpacing.sm,
                    top = OmniSpacing.xs,
                    bottom = OmniSpacing.xs,
                ),
                verticalAlignment = Alignment.Bottom,
            ) {
                androidx.compose.material3.IconButton(onClick = onGallery) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.chat_add_image),
                        modifier = Modifier.size(20.dp),
                    )
                }
                androidx.compose.material3.IconButton(onClick = onCamera) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.chat_take_photo),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = OmniSpacing.xs),
                ) {
                    if (state.draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_message_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    BasicTextField(
                        value = state.draft,
                        onValueChange = onDraft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                    )
                }
                Box {
                    var languageMenu by remember { mutableStateOf(false) }
                    androidx.compose.material3.TextButton(onClick = { languageMenu = true }, enabled = !voiceListening, modifier = Modifier.width(48.dp)) {
                        Text(if (voiceLanguage.startsWith("fa", ignoreCase = true)) "فا" else if (voiceLanguage.startsWith("en", ignoreCase = true)) "EN" else voiceLanguage.take(5))
                    }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.language_persian)) }, onClick = { languageMenu = false; onVoiceLanguage("fa-IR") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.language_english)) }, onClick = { languageMenu = false; onVoiceLanguage("en-US") })
                    }
                }
                androidx.compose.material3.IconButton(onClick = onVoice) {
                    Icon(
                        if (voiceListening) Icons.Outlined.Close else Icons.Outlined.KeyboardVoice,
                        contentDescription = stringResource(if (voiceListening) R.string.chat_dictation_stop else R.string.chat_dictation_start),
                        modifier = Modifier.size(20.dp),
                    )
                }
                val canSend = state.draft.isNotBlank() || state.pendingAttachments.isNotEmpty()
                if (state.generating) {
                    androidx.compose.material3.IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    androidx.compose.material3.IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Dialogs                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
internal fun SystemPromptDialog(
    conversation: Conversation?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(conversation?.id) { mutableStateOf(conversation?.systemPrompt.orEmpty()) }
    OmniDialog(
        title = stringResource(R.string.chat_system_prompt_title),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.chat_system_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(OmniSpacing.sm))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            minLines = 4,
            maxLines = 10,
        )
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
            Spacer(Modifier.width(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_save),
                onClick = { onSave(text) },
            )
        }
    }
}

@Composable
internal fun EditMessageDialog(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(message.id) { mutableStateOf(message.content) }
    OmniDialog(
        title = stringResource(R.string.chat_edit_message_title),
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp),
            minLines = 3,
            maxLines = 10,
        )
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
            Spacer(Modifier.width(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_edit_and_resend),
                onClick = { onSave(text) },
            )
        }
    }
}

@Composable
internal fun DeleteMessageDialog(
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    OmniDialog(
        title = stringResource(R.string.chat_delete_message_title),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.chat_delete_message_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
            Spacer(Modifier.width(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_delete),
                onClick = onDelete,
            )
        }
    }
}

@Composable
internal fun PendingDeleteDialog(
    candidate: FileRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OmniDialog(
        title = stringResource(R.string.chat_delete_file_title),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.chat_delete_file_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(OmniSpacing.sm))
        Text(
            text = candidate.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = candidate.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
            Spacer(Modifier.width(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_delete_file_action),
                onClick = onConfirm,
            )
        }
    }
}

@Composable
internal fun FileOutcomesDialog(
    outcomes: List<FileOutcome>,
    onPlay: (FileRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    OmniDialog(
        title = stringResource(R.string.chat_file_results_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            outcomes.forEach { outcome ->
                when (outcome) {
                    is FileOutcome.Searched -> {
                        Text(
                            text = stringResource(R.string.chat_file_matches_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        outcome.matches.take(12).forEach { record ->
                            OmniFileRow(record = record, onPlay = { onPlay(record) })
                        }
                    }

                    is FileOutcome.NeedsChoice -> {
                        Text(
                            text = stringResource(R.string.chat_file_matches_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        outcome.options.take(12).forEach { record ->
                            OmniFileRow(record = record, onPlay = { onPlay(record) })
                        }
                    }

                    is FileOutcome.Launched ->
                        OutcomeLine(stringResource(R.string.chat_file_open_action) + " Â· " + outcome.label)

                    is FileOutcome.Deleted ->
                        OutcomeLine(stringResource(R.string.chat_delete_file_action) + " Â· " + outcome.name)

                    is FileOutcome.Downloaded ->
                        OutcomeLine(stringResource(R.string.chat_add_image) + " Â· " + outcome.name)

                    is FileOutcome.WallpaperSet ->
                        OutcomeLine(outcome.name)

                    is FileOutcome.Blocked ->
                        OutcomeLine(stringResource(R.string.chat_file_blocked_title) + " Â· " + outcome.reason)

                    is FileOutcome.Failed ->
                        OutcomeLine(stringResource(R.string.chat_file_failed_title) + " Â· " + outcome.message)

                    is FileOutcome.DeleteConfirm -> Unit
                }
            }
        }
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniPrimaryButton(text = stringResource(R.string.chat_done), onClick = onDismiss)
        }
    }
}

@Composable
private fun OutcomeLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OmniSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(OmniSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OmniFileRow(record: FileRecord, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = OmniSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIconWell(icon = Icons.Outlined.FolderOpen)
        Spacer(Modifier.width(OmniSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = record.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.FileDownload,
            contentDescription = stringResource(R.string.chat_file_open_action),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun DeviceInfoDialog(
    report: String,
    onDismiss: () -> Unit,
) {
    OmniDialog(
        title = stringResource(R.string.chat_select_service),
        onDismiss = onDismiss,
    ) {
        Box(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniPrimaryButton(text = stringResource(R.string.chat_done), onClick = onDismiss)
        }
    }
}

@Composable
internal fun DeviceActionsDialog(
    actions: List<DeviceAction>,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    OmniDialog(
        title = stringResource(R.string.chat_continue),
        onDismiss = onDismiss,
    ) {
        actions.forEach { action ->
            Text(
                text = describeAction(action),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = OmniSpacing.xs),
            )
        }
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
            Spacer(Modifier.width(OmniSpacing.sm))
            OmniPrimaryButton(
                text = stringResource(R.string.chat_continue),
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun describeAction(action: DeviceAction): String = when (action) {
    is DeviceAction.WebSearch -> action.query
    is DeviceAction.Dial -> action.phoneNumber
    is DeviceAction.Sms -> action.phoneNumber
    is DeviceAction.Email -> action.address
    DeviceAction.Camera -> stringResource(R.string.chat_take_photo)
    DeviceAction.Gallery -> stringResource(R.string.chat_add_image)
    DeviceAction.Contacts -> stringResource(R.string.chat_contacts_action)
    is DeviceAction.Maps -> action.query
    DeviceAction.AppSettings -> stringResource(R.string.chat_add_service)
    DeviceAction.DeviceInfo -> stringResource(R.string.chat_select_service)
    is DeviceAction.FindFile -> action.query
    is DeviceAction.PlayFile -> action.query
    is DeviceAction.DownloadFile -> action.query ?: action.url.orEmpty()
    is DeviceAction.DeleteFile -> action.exactName
    is DeviceAction.SetWallpaper -> action.query
}

@Composable
internal fun chatErrorMessage(error: Throwable): String = when (error) {
    is com.omni.aiassistant.core.error.AppError.Authentication ->
        stringResource(R.string.chat_error_auth)

    is com.omni.aiassistant.core.error.AppError.RateLimit ->
        stringResource(R.string.chat_error_rate_limit)

    is com.omni.aiassistant.core.error.AppError.InsufficientCredit ->
        stringResource(R.string.chat_error_credit)

    is com.omni.aiassistant.core.error.AppError.ModelUnavailable ->
        stringResource(R.string.chat_error_model)

    is com.omni.aiassistant.core.error.AppError.ContextLength ->
        stringResource(R.string.chat_error_context)

    is com.omni.aiassistant.core.error.AppError.Network ->
        stringResource(R.string.chat_error_network)

    is com.omni.aiassistant.core.error.AppError.Tls ->
        stringResource(R.string.chat_error_tls)

    is com.omni.aiassistant.core.error.AppError.Endpoint ->
        stringResource(R.string.chat_error_endpoint)

    is com.omni.aiassistant.core.error.AppError.Timeout ->
        stringResource(R.string.chat_error_timeout)

    is com.omni.aiassistant.core.error.AppError.Backup ->
        stringResource(R.string.chat_error_backup)

    is com.omni.aiassistant.core.error.AppError.Encryption ->
        stringResource(R.string.chat_error_encryption)

    is com.omni.aiassistant.core.error.AppError.Validation ->
        stringResource(R.string.chat_error_validation)

    is com.omni.aiassistant.core.error.AppError.UnsupportedFeature ->
        stringResource(R.string.chat_error_unsupported)

    is com.omni.aiassistant.core.error.AppError.Permission ->
        stringResource(R.string.chat_error_permission)

    is com.omni.aiassistant.core.error.AppError.Provider ->
        stringResource(R.string.chat_error_provider)

    is com.omni.aiassistant.core.error.AppError.Database ->
        stringResource(R.string.chat_error_database)

    else -> stringResource(R.string.chat_error_generic)
}

/* -------------------------------------------------------------------------- */
/* Dialog shell                                                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun OmniDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(OmniRadius.large),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OmniSpacing.lg, vertical = OmniSpacing.xl)
                .widthIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier.padding(OmniSpacing.lg),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(OmniSpacing.md))
                content()
            }
        }
    }
}
