package com.omni.aiassistant.feature.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.device.FileRecord
import com.omni.aiassistant.core.ui.AttachmentImage
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.MarkdownMessage
import com.omni.aiassistant.core.ui.OmniPrimaryButton
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniStatusTone
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusDot
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.device.AndroidTtsController
import com.omni.aiassistant.device.DeviceAction
import com.omni.aiassistant.device.DeviceActionExecutor
import com.omni.aiassistant.device.DeviceInfoProvider
import com.omni.aiassistant.device.FileOutcome
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
import com.omni.aiassistant.domain.model.PreparedAttachment
import com.omni.aiassistant.domain.model.ProviderProfile
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    initialDraft: String = "",
    onOpenProviders: () -> Unit = {},
    autoSend: Boolean = false,
    viewModel: ChatViewModel = koinViewModel(parameters = { parametersOf(conversationId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val executor: DeviceActionExecutor = koinInject()
    val deviceInfo: DeviceInfoProvider = koinInject()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var providerMenu by remember { mutableStateOf(false) }
    var promptDialog by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var deviceInfoText by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var cameraTemp by remember { mutableStateOf<File?>(null) }
    val tts = remember { AndroidTtsController(context) }
    var autoSpokenId by remember { mutableStateOf<String?>(null) }
    var autoSpeechInitialized by remember { mutableStateOf(false) }
    var followOutput by remember { mutableStateOf(true) }
    var userDragPending by remember { mutableStateOf(false) }
    val noAppForActionMessage = stringResource(R.string.no_app_for_action)
    val ttsFailedMessage = stringResource(R.string.chat_notice_tts_failed)
    val micDeniedMessage = stringResource(R.string.chat_mic_permission_denied)
    val voiceUnavailableMessage = stringResource(R.string.chat_voice_unavailable)
    val voiceNoMatch = stringResource(R.string.voice_no_match)
    val voiceNetworkError = stringResource(R.string.error_network)
    val voiceLanguageUnavailable = stringResource(R.string.chat_dictation_language_unavailable)
    var voiceListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceLanguageOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val voiceLanguage = voiceLanguageOverride ?: state.settings.voiceLanguageTag.ifBlank { "fa-IR" }
    val dictation = remember(context, conversationId) {
        ChatDictation(
            context = context,
            onListening = { voiceListening = it },
            onPartial = { voicePartial = it.take(2000) },
            onFinal = { text -> viewModel.appendVoiceInput(text); composerFocus.requestFocus(); keyboard?.show() },
            onFailure = { error ->
                localNotice = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> micDeniedMessage
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> voiceNetworkError
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> voiceNoMatch
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> voiceLanguageUnavailable
                    else -> voiceUnavailableMessage
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { dictation.close(); tts.shutdown() }
    }

    LaunchedEffect(initialDraft) {
        if (initialDraft.isNotBlank()) viewModel.setDraft(initialDraft)
    }

    // A command that arrived from the wake word is sent right away, exactly
    // like the user pressing the send button.
    var autoSendTriggered by rememberSaveable(conversationId) { mutableStateOf(false) }
    LaunchedEffect(autoSend, initialDraft, state.draft) {
        if (autoSend && !autoSendTriggered && state.draft.isNotBlank()) {
            autoSendTriggered = true
            viewModel.send()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        uris.take((4 - state.pendingAttachments.size).coerceAtLeast(0))
            .forEach { viewModel.addImage(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val temp = cameraTemp
        if (success && temp != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", temp)
            viewModel.addImage(uri) { temp.delete() }
        } else {
            temp?.delete()
        }
        cameraTemp = null
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (!dictation.start(voiceLanguage)) localNotice = voiceUnavailableMessage
        } else {
            localNotice = micDeniedMessage
        }
    }
    val startVoice: () -> Unit = {
        when {
            voiceListening -> dictation.stop()
            !SpeechRecognizer.isRecognitionAvailable(context) ->
                localNotice = voiceUnavailableMessage
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED ->
                if (!dictation.start(voiceLanguage)) localNotice = voiceUnavailableMessage
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val showToolActivity = state.settings.showToolActivity
    val timeline = state.messages.filter { message ->
        shouldRenderMessage(message, showToolActivity)
    }
    val toolCallsById = remember(state.messages) {
        buildMap {
            state.messages.forEach { message ->
                message.toolCalls.forEach { call -> put(call.id, call) }
            }
        }
    }

    // Follow the stream only while the reader is at the bottom. Only a real
    // drag releases the lock; programmatic auto-scrolls must not turn it off.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userDragPending = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                if (userDragPending) {
                    userDragPending = false
                    followOutput = listState.isAtBottom()
                }
            }
    }
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            if (state.messages.lastOrNull()?.role == MessageRole.USER) followOutput = true
            if (followOutput) listState.snapToBottom(timeline.lastIndex)
        }
    }
    LaunchedEffect(state.liveText) {
        if (
            state.generating &&
            state.liveText.isNotEmpty() &&
            followOutput &&
            !listState.isScrollInProgress
        ) {
            listState.scrollBy(Float.MAX_VALUE)
        }
    }
    LaunchedEffect(state.generating) {
        if (!state.generating && timeline.isNotEmpty() && followOutput) {
            listState.snapToBottom(timeline.lastIndex)
        }
    }
    LaunchedEffect(state.activeTool) {
        if (state.generating && followOutput && timeline.isNotEmpty()) {
            listState.scrollBy(Float.MAX_VALUE)
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && timeline.isNotEmpty()) {
            followOutput = true
            listState.snapToBottom(timeline.lastIndex)
        }
    }

    LaunchedEffect(state.messages, state.settings.autoTts) {
        val last = state.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT &&
                it.status == MessageStatus.COMPLETE &&
                it.content.isNotBlank()
        }
        if (!autoSpeechInitialized) {
            autoSpokenId = last?.id
            autoSpeechInitialized = true
        } else if (last != null && last.id != autoSpokenId) {
            autoSpokenId = last.id
            if (state.settings.autoTts) {
                tts.speak(
                    last.content,
                    state.settings.ttsLanguageTag,
                    state.settings.ttsVoiceName,
                    state.settings.ttsSpeed,
                    state.settings.ttsPitch,
                )
            }
        }
    }

    val speakText: (String) -> Unit = { text ->
        val spoken = tts.speak(
            text,
            state.settings.ttsLanguageTag,
            state.settings.ttsVoiceName,
            state.settings.ttsSpeed,
            state.settings.ttsPitch,
        )
        if (!spoken) localNotice = ttsFailedMessage
    }
    val lastReadable = state.messages.lastOrNull {
        it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
    }?.content ?: state.liveText.takeIf { it.isNotBlank() }
    val retryTarget = state.messages.lastOrNull()?.takeIf {
        it.role == MessageRole.ASSISTANT &&
            it.status == MessageStatus.FAILED &&
            !state.generating
    }
    val visibleFileOutcomes = state.fileOutcomes.filterNot { it is FileOutcome.DeleteConfirm }

    if (promptDialog) {
        SystemPromptDialog(
            conversation = state.conversation,
            onDismiss = { promptDialog = false },
            onSave = { prompt ->
                viewModel.updateConversationSystemPrompt(prompt)
                promptDialog = false
            },
        )
    }
    editMessage?.let { message ->
        EditMessageDialog(
            message = message,
            onDismiss = { editMessage = null },
            onSave = { value ->
                viewModel.editAndResend(message.id, value)
                editMessage = null
            },
        )
    }
    deleteMessage?.let { message ->
        DeleteMessageDialog(
            onDismiss = { deleteMessage = null },
            onDelete = {
                viewModel.deleteMessage(message.id)
                deleteMessage = null
            },
        )
    }
    state.pendingDelete?.let { candidate ->
        PendingDeleteDialog(
            candidate = candidate,
            onDismiss = viewModel::cancelPendingDelete,
            onConfirm = viewModel::confirmPendingDelete,
        )
    }
    if (visibleFileOutcomes.isNotEmpty() && state.pendingDelete == null) {
        FileOutcomesDialog(
            outcomes = visibleFileOutcomes,
            onPlay = { record -> viewModel.playExactFile(record) },
            onDismiss = viewModel::clearFileOutcomes,
        )
    }
    deviceInfoText?.let { report ->
        DeviceInfoDialog(report = report, onDismiss = { deviceInfoText = null })
    }
    if (state.pendingDeviceActions.isNotEmpty()) {
        DeviceActionsDialog(
            actions = state.pendingDeviceActions,
            onDismiss = viewModel::consumeDeviceActions,
            onContinue = {
                state.pendingDeviceActions.forEach { action ->
                    if (action == DeviceAction.DeviceInfo) {
                        deviceInfoText = deviceInfo.report()
                    } else {
                        executor.execute(action).onFailure {
                            localNotice = noAppForActionMessage
                        }
                    }
                }
                viewModel.consumeDeviceActions()
            },
        )
    }
    state.pendingConfirmation?.let { request ->
        ToolConfirmationSheet(request = request) { allow, always ->
            viewModel.resolveToolConfirmation(allow, always)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatTopBar(
            title = state.conversation?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.chat_title),
            provider = state.activeProvider,
            providers = state.providers,
            providerMenu = providerMenu,
            onProviderMenuChange = { providerMenu = it },
            onSelectProvider = viewModel::selectProvider,
            onManageProviders = {
                providerMenu = false
                onOpenProviders()
            },
            onBack = onBack,
            onOpenPrompt = { promptDialog = true },
            onReadLast = { lastReadable?.let(speakText) },
            onStopReading = { tts.stop() },
            canRead = lastReadable != null,
            autoTts = state.settings.autoTts,
        )
        if (state.settings.creditSaver) {
            CreditSaverLine(contextMessages = state.settings.maxContextMessages.coerceAtMost(4))
        }
        state.error?.let { error ->
            ChatErrorBanner(
                error = error,
                retryTargetId = retryTarget?.id,
                onRetry = { id -> viewModel.retryFailed(id) },
                onDismiss = viewModel::clearError,
                onOpenProviders = onOpenProviders,
            )
        }
        localNotice?.let { notice ->
            ChatNoticeBanner(
                title = stringResource(
                    if (notice == noAppForActionMessage) {
                        R.string.chat_notice_action_unavailable
                    } else {
                        R.string.chat_notice_title
                    },
                ),
                message = notice,
                onDismiss = { localNotice = null },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (timeline.isEmpty()) {
                EmptyChatState(
                    hasProvider = state.activeProvider != null,
                    onOpenProviderPicker = { providerMenu = true },
                    onPrompt = { prompt ->
                        viewModel.setDraft(prompt)
                        composerFocus.requestFocus()
                        keyboard?.show()
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 820.dp)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(
                        start = OmniSpacing.screenHorizontal,
                        end = OmniSpacing.screenHorizontal,
                        top = OmniSpacing.md,
                        bottom = OmniSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
                ) {
                    items(
                        items = timeline,
                        key = { it.id },
                        contentType = { it.role.name },
                    ) { message ->
                        val displayContent = if (
                            message.id == state.liveAssistantId &&
                            state.liveText.isNotEmpty()
                        ) {
                            state.liveText
                        } else {
                            message.content
                        }
                        val call = message.toolCallId?.let(toolCallsById::get)
                        val isLastMessage = message.id == state.messages.lastOrNull()?.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (message.role == MessageRole.TOOL) 0.dp else OmniSpacing.md),
                        ) {
                            when (message.role) {
                                MessageRole.USER -> UserMessage(
                                    message = message,
                                    content = displayContent,
                                    onCopy = { copyText(context, displayContent) },
                                    onShare = { shareText(context, displayContent) },
                                    onEdit = if (!state.generating) {
                                        { editMessage = message }
                                    } else {
                                        null
                                    },
                                    onDelete = if (!state.generating) {
                                        { deleteMessage = message }
                                    } else {
                                        null
                                    },
                                )
                                MessageRole.ASSISTANT -> AssistantMessage(
                                    message = message,
                                    content = displayContent,
                                    streaming = state.generating &&
                                        state.liveAssistantId == message.id,
                                    isLastMessage = isLastMessage,
                                    onCopy = { copyText(context, displayContent) },
                                    onShare = { shareText(context, displayContent) },
                                    onSpeak = if (displayContent.isNotBlank()) {
                                        { speakText(displayContent) }
                                    } else {
                                        null
                                    },
                                    onRetry = if (
                                        message.status == MessageStatus.FAILED &&
                                        isLastMessage &&
                                        !state.generating
                                    ) {
                                        { viewModel.retryFailed(message.id) }
                                    } else {
                                        null
                                    },
                                    onRegenerate = if (
                                        isLastMessage &&
                                        message.status == MessageStatus.COMPLETE &&
                                        !state.generating
                                    ) {
                                        viewModel::regenerateLatest
                                    } else {
                                        null
                                    },
                                    onDelete = if (!state.generating) {
                                        { deleteMessage = message }
                                    } else {
                                        null
                                    },
                                )
                                MessageRole.TOOL -> ToolActivityCard(
                                    messageId = message.id,
                                    toolName = message.toolName ?: call?.name,
                                    payload = displayContent,
                                    arguments = call?.arguments ?: JsonObject(emptyMap()),
                                )
                                MessageRole.SYSTEM -> Unit
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = timeline.isNotEmpty() && !followOutput,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = OmniSpacing.screenHorizontal, bottom = OmniSpacing.md),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        followOutput = true
                        scope.launch { listState.snapToBottom(timeline.lastIndex) }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            if (state.generating && (state.activeTool != null || state.agentRound > 0)) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LiveToolStrip(
                        toolName = state.activeTool,
                        step = state.agentRound,
                        onStop = viewModel::stopGeneration,
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            .fillMaxWidth()
                            .padding(horizontal = OmniSpacing.md, vertical = OmniSpacing.sm),
                    )
                }
            }
            Hairline()
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ChatComposer(
                    state = state,
                    focusRequester = composerFocus,
                    showProviderNotice = state.activeProvider == null && timeline.isNotEmpty(),
                    onDraft = viewModel::setDraft,
                    onSend = viewModel::send,
                    onStop = viewModel::stopGeneration,
                    onRemoveAttachment = viewModel::removePendingAttachment,
                    onVoice = startVoice,
                    voiceLanguage = voiceLanguage,
                    voiceListening = voiceListening,
                    voicePartial = voicePartial,
                    onVoiceLanguage = { tag -> voiceLanguageOverride = tag; viewModel.setVoiceLanguage(tag) },
                    onGallery = {
                        if (state.pendingAttachments.size < 4) {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onCamera = {
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File.createTempFile("capture_", ".jpg", dir)
                        cameraTemp = file
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraLauncher.launch(uri)
                    },
                    onOpenProviderPicker = { providerMenu = true },
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
