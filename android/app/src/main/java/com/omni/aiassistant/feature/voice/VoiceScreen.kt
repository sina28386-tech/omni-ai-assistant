package com.omni.aiassistant.feature.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.aiassistant.R
import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTextField
import com.omni.aiassistant.core.ui.OmniTextButton
import com.omni.aiassistant.core.ui.SectionLabel
import com.omni.aiassistant.core.ui.StatusPill
import com.omni.aiassistant.core.ui.OmniStatusTone
import org.koin.androidx.compose.koinViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun VoiceScreen(
    onOpenConversation: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel: VoiceViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    var listening by remember { mutableStateOf(false) }
    var permissionBlocked by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var recognitionUnavailable by remember { mutableStateOf(false) }
    var speechErrorRes by remember { mutableStateOf<Int?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val stopRequested = remember { mutableStateOf(false) }
    val autoSendVoice by rememberUpdatedState(state.settings.autoSendVoice)

    fun stopListening() {
        stopRequested.value = true
        recognizer?.cancel()
        listening = false
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            recognitionUnavailable = true
            speechErrorRes = null
            listening = false
            return
        }
        recognitionUnavailable = false
        permissionDenied = false
        permissionBlocked = false
        speechErrorRes = null
        stopRequested.value = false

        val current = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
            ?.also { recognizer = it }
        if (current == null) {
            recognitionUnavailable = true
            listening = false
            return
        }
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                speechErrorRes = null
            }

            override fun onBeginningOfSpeech() {
                listening = true
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                if (stopRequested.value) {
                    stopRequested.value = false
                    return
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    permissionDenied = true
                    permissionBlocked = activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
                    } ?: true
                    speechErrorRes = null
                    return
                }
                speechErrorRes = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> R.string.voice_no_match
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_speech
                    SpeechRecognizer.ERROR_NETWORK -> R.string.error_network
                    else -> R.string.voice_error_body
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                if (stopRequested.value) {
                    stopRequested.value = false
                    return
                }
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    viewModel.setRecognizedText(text)
                    if (autoSendVoice) viewModel.submit()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) viewModel.setRecognizedText(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, state.settings.voiceLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        runCatching { current.startListening(intent) }.onFailure {
            listening = false
            speechErrorRes = R.string.voice_error_body
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionBlocked = false
            permissionDenied = false
            startListening()
        } else {
            permissionDenied = true
            permissionBlocked = activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: true
        }
    }

    fun requestOrStartListening() {
        speechErrorRes = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionBlocked = false
            permissionDenied = false
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    fun openSystemVoiceSettings() {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching { openAppSettings() }
    }

    fun openSpeechSettings() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val voiceInput = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            if (runCatching { context.startActivity(voiceInput) }.isSuccess) return
        }
        openSystemVoiceSettings()
    }

    fun send() {
        if (listening) stopListening()
        viewModel.submit()
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }

    val openConversation by rememberUpdatedState(onOpenConversation)
    LaunchedEffect(state.pendingCommand) {
        state.pendingCommand?.let { command ->
            openConversation(command.conversationId, command.text)
            viewModel.consumeCommand()
        }
    }

    val languageLabel = when {
        state.settings.voiceLanguageTag.startsWith("fa", ignoreCase = true) ->
            stringResource(R.string.language_persian)
        state.settings.voiceLanguageTag.startsWith("en", ignoreCase = true) ->
            stringResource(R.string.language_english)
        state.settings.voiceLanguageTag.isBlank() -> stringResource(R.string.not_set)
        else -> state.settings.voiceLanguageTag
    }
    val idleState = stringResource(R.string.voice_state_idle)
    val listeningState = stringResource(R.string.voice_state_listening)
    val transcriptLabel = stringResource(R.string.voice_transcript)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OmniSpacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = {
                stopListening()
                onClose()
            }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
            }
        }

        StatusPill(
            text = stringResource(R.string.voice_input_language_chip, languageLabel),
            tone = OmniStatusTone.NEUTRAL,
        )

        Spacer(Modifier.height(OmniSpacing.md))
        Text(
            text = stringResource(R.string.voice_agent_handoff_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (state.settings.autoSendVoice) {
            Spacer(Modifier.height(OmniSpacing.xs))
            Text(
                text = stringResource(R.string.voice_auto_send_on),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(OmniSpacing.xxl))

        VoiceWaveform(
            listening = listening,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        )

        Spacer(Modifier.height(OmniSpacing.lg))

        FilledIconButton(
            onClick = {
                if (listening) {
                    stopListening()
                } else {
                    requestOrStartListening()
                }
            },
            modifier = Modifier
                .size(72.dp)
                .semantics {
                    stateDescription = if (listening) listeningState else idleState
                },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                contentDescription = stringResource(
                    if (listening) R.string.voice_stop_listening else R.string.voice_start_listening,
                ),
                modifier = Modifier.size(30.dp),
            )
        }

        Spacer(Modifier.height(OmniSpacing.md))

        Text(
            text = stringResource(if (listening) R.string.listening else R.string.tap_to_speak),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        Spacer(Modifier.height(OmniSpacing.md))

        if (permissionDenied) {
            VoiceNotice(
                icon = Icons.Outlined.MicOff,
                title = stringResource(R.string.permission_denied),
                body = stringResource(
                    if (permissionBlocked) R.string.permission_open_settings else R.string.voice_permission_denied_body,
                ),
                tone = NoticeTone.WARNING,
                actionLabel = stringResource(
                    if (permissionBlocked) R.string.open_settings else R.string.voice_allow_microphone,
                ),
                onAction = {
                    if (permissionBlocked) {
                        openAppSettings()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }

        if (recognitionUnavailable) {
            VoiceNotice(
                icon = Icons.Outlined.GraphicEq,
                title = stringResource(R.string.speech_unavailable),
                body = stringResource(R.string.voice_speech_unavailable_body),
                tone = NoticeTone.WARNING,
                actionLabel = stringResource(R.string.open_settings),
                onAction = { openSpeechSettings() },
            )
        }

        speechErrorRes?.let { errorRes ->
            VoiceNotice(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.voice_error),
                body = stringResource(errorRes),
                tone = NoticeTone.ERROR,
                actionLabel = stringResource(R.string.retry),
                onAction = {
                    speechErrorRes = null
                    requestOrStartListening()
                },
            )
        }

        Spacer(Modifier.height(OmniSpacing.sm))

        SectionLabel(
            text = transcriptLabel,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(OmniSpacing.sm))

        OmniTextField(
            value = state.recognizedText,
            onValueChange = viewModel::setRecognizedText,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = transcriptLabel },
            placeholder = stringResource(R.string.speech_partial),
            singleLine = false,
            minLines = 2,
            maxLines = 6,
            enabled = !state.loading,
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(OmniSpacing.md))

        ReadAloudToggle(
            checked = state.settings.autoTts,
            onCheckedChange = viewModel::setReadAloud,
            enabled = !state.loading,
        )

        Spacer(Modifier.height(OmniSpacing.md))

        OmniSecondaryButton(
            text = stringResource(R.string.voice_agent_send),
            onClick = { send() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.recognizedText.isNotBlank() && !state.loading,
            icon = Icons.AutoMirrored.Outlined.Send,
        )

        Spacer(Modifier.height(OmniSpacing.xxl))

        state.error?.let { error ->
            VoiceNotice(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.voice_reply_failed),
                body = aiErrorMessage(error),
                tone = NoticeTone.ERROR,
                actionLabel = stringResource(R.string.retry),
                onAction = {
                    viewModel.clearError()
                    viewModel.submit()
                },
                onDismiss = viewModel::clearError,
            )
        }

        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = OmniSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.voice_waiting_response),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Spacer(Modifier.height(OmniSpacing.xxxl))
    }
}

@Composable
private fun ReadAloudToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.voice_read_aloud),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun VoiceWaveform(
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = if (listening) {
        val transition = rememberInfiniteTransition(label = "voiceWaveform")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "voiceWavePhase",
        )
        animated
    } else {
        0f
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val bars = 33
        val gap = size.width / (bars * 3.2f)
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val centerY = size.height / 2f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        for (index in 0 until bars) {
            val distance = abs(index - (bars - 1) / 2f) / ((bars - 1) / 2f)
            val envelope = 1f - distance * 0.72f
            val wave = if (listening) {
                sin(phase + index * 0.62f) * 0.5f + 0.5f
            } else {
                0.22f + 0.12f * (1f - distance)
            }
            val barHeight = size.height * (0.10f + 0.78f * wave * envelope)
            drawRoundRect(
                color = if (listening) activeColor else idleColor.copy(alpha = 0.35f),
                topLeft = Offset(index * (barWidth + gap), centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

private enum class NoticeTone { WARNING, ERROR }

@Composable
private fun VoiceNotice(
    icon: ImageVector,
    title: String,
    body: String,
    tone: NoticeTone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val accent = when (tone) {
        NoticeTone.WARNING -> MaterialTheme.colorScheme.tertiary
        NoticeTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Hairline()
        Spacer(Modifier.height(OmniSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(OmniSpacing.xxs))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) {
                Text(actionLabel)
            }
        }
        Spacer(Modifier.height(OmniSpacing.sm))
    }
}

@Composable
private fun aiErrorMessage(error: Throwable): String = stringResource(
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
