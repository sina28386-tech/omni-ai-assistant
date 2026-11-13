package com.omni.aiassistant.feature.bots

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.OmniTextField
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.domain.bots.BotProfiles
import com.omni.aiassistant.domain.bots.BotValidationIssue
import org.koin.androidx.compose.koinViewModel

@Composable
fun BotsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenDevices: () -> Unit,
    viewModel: BotsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Keep unsaved instructions in memory; only the encrypted repository persists roles.
    var editing by remember { mutableStateOf(false) }
    var botId by remember { mutableStateOf<String?>(null) }
    var baseProviderId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<BotItem?>(null) }
    val closeEditor: () -> Unit = {
        if (!state.busy) {
            editing = false
            viewModel.clearError()
        }
    }
    BackHandler(enabled = editing) { closeEditor() }

    deleteTarget?.let { bot ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleteTarget = null },
            title = { Text(stringResource(R.string.bots_workspace_delete_title, bot.name)) },
            text = { Text(stringResource(R.string.bots_workspace_delete_body)) },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = { viewModel.delete(bot.id) { deleteTarget = null } },
                ) { Text(stringResource(R.string.bots_workspace_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !state.busy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bots_workspace_cancel))
                }
            },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            contentPadding = PaddingValues(OmniSpacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.xl),
        ) {
            item(key = "heading") {
                Column {
                    IconButton(onClick = { if (editing) closeEditor() else onBack() }, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.bots_workspace_back))
                    }
                    ScreenHeader(
                        title = stringResource(if (editing) {
                            if (botId == null) R.string.bots_workspace_add else R.string.bots_workspace_edit
                        } else R.string.bots_workspace_title),
                        subtitle = if (editing) null else stringResource(R.string.bots_workspace_subtitle),
                    )
                }
            }
            if (state.loading || state.busy) {
                item(key = "progress") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.loading) Text(stringResource(R.string.bots_workspace_loading),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.validation != null || state.operationFailed) {
                item(key = "error") {
                    BotError(state, viewModel::clearError)
                }
            }
            if (editing) {
                item(key = "editor") {
                    BotEditor(
                        editingExisting = botId != null,
                        models = state.models,
                        baseProviderId = baseProviderId,
                        onModelSelected = { baseProviderId = it; viewModel.clearError() },
                        name = name,
                        onNameChange = { name = it; viewModel.clearError() },
                        instructions = instructions,
                        onInstructionsChange = { instructions = it; viewModel.clearError() },
                        busy = state.busy,
                        onOpenProviders = onOpenProviders,
                        onCancel = closeEditor,
                        onSave = {
                            viewModel.save(botId, baseProviderId, name, instructions) { editing = false }
                        },
                    )
                }
            } else {
                item(key = "scope") {
                    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                        Text(stringResource(R.string.bots_workspace_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(if (state.agentMode) R.string.bots_workspace_agent_on
                            else R.string.bots_workspace_agent_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OmniTextButton(stringResource(R.string.bots_workspace_access), onOpenDevices)
                    }
                }
                item(key = "create") {
                    if (state.models.isEmpty() && !state.loading) {
                        Text(stringResource(R.string.bots_workspace_no_provider),
                            style = MaterialTheme.typography.bodyMedium)
                        OmniSecondaryButton(stringResource(R.string.bots_workspace_providers), onOpenProviders,
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        OmniPrimaryButton(
                            text = stringResource(R.string.bots_workspace_add),
                            enabled = !state.busy && !state.loading,
                            icon = Icons.Outlined.Add,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                botId = null
                                baseProviderId = state.models.firstOrNull()?.id
                                name = ""
                                instructions = ""
                                viewModel.clearError()
                                editing = true
                            },
                        )
                    }
                }
                if (state.bots.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                            Text(stringResource(R.string.bots_workspace_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.bots_workspace_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(state.bots, key = { it.id }) { bot ->
                    BotRow(
                        bot, state.busy,
                        onOpen = { viewModel.openConversation(bot.id, onOpenChat) },
                        onEdit = {
                            botId = bot.id
                            baseProviderId = null
                            name = bot.name
                            instructions = bot.instructions
                            viewModel.clearError()
                            editing = true
                        },
                        onDelete = { deleteTarget = bot },
                    )
                }
            }
        }
    }
}

@Composable
private fun BotRow(bot: BotItem, busy: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(OmniSpacing.lg), verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
            Text(bot.name, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(bot.modelId, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(bot.instructions, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!bot.enabled || !bot.supportsTools) Text(
                stringResource(if (!bot.enabled) R.string.bots_workspace_disabled else R.string.bots_workspace_chat_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OmniSecondaryButton(
                stringResource(if (bot.hasConversation) R.string.bots_workspace_chat_continue else R.string.bots_workspace_chat_new),
                onOpen, modifier = Modifier.fillMaxWidth(), enabled = !busy && bot.enabled,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                OmniTextButton(stringResource(R.string.bots_workspace_edit), onEdit, enabled = !busy)
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.bots_workspace_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BotEditor(
    editingExisting: Boolean,
    models: List<BotModelChoice>,
    baseProviderId: String?,
    onModelSelected: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    busy: Boolean,
    onOpenProviders: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.md)) {
        if (!editingExisting) {
            ModelPicker(models, baseProviderId, !busy, onModelSelected)
            Text(stringResource(R.string.bots_workspace_model_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (models.isEmpty()) OmniTextButton(stringResource(R.string.bots_workspace_providers), onOpenProviders)
        }
        val nameLabel = stringResource(R.string.bots_workspace_name_label)
        Text(nameLabel, style = MaterialTheme.typography.labelLarge)
        OmniTextField(name, onNameChange,
            modifier = Modifier.semantics { contentDescription = nameLabel },
            placeholder = stringResource(R.string.bots_workspace_name_hint), enabled = !busy)
        val roleLabel = stringResource(R.string.bots_workspace_role_label)
        Text(roleLabel, style = MaterialTheme.typography.labelLarge)
        OmniTextField(instructions, onInstructionsChange,
            modifier = Modifier.semantics { contentDescription = roleLabel },
            placeholder = stringResource(R.string.bots_workspace_role_hint),
            enabled = !busy, singleLine = false, minLines = 5, maxLines = 12)
        Text(stringResource(R.string.bots_workspace_role_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OmniPrimaryButton(
            text = stringResource(if (busy) R.string.bots_workspace_saving else R.string.bots_workspace_save),
            onClick = onSave,
            enabled = !busy && (editingExisting || models.any { it.id == baseProviderId }),
            loading = busy, modifier = Modifier.fillMaxWidth(),
        )
        OmniTextButton(stringResource(R.string.bots_workspace_cancel), onCancel, enabled = !busy)
    }
}

@Composable
private fun ModelPicker(models: List<BotModelChoice>, selectedId: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
        Text(stringResource(R.string.bots_workspace_model_label), style = MaterialTheme.typography.labelLarge)
        Box {
            OmniSecondaryButton(
                text = selected?.let { "${it.name} · ${it.modelId}" }
                    ?: stringResource(R.string.bots_workspace_choose_model),
                onClick = { expanded = true }, enabled = enabled && models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.ExpandMore,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text("${model.name} · ${model.modelId}") },
                        onClick = { expanded = false; onSelect(model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BotError(state: BotsUiState, onDismiss: () -> Unit) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(botErrorResource(state.validation)),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        OmniTextButton(stringResource(R.string.bots_workspace_dismiss_error), onDismiss)
    }
}

@StringRes
private fun botErrorResource(issue: BotValidationIssue?): Int = when (issue) {
    BotValidationIssue.NAME_REQUIRED -> R.string.bots_workspace_error_name_required
    BotValidationIssue.NAME_TOO_LONG -> R.string.bots_workspace_error_name_long
    BotValidationIssue.INSTRUCTIONS_REQUIRED -> R.string.bots_workspace_error_role_required
    BotValidationIssue.INSTRUCTIONS_TOO_LONG -> R.string.bots_workspace_error_role_long
    BotValidationIssue.PROVIDER_UNAVAILABLE -> R.string.bots_workspace_error_provider
    BotValidationIssue.INVALID_BOT_ID -> R.string.bots_workspace_error_missing
    null -> R.string.bots_workspace_error_generic
}
