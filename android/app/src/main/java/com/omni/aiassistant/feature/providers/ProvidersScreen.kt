package com.omni.aiassistant.feature.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.network.ProviderPresets
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniGroup
import com.omni.aiassistant.core.ui.OmniIconWell
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniStatusTone
import com.omni.aiassistant.core.ui.OmniSwitchRow
import com.omni.aiassistant.core.ui.OmniTextField
import com.omni.aiassistant.core.ui.ScreenHeader
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusDot
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import org.koin.androidx.compose.koinViewModel

private enum class EditorAction { TEST, SAVE }

private data class EditorStatus(
    val title: String,
    val body: String,
    val tone: OmniStatusTone,
    val loading: Boolean = false,
)

@Composable
fun ProvidersScreen(viewModel: ProvidersViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<ProviderDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<ProviderProfile?>(null) }

    val openEditor: (ProviderDraft) -> Unit = { next ->
        viewModel.clearStatus()
        draft = next
    }

    deleteTarget?.let { profile ->
        DeleteProviderDialog(
            profile = profile,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(profile.id)
                deleteTarget = null
            },
        )
    }

    val currentDraft = draft
    if (currentDraft == null) {
        ProviderListScreen(
            state = state,
            onAdd = { openEditor(viewModel.newDraft()) },
            onEdit = { openEditor(viewModel.editDraft(it)) },
            onDuplicate = viewModel::duplicate,
            onDelete = { deleteTarget = it },
            onSetActive = viewModel::setActive,
        )
    } else {
        ProviderEditor(
            initial = currentDraft,
            testing = state.testing,
            testResult = state.testResult,
            toolTest = state.toolTest,
            error = state.error,
            onBack = {
                viewModel.clearStatus()
                draft = null
            },
            onTypeChanged = { value, type -> viewModel.applyTypePreset(value, type) },
            onTest = viewModel::test,
            onSave = { value -> viewModel.save(value) { draft = null } },
            onClearStatus = viewModel::clearStatus,
        )
    }
}

/* ------------------------------------------------------------------ list */

@Composable
private fun ProviderListScreen(
    state: ProvidersUiState,
    onAdd: () -> Unit,
    onEdit: (ProviderProfile) -> Unit,
    onDuplicate: (ProviderProfile) -> Unit,
    onDelete: (ProviderProfile) -> Unit,
    onSetActive: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                start = OmniSpacing.screenHorizontal,
                end = OmniSpacing.screenHorizontal,
                top = OmniSpacing.lg,
                bottom = OmniSpacing.xxxl,
            ),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.providers),
                    subtitle = stringResource(R.string.providers_screen_subtitle),
                )
            }

            if (state.localDevelopmentEnabled) {
                item {
                    Box(Modifier.padding(top = OmniSpacing.lg)) {
                        LocalDevelopmentBanner()
                    }
                }
            }

            if (state.profiles.isEmpty()) {
                item {
                    Box(Modifier.padding(top = OmniSpacing.xxl)) {
                        EmptyProviders(onAdd = onAdd)
                    }
                }
            } else {
                item {
                    Column {
                        OmniPrimaryButton(
                            text = stringResource(R.string.add_provider),
                            onClick = onAdd,
                            modifier = Modifier.padding(top = OmniSpacing.xl),
                            icon = Icons.Outlined.Add,
                        )
                        Spacer(Modifier.height(OmniSpacing.xxl))
                        SectionLabel(text = stringResource(R.string.providers_section_profiles))
                        Spacer(Modifier.height(OmniSpacing.sm))
                    }
                }
                item {
                    OmniGroup {
                        state.profiles.forEachIndexed { index, profile ->
                            ProviderRow(
                                profile = profile,
                                active = state.activeProviderId == profile.id,
                                showDivider = index < state.profiles.lastIndex,
                                onEdit = { onEdit(profile) },
                                onDuplicate = { onDuplicate(profile) },
                                onDelete = { onDelete(profile) },
                                onSetActive = { onSetActive(profile.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    profile: ProviderProfile,
    active: Boolean,
    showDivider: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val typeLabel = providerTypeLabel(profile.type)
    val displayName = profile.name.ifBlank { typeLabel }
    val model = profile.modelId.ifBlank { stringResource(R.string.providers_no_model) }
    val needsKey = ProviderPresets.forType(profile.type).apiKeyRequired && profile.apiKey.isBlank()

    val statusTone = when {
        !profile.enabled -> OmniStatusTone.NEUTRAL
        needsKey -> OmniStatusTone.WARNING
        else -> OmniStatusTone.SUCCESS
    }
    val statusLabel = stringResource(
        when {
            !profile.enabled -> R.string.provider_disabled
            needsKey -> R.string.providers_status_needs_key
            else -> R.string.providers_status_ready
        },
    )
    val statusColor = if (profile.enabled && needsKey) scheme.tertiary else scheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onEdit)
                .heightIn(min = 72.dp)
                .padding(
                    start = OmniSpacing.xl,
                    end = OmniSpacing.sm,
                    top = OmniSpacing.sm,
                    bottom = OmniSpacing.sm,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = OmniSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        StatusPill(
                            text = stringResource(R.string.providers_default_badge),
                            tone = OmniStatusTone.SUCCESS,
                        )
                    }
                }
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = if (profile.modelId.isBlank()) TextDirection.Content else TextDirection.Ltr,
                    ),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(if (profile.supportsTools) R.string.providers_tools_declared else R.string.providers_chat_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(OmniSpacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    StatusDot(tone = statusTone)
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(
                            R.string.providers_more_options_for,
                            displayName,
                        ),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(OmniRadius.medium),
                    containerColor = scheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    if (!active) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.providers_action_activate)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                            },
                            enabled = profile.enabled,
                            onClick = {
                                menuExpanded = false
                                onSetActive()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_provider)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate_provider)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = scheme.error) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = scheme.error)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
        if (showDivider) Hairline(startPadding = OmniSpacing.xl)
    }
}

@Composable
private fun LocalDevelopmentBanner() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OmniRadius.medium),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OmniSpacing.lg, vertical = OmniSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = scheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxs),
            ) {
                Text(
                    text = stringResource(R.string.providers_local_dev_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.providers_local_dev_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyProviders(onAdd: () -> Unit) {
    OmniGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.md),
        ) {
            OmniIconWell(
                icon = Icons.Outlined.Key,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.providers_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.providers_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(OmniSpacing.xs))
            OmniPrimaryButton(
                text = stringResource(R.string.add_provider),
                onClick = onAdd,
                icon = Icons.Outlined.Add,
            )
        }
    }
}

@Composable
private fun DeleteProviderDialog(
    profile: ProviderProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        val typeLabel = providerTypeLabel(profile.type)
        val displayName = profile.name.ifBlank { typeLabel }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(OmniRadius.xl),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(OmniSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.md),
            ) {
                Text(
                    text = stringResource(R.string.delete_provider_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.providers_delete_body_named, displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(OmniSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(OmniSpacing.sm))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(OmniRadius.medium),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.error,
                            contentColor = scheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- editor */

@Composable
private fun ProviderEditor(
    initial: ProviderDraft,
    testing: Boolean,
    testResult: ProviderTestResult,
    toolTest: ToolTestResult,
    error: Throwable?,
    onBack: () -> Unit,
    onTypeChanged: (ProviderDraft, ProviderType) -> ProviderDraft,
    onTest: (ProviderDraft) -> Unit,
    onSave: (ProviderDraft) -> Unit,
    onClearStatus: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var apiKeyVisible by remember(initial) { mutableStateOf(false) }
    var revision by remember(initial) { mutableIntStateOf(0) }
    var testedRevision by remember(initial) { mutableStateOf<Int?>(null) }
    var lastAction by remember(initial) { mutableStateOf<EditorAction?>(null) }

    BackHandler(onBack = onBack)

    fun commit(next: ProviderDraft) {
        draft = next
        revision += 1
        testedRevision = null
        lastAction = null
        onClearStatus()
    }

    fun chooseType(type: ProviderType) {
        if (type == draft.type) return
        draft = onTypeChanged(draft, type)
        revision += 1
        testedRevision = null
        lastAction = null
        onClearStatus()
    }

    fun runTest() {
        lastAction = EditorAction.TEST
        testedRevision = revision
        onTest(draft)
    }

    fun runSave() {
        lastAction = EditorAction.SAVE
        onClearStatus()
        onSave(draft)
    }

    val testIsCurrent = lastAction == EditorAction.TEST && testedRevision == revision
    val status: EditorStatus? = when {
        testIsCurrent && testing -> EditorStatus(
            title = stringResource(R.string.testing_connection),
            body = stringResource(R.string.providers_testing_body),
            tone = OmniStatusTone.INFO,
            loading = true,
        )
        testIsCurrent && testResult == ProviderTestResult.SUCCESS -> EditorStatus(
            title = stringResource(R.string.providers_test_success_title),
            body = stringResource(when (toolTest) {
                ToolTestResult.CONFIRMED -> R.string.providers_tool_test_confirmed
                ToolTestResult.UNCONFIRMED -> R.string.providers_tool_test_unconfirmed
                ToolTestResult.NOT_RUN -> R.string.providers_test_success_body
            }),
            tone = OmniStatusTone.SUCCESS,
        )
        testIsCurrent && testResult == ProviderTestResult.FAILURE -> EditorStatus(
            title = stringResource(R.string.providers_test_failed_title),
            body = providerErrorBody(error),
            tone = OmniStatusTone.ERROR,
        )
        lastAction == EditorAction.SAVE && error != null -> EditorStatus(
            title = stringResource(R.string.providers_save_failed_title),
            body = providerErrorBody(error),
            tone = OmniStatusTone.ERROR,
        )
        else -> null
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            EditorTopBar(
                title = stringResource(
                    if (initial.id == null) R.string.new_provider else R.string.edit_provider,
                ),
                onBack = onBack,
            )
            Hairline()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = OmniSpacing.screenHorizontal,
                            vertical = OmniSpacing.xxl,
                        ),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxxl),
                ) {
                    ConnectionSection(
                        draft = draft,
                        onDraftChange = { commit(it) },
                        onTypeSelect = { chooseType(it) },
                        apiKeyVisible = apiKeyVisible,
                        onToggleApiKey = { apiKeyVisible = !apiKeyVisible },
                    )
                    ModelSection(draft = draft, onDraftChange = { commit(it) })
                    BehaviorSection(draft = draft, onDraftChange = { commit(it) })
                    AdvancedSection(draft = draft, onDraftChange = { commit(it) })
                }
            }
            EditorBottomBar(
                testing = testing,
                saveLabel = stringResource(
                    if (initial.id == null) R.string.providers_save_provider else R.string.providers_save_changes,
                ),
                status = status,
                onTest = { runTest() },
                onSave = { runSave() },
            )
        }
    }
}

@Composable
private fun EditorTopBar(title: String, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = OmniSpacing.xs,
                end = OmniSpacing.lg,
                top = OmniSpacing.xs,
                bottom = OmniSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = scheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(OmniSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.provider_profile),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun EditorBottomBar(
    testing: Boolean,
    saveLabel: String,
    status: EditorStatus?,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Hairline()
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .padding(horizontal = OmniSpacing.screenHorizontal, vertical = OmniSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    status?.let { EditorStatusBar(it) }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.5f
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                                OmniSecondaryButton(
                                    text = stringResource(
                                        if (testing) R.string.testing_connection else R.string.test_connection,
                                    ),
                                    onClick = onTest,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !testing,
                                )
                                OmniPrimaryButton(
                                    text = saveLabel,
                                    onClick = onSave,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !testing,
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                            ) {
                                OmniSecondaryButton(
                                    text = stringResource(
                                        if (testing) R.string.testing_connection else R.string.test_connection,
                                    ),
                                    onClick = onTest,
                                    modifier = Modifier.weight(1f),
                                    enabled = !testing,
                                )
                                OmniPrimaryButton(
                                    text = saveLabel,
                                    onClick = onSave,
                                    modifier = Modifier.weight(1f),
                                    enabled = !testing,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(status: EditorStatus) {
    val scheme = MaterialTheme.colorScheme
    val container = when (status.tone) {
        OmniStatusTone.SUCCESS -> scheme.primaryContainer
        OmniStatusTone.ERROR -> scheme.errorContainer
        OmniStatusTone.WARNING -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when (status.tone) {
        OmniStatusTone.SUCCESS -> scheme.onPrimaryContainer
        OmniStatusTone.ERROR -> scheme.onErrorContainer
        OmniStatusTone.WARNING -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    val border = when (status.tone) {
        OmniStatusTone.SUCCESS -> scheme.primary.copy(alpha = 0.4f)
        OmniStatusTone.ERROR -> scheme.error.copy(alpha = 0.4f)
        else -> scheme.outlineVariant
    }
    val icon: ImageVector? = when (status.tone) {
        OmniStatusTone.SUCCESS -> Icons.Outlined.CheckCircle
        OmniStatusTone.ERROR -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Info
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OmniRadius.medium),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            if (status.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.xxs),
            ) {
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/* -------------------------------------------------------------- sections */

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Hairline()
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.lg),
            content = content,
        )
    }
}

@Composable
private fun ConnectionSection(
    draft: ProviderDraft,
    onDraftChange: (ProviderDraft) -> Unit,
    onTypeSelect: (ProviderType) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKey: () -> Unit,
) {
    EditorSection(title = stringResource(R.string.providers_section_connection)) {
        PresetPicker(selected = draft.type, onSelect = onTypeSelect)
        FormTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = stringResource(R.string.profile_name),
            helper = stringResource(R.string.providers_helper_name),
        )
        FormTextField(
            value = draft.baseUrl,
            onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
            label = stringResource(R.string.base_url),
            helper = stringResource(R.string.providers_helper_base_url),
            keyboardType = KeyboardType.Uri,
            technical = true,
        )
        FormTextField(
            value = draft.endpointPath,
            onValueChange = { onDraftChange(draft.copy(endpointPath = it)) },
            label = stringResource(R.string.endpoint_path),
            helper = stringResource(R.string.providers_helper_endpoint_path),
            keyboardType = KeyboardType.Uri,
            technical = true,
        )
        ProviderSecretField(
            value = draft.apiKey,
            onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
            label = stringResource(R.string.api_key),
            helper = stringResource(R.string.providers_helper_api_key),
            placeholder = stringResource(
                if (draft.id == null) R.string.api_key_placeholder else R.string.api_key_unchanged,
            ),
            visible = apiKeyVisible,
            onToggleVisibility = onToggleApiKey,
        )
        FormTextField(
            value = draft.organizationId,
            onValueChange = { onDraftChange(draft.copy(organizationId = it)) },
            label = stringResource(R.string.providers_label_organization),
            helper = stringResource(R.string.providers_helper_organization),
            technical = true,
        )
        OmniGroup {
            OmniSwitchRow(
                title = stringResource(R.string.enabled),
                subtitle = stringResource(R.string.providers_helper_enabled),
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun ModelSection(
    draft: ProviderDraft,
    onDraftChange: (ProviderDraft) -> Unit,
) {
    EditorSection(title = stringResource(R.string.providers_section_model)) {
        FormTextField(
            value = draft.modelId,
            onValueChange = { onDraftChange(draft.copy(modelId = it)) },
            label = stringResource(R.string.model_id),
            helper = stringResource(R.string.providers_helper_model),
            technical = true,
        )
        OmniGroup {
            OmniSwitchRow(
                title = stringResource(R.string.providers_switch_tools),
                subtitle = stringResource(R.string.providers_helper_tools),
                checked = draft.supportsTools,
                onCheckedChange = { onDraftChange(draft.copy(supportsTools = it)) },
            )
            OmniSwitchRow(
                title = stringResource(R.string.supports_vision),
                subtitle = stringResource(R.string.providers_helper_vision),
                checked = draft.supportsVision,
                onCheckedChange = { onDraftChange(draft.copy(supportsVision = it)) },
            )
            OmniSwitchRow(
                title = stringResource(R.string.supports_streaming),
                subtitle = stringResource(R.string.providers_helper_streaming),
                checked = draft.supportsStreaming,
                onCheckedChange = { onDraftChange(draft.copy(supportsStreaming = it)) },
                showDivider = false,
            )
        }
        Text(
            text = stringResource(if (draft.supportsTools) R.string.providers_tools_declared else R.string.providers_chat_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BehaviorSection(
    draft: ProviderDraft,
    onDraftChange: (ProviderDraft) -> Unit,
) {
    EditorSection(title = stringResource(R.string.providers_section_behavior)) {
        FormTextField(
            value = draft.temperature,
            onValueChange = { onDraftChange(draft.copy(temperature = it)) },
            label = stringResource(R.string.providers_label_temperature),
            helper = stringResource(R.string.providers_helper_temperature),
            keyboardType = KeyboardType.Decimal,
        )
        FormTextField(
            value = draft.maxTokens,
            onValueChange = { onDraftChange(draft.copy(maxTokens = it)) },
            label = stringResource(R.string.providers_label_max_tokens),
            helper = stringResource(R.string.providers_helper_max_tokens),
            keyboardType = KeyboardType.Number,
        )
        TokenParameterPicker(
            value = draft.tokenParameter,
            onSelect = { onDraftChange(draft.copy(tokenParameter = it)) },
        )
        FormTextField(
            value = draft.systemPrompt,
            onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
            label = stringResource(R.string.system_prompt),
            helper = stringResource(R.string.providers_helper_system_prompt),
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            imeAction = ImeAction.Default,
        )
    }
}

@Composable
private fun AdvancedSection(
    draft: ProviderDraft,
    onDraftChange: (ProviderDraft) -> Unit,
) {
    EditorSection(title = stringResource(R.string.providers_section_advanced)) {
        FormTextField(
            value = draft.timeoutSeconds,
            onValueChange = { onDraftChange(draft.copy(timeoutSeconds = it)) },
            label = stringResource(R.string.providers_label_timeout),
            helper = stringResource(R.string.providers_helper_timeout),
            keyboardType = KeyboardType.Number,
        )
        FormTextField(
            value = draft.customHeaders,
            onValueChange = { onDraftChange(draft.copy(customHeaders = it)) },
            label = stringResource(R.string.providers_label_custom_headers),
            helper = stringResource(R.string.providers_helper_custom_headers),
            placeholder = stringResource(R.string.custom_headers_hint),
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            imeAction = ImeAction.Default,
            technical = true,
            monospace = true,
        )
    }
}

/* ---------------------------------------------------------------- inputs */

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FormHelper(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helper: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    technical: Boolean = false,
    monospace: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
    ) {
        FormLabel(label)
        OmniTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textDirection = if (technical) TextDirection.Ltr else TextDirection.Content,
                fontFamily = if (monospace) {
                    FontFamily.Monospace
                } else {
                    MaterialTheme.typography.bodyLarge.fontFamily
                },
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        )
        FormHelper(helper)
    }
}

@Composable
private fun ProviderSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helper: String,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OmniRadius.medium)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
    ) {
        FormLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = scheme.onSurface,
                fontFamily = FontFamily.Monospace,
                textDirection = TextDirection.Ltr,
            ),
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(shape)
                        .background(scheme.surfaceContainerLow)
                        .border(1.dp, scheme.outlineVariant, shape)
                        .padding(start = OmniSpacing.md, end = OmniSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
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
                            imageVector = if (visible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = stringResource(
                                if (visible) R.string.providers_hide_api_key else R.string.providers_show_api_key,
                            ),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
        FormHelper(helper)
    }
}

@Composable
private fun TokenParameterPicker(
    value: TokenParameter,
    onSelect: (TokenParameter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OmniRadius.medium)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
    ) {
        FormLabel(stringResource(R.string.token_parameter))
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable(role = Role.Button) { expanded = true },
                shape = shape,
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    Text(
                        text = tokenParameterLabel(value),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(OmniRadius.medium),
                containerColor = scheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                TokenParameter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(tokenParameterLabel(option)) },
                        onClick = {
                            expanded = false
                            if (option != value) onSelect(option)
                        },
                    )
                }
            }
        }
        FormHelper(stringResource(R.string.providers_helper_token_parameter))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetPicker(
    selected: ProviderType,
    onSelect: (ProviderType) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
    ) {
        FormLabel(stringResource(R.string.providers_label_type))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            ProviderType.entries.forEach { type ->
                PresetChip(
                    label = providerTypeLabel(type),
                    selected = type == selected,
                    onClick = { if (type != selected) onSelect(type) },
                )
            }
        }
        FormHelper(stringResource(R.string.providers_helper_type))
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(OmniRadius.medium)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/* ------------------------------------------------------------- utilities */

@Composable
private fun providerErrorBody(error: Throwable?): String = stringResource(
    when (error) {
        is AppError.Endpoint -> R.string.providers_error_endpoint
        is AppError.Validation -> R.string.providers_error_validation
        is AppError.Authentication, is AppError.Permission -> R.string.providers_error_auth
        is AppError.Network, is AppError.Tls -> R.string.providers_error_network
        is AppError.Timeout -> R.string.providers_error_timeout
        is AppError.RateLimit, is AppError.InsufficientCredit -> R.string.providers_error_quota
        is AppError.ModelUnavailable -> R.string.providers_error_model
        else -> R.string.providers_test_failed_body
    },
)

@Composable
private fun providerTypeLabel(type: ProviderType): String = stringResource(
    when (type) {
        ProviderType.NVIDIA -> R.string.providers_type_nvidia
        ProviderType.OPENAI -> R.string.providers_type_openai
        ProviderType.GROQ -> R.string.providers_type_groq
        ProviderType.OPENROUTER -> R.string.providers_type_openrouter
        ProviderType.GEMINI -> R.string.providers_type_gemini
        ProviderType.OLLAMA -> R.string.providers_type_ollama
        ProviderType.VLLM -> R.string.providers_type_vllm
        ProviderType.CUSTOM -> R.string.providers_type_custom
    },
)

@Composable
private fun tokenParameterLabel(parameter: TokenParameter): String = stringResource(
    when (parameter) {
        TokenParameter.AUTO -> R.string.token_auto
        TokenParameter.MAX_TOKENS -> R.string.token_max_tokens
        TokenParameter.MAX_COMPLETION_TOKENS -> R.string.token_max_completion
    },
)
