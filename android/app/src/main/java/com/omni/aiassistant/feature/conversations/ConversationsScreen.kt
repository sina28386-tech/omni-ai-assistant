package com.omni.aiassistant.feature.conversations

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTabRow
import com.omni.aiassistant.core.ui.OmniTextField
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.domain.model.Conversation

@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    titleRes: Int = R.string.conversations,
    /**
     * When non-null, the list is locked to that archive slice and the
     * toggle is hidden (used by the history tab). Null = interactive toggle.
     */
    lockedArchived: Boolean? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The History destination shares this ViewModel with Chats. Lock its query
    // to the archived slice while mounted, then restore the active slice so the
    // Chats tab does not inherit the History filter.
    LaunchedEffect(lockedArchived) {
        if (lockedArchived != null) viewModel.setShowArchived(lockedArchived)
    }
    DisposableEffect(lockedArchived) {
        onDispose { if (lockedArchived != null) viewModel.setShowArchived(false) }
    }

    val visible = state.conversations
    val showingArchived = state.showArchived
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    renameTarget?.let { target ->
        RenameConversationDialog(target = target, onDismiss = { renameTarget = null }) { title ->
            viewModel.rename(target.id, title)
            renameTarget = null
        }
    }
    deleteTarget?.let { target ->
        DeleteConversationDialog(
            target = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
        )
    }
    if (clearConfirm) {
        ClearAllDialog(
            onDismiss = { clearConfirm = false },
            onConfirm = {
                viewModel.clearAll()
                clearConfirm = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OmniSpacing.screenHorizontal,
            end = OmniSpacing.screenHorizontal,
            top = OmniSpacing.xl,
            bottom = OmniSpacing.xxxl,
        ),
    ) {
        item(key = "conversations_header") {
            ConversationsHeader(
                title = stringResource(titleRes),
                search = state.search,
                onSearchChange = viewModel::setSearch,
                showToggle = lockedArchived == null,
                showingArchived = showingArchived,
                onSelectArchived = viewModel::setShowArchived,
                onNew = onNew,
                canClearAll = visible.isNotEmpty(),
                onClearAll = { clearConfirm = true },
            )
        }
        state.error?.let { error ->
            item(key = "conversations_error") {
                ConversationErrorBanner(error = error, onDismiss = viewModel::clearError)
            }
        }
        if (visible.isEmpty()) {
            item(key = "conversations_empty") {
                ConversationsEmptyState(
                    search = state.search,
                    showingArchived = showingArchived,
                    lockedArchived = lockedArchived,
                    onNew = onNew,
                    onClearSearch = { viewModel.setSearch("") },
                    onViewCurrent = { viewModel.setShowArchived(false) },
                )
            }
        } else {
            itemsIndexed(visible, key = { _, conversation -> conversation.id }) { index, conversation ->
                ConversationRow(
                    conversation = conversation,
                    onOpen = { onOpen(conversation.id) },
                    onRename = { renameTarget = conversation },
                    onPin = { viewModel.setPinned(conversation.id, !conversation.pinned) },
                    onArchive = { viewModel.setArchived(conversation.id, !conversation.archived) },
                    onDelete = { deleteTarget = conversation },
                    showDivider = index != visible.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun ConversationsHeader(
    title: String,
    search: String,
    onSearchChange: (String) -> Unit,
    showToggle: Boolean,
    showingArchived: Boolean,
    onSelectArchived: (Boolean) -> Unit,
    onNew: () -> Unit,
    canClearAll: Boolean,
    onClearAll: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.lg)) {
        ScreenHeader(
            title = title,
            actions = {
                if (canClearAll) {
                    Box {
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_all_chats)) },
                                onClick = {
                                    overflowOpen = false
                                    onClearAll()
                                },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(OmniSpacing.xs))
                FilledIconButton(
                    onClick = onNew,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.new_chat),
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )

        OmniTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.search_conversations),
            leadingIcon = Icons.Outlined.Search,
            trailing = if (search.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.conversations_clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )

        if (showToggle) {
            OmniTabRow(
                items = listOf(
                    stringResource(R.string.show_active),
                    stringResource(R.string.show_archived),
                ),
                selectedIndex = if (showingArchived) 1 else 0,
                onSelect = { onSelectArchived(it == 1) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val displayTitle = if (conversation.title.isBlank()) {
        stringResource(R.string.conversations_untitled)
    } else {
        conversation.title
    }
    val relativeTime = remember(context, conversation.updatedAt) {
        DateUtils.getRelativeTimeSpanString(
            conversation.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    val updatedLabel = stringResource(R.string.conversations_updated_at, relativeTime)
    val menuLabel = stringResource(R.string.conversation_menu)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menuExpanded = true },
                    onClick = onOpen,
                )
                .padding(
                    start = OmniSpacing.lg,
                    end = OmniSpacing.xs,
                    top = OmniSpacing.md,
                    bottom = OmniSpacing.md,
                )
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(
                    text = updatedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = menuLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ConversationMenu(
                    expanded = menuExpanded,
                    conversation = conversation,
                    onDismiss = { menuExpanded = false },
                    onRename = onRename,
                    onPin = onPin,
                    onArchive = onArchive,
                    onDelete = onDelete,
                )
            }
        }
        if (showDivider) Hairline(startPadding = OmniSpacing.lg)
    }
}

@Composable
private fun ConversationMenu(
    expanded: Boolean,
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.rename)) },
            onClick = {
                onDismiss()
                onRename()
            },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (conversation.pinned) R.string.unpin else R.string.pin)) },
            onClick = {
                onDismiss()
                onPin()
            },
            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(if (conversation.archived) R.string.unarchive else R.string.archive))
            },
            onClick = {
                onDismiss()
                onArchive()
            },
            leadingIcon = {
                Icon(
                    imageVector = if (conversation.archived) {
                        Icons.Outlined.Unarchive
                    } else {
                        Icons.Outlined.Archive
                    },
                    contentDescription = null,
                )
            },
        )
        HorizontalDivider(Modifier.padding(vertical = OmniSpacing.xs))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            onClick = {
                onDismiss()
                onDelete()
            },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
        )
    }
}

@Composable
private fun ConversationsEmptyState(
    search: String,
    showingArchived: Boolean,
    lockedArchived: Boolean?,
    onNew: () -> Unit,
    onClearSearch: () -> Unit,
    onViewCurrent: () -> Unit,
) {
    val icon: ImageVector
    val title: String
    val body: String
    val secondary: Pair<String, () -> Unit>?

    when {
        search.isNotBlank() -> {
            icon = Icons.Outlined.SearchOff
            title = stringResource(R.string.conversations_empty_search_title)
            body = stringResource(R.string.conversations_empty_search_body, search)
            secondary = stringResource(R.string.conversations_clear_search) to onClearSearch
        }
        showingArchived -> {
            icon = Icons.Outlined.Archive
            title = stringResource(R.string.conversations_empty_archived_title)
            body = stringResource(
                if (lockedArchived != null) {
                    R.string.conversations_empty_archived_locked_body
                } else {
                    R.string.conversations_empty_archived_body
                },
            )
            secondary = if (lockedArchived == null) {
                stringResource(R.string.conversations_view_current) to onViewCurrent
            } else {
                null
            }
        }
        else -> {
            icon = Icons.Outlined.Forum
            title = stringResource(R.string.no_conversations)
            body = stringResource(R.string.start_first_chat)
            secondary = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OmniSpacing.lg, vertical = OmniSpacing.giant),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(OmniRadius.small),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.height(OmniSpacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(OmniSpacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(OmniSpacing.lg))
        OmniPrimaryButton(
            text = stringResource(R.string.new_chat),
            onClick = onNew,
            icon = Icons.Outlined.Add,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (secondary != null) {
            Spacer(Modifier.height(OmniSpacing.xs))
            OmniTextButton(
                text = secondary.first,
                onClick = secondary.second,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun ConversationErrorBanner(error: Throwable, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OmniSpacing.md),
        shape = RoundedCornerShape(OmniRadius.medium),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = OmniSpacing.md, top = OmniSpacing.sm, end = OmniSpacing.xs, bottom = OmniSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = conversationErrorMessage(error),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun conversationErrorMessage(error: Throwable): String = stringResource(
    when (error) {
        is AppError.Authentication -> R.string.error_auth
        is AppError.RateLimit -> R.string.error_rate_limit
        is AppError.InsufficientCredit -> R.string.error_credit
        is AppError.ModelUnavailable -> R.string.error_model
        is AppError.ContextLength -> R.string.error_context
        is AppError.Network -> R.string.error_network
        is AppError.Tls -> R.string.error_tls
        is AppError.Endpoint -> R.string.error_endpoint
        is AppError.Timeout -> R.string.error_timeout
        is AppError.Backup -> R.string.error_backup
        is AppError.Encryption -> R.string.error_encryption
        is AppError.Validation -> R.string.error_validation
        is AppError.UnsupportedFeature -> R.string.error_unsupported
        is AppError.Permission -> R.string.error_permission
        is AppError.Provider -> R.string.error_provider
        is AppError.Database -> R.string.error_database
        else -> R.string.error_generic
    },
)

@Composable
private fun RenameConversationDialog(
    target: Conversation,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(target.id) { mutableStateOf(target.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(OmniRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = stringResource(R.string.rename),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                Text(
                    text = stringResource(R.string.conversations_title_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OmniTextField(
                    value = value,
                    onValueChange = { value = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.conversations_untitled),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onSave(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteConversationDialog(
    target: Conversation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val displayTitle = if (target.title.isBlank()) {
        stringResource(R.string.conversations_untitled)
    } else {
        target.title
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(OmniRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = stringResource(R.string.conversations_delete_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.confirm_delete_conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.conversations_cannot_be_undone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.yes_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ClearAllDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(OmniRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = stringResource(R.string.clear_all_chats),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.clear_all_chats_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.conversations_clear_all_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.conversations_cannot_be_undone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.yes_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
