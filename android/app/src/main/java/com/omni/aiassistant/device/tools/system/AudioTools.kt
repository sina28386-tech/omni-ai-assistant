package com.omni.aiassistant.device.tools.system

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import com.omni.aiassistant.core.agent.ToolArgs.clamp
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal class VolumeSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "volume_set",
        description = "Set one audio stream's volume as a percentage, without opening the system UI. " +
            "The allowed streams are media, ring, alarm and notification.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "stream" to ToolSchema.string(
                    "Audio stream to change",
                    choices = listOf("media", "ring", "alarm", "notification"),
                ),
                "percent" to ToolSchema.integer("Target volume percentage", minimum = 0, maximum = 100),
            ),
            required = listOf("stream", "percent"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val streamName = arguments.string("stream")?.lowercase(Locale.US)
            ?: return ToolOutcome.Failure("stream is required")
        val streamId = when (streamName) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return ToolOutcome.Failure("stream must be media, ring, alarm or notification")
        }
        val percent = clamp(arguments.requireIntArg("percent"), 0, 100)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolOutcome.Failure("The audio service is unavailable")

        val max = audio.getStreamMaxVolume(streamId)
        if (max <= 0) return ToolOutcome.Failure("The $streamName stream is unavailable")
        val index = (percent / 100.0 * max).roundToInt().coerceIn(0, max)
        audio.setStreamVolume(streamId, index, 0)
        val actual = (audio.getStreamVolume(streamId) * 100.0 / max).roundToInt().coerceIn(0, 100)

        return ToolOutcome.Success(
            summary = "${streamName.replaceFirstChar { it.uppercase() }} volume set to $actual%",
            data = buildJsonObject {
                put("stream", streamName)
                put("percent", actual)
                put("index", index)
                put("maxIndex", max)
            },
        )
    }
}

internal class RingerSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    securityCapability = Capability.DND,
    capabilityLabel = "Allow Do Not Disturb access to silence the phone",
) {

    override val spec = ToolSpec(
        name = "ringer_set",
        description = "Set the ringer to silent, vibrate or normal. Silent mode requires Do Not " +
            "Disturb access on Android 6 and newer; the system may still block it under a policy.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "mode" to ToolSchema.string(
                    "Ringer mode",
                    choices = listOf("silent", "vibrate", "normal"),
                ),
            ),
            required = listOf("mode"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val mode = arguments.string("mode")?.lowercase(Locale.US)
            ?: return ToolOutcome.Failure("mode is required")
        val target = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            else -> return ToolOutcome.Failure("mode must be silent, vibrate or normal")
        }
        val dndGranted = runCatching { capabilities.isGranted(Capability.DND) }.getOrDefault(false)
        if (mode == "silent" && !dndGranted) {
            return ToolOutcome.NeedsPermission(
                Capability.DND.name,
                "Allow Do Not Disturb access to silence the phone",
            )
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolOutcome.Failure("The audio service is unavailable")

        audio.ringerMode = target
        val actual = audio.ringerMode
        if (actual != target) {
            return if (mode == "silent") {
                ToolOutcome.NeedsPermission(
                    Capability.DND.name,
                    "The system blocked silent mode; allow Do Not Disturb access",
                )
            } else {
                ToolOutcome.Failure("The system did not change the ringer mode")
            }
        }

        return ToolOutcome.Success(
            summary = "Ringer mode set to $mode",
            data = buildJsonObject {
                put("mode", mode)
                put("actual", ringerModeName(actual))
            },
        )
    }
}

internal class MediaControlTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "media_control",
        description = "Control playback: play/pause, next, previous or stop. When the assistant " +
            "started the music it controls that playback directly; otherwise it sends the media " +
            "button to whichever app is playing.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "action" to ToolSchema.string(
                    "Media action",
                    choices = listOf("play_pause", "next", "previous", "stop"),
                ),
            ),
            required = listOf("action"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val action = arguments.string("action")?.lowercase(Locale.US)
            ?: return ToolOutcome.Failure("action is required")
        if (action !in setOf("play_pause", "next", "previous", "stop")) {
            return ToolOutcome.Failure("action must be play_pause, next, previous or stop")
        }

        // The assistant's own player is controlled directly. The media keys
        // below only reach whatever app the system treats as the media button
        // session, which is not guaranteed for a player we started ourselves.
        val state = com.omni.aiassistant.device.media.MediaPlaybackService.state.value
        if (state.title.isNotBlank() || state.playing) {
            val serviceAction = when (action) {
                "play_pause" -> if (state.playing) {
                    com.omni.aiassistant.device.media.MediaPlaybackService.ACTION_PAUSE
                } else {
                    com.omni.aiassistant.device.media.MediaPlaybackService.ACTION_RESUME
                }
                "next" -> com.omni.aiassistant.device.media.MediaPlaybackService.ACTION_NEXT
                "previous" -> com.omni.aiassistant.device.media.MediaPlaybackService.ACTION_PREVIOUS
                else -> com.omni.aiassistant.device.media.MediaPlaybackService.ACTION_STOP
            }
            com.omni.aiassistant.device.media.MediaPlaybackService.command(context, serviceAction)
            val label = action.replace('_', ' ')
            return ToolOutcome.Success(
                summary = "Media $label applied to ${state.title}",
                data = buildJsonObject {
                    put("action", action)
                    put("target", "assistant")
                    put("title", state.title)
                },
            )
        }

        val keyCode = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolOutcome.Failure("The audio service is unavailable")

        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        val label = action.replace('_', ' ')
        return ToolOutcome.Success(
            summary = "Sent the media $label key",
            data = buildJsonObject {
                put("action", action)
                put("target", "system")
            },
        )
    }
}

internal class TtsSpeakTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "tts_speak",
        description = "Speak text aloud through the phone's text-to-speech engine. Text longer than " +
            "4000 characters is shortened. language is an optional BCP-47 tag such as en-US or fa-IR; " +
            "the device language is used when omitted.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "text" to ToolSchema.string("Text to speak, up to 4000 characters"),
                "language" to ToolSchema.string("Optional BCP-47 language tag, e.g. en-US"),
            ),
            required = listOf("text"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val text = arguments.requireTextArg("text").trim().take(MAX_TTS_CHARS)
        val requestedTag = (arguments.string("language") ?: Locale.getDefault().toLanguageTag())
            .trim()
            .take(35)
        val locale = Locale.forLanguageTag(requestedTag)
            .takeIf { it.language.isNotBlank() }
            ?: Locale.getDefault()

        val engine = createEngine()
            ?: return ToolOutcome.Failure("Text-to-speech is not available on this device")

        return try {
            val status = withContext(Dispatchers.Main) { engine.setLanguage(locale) }
            if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
                ToolOutcome.Failure("The speech engine does not support '$requestedTag'")
            } else {
                speakAndWait(engine, text, locale)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    private suspend fun speakAndWait(
        engine: TextToSpeech,
        text: String,
        locale: Locale,
    ): ToolOutcome {
        val done = CompletableDeferred<Boolean>()
        val utteranceId = "omni-system-${System.nanoTime()}"

        withContext(Dispatchers.Main) {
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        done.complete(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        done.complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        done.complete(false)
                    }
                },
            )
        }

        val started = withContext(Dispatchers.Main) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        }
        if (!started) return ToolOutcome.Failure("The speech engine could not start")

        val finished = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() } ?: false
        if (!finished) return ToolOutcome.Failure("Speech did not finish within 15 seconds")

        return ToolOutcome.Success(
            summary = "Spoke ${text.length} characters aloud",
            data = buildJsonObject {
                put("language", locale.toLanguageTag())
                put("characters", text.length)
            },
        )
    }

    private suspend fun createEngine(): TextToSpeech? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<TextToSpeech?> { continuation ->
            val holder = AtomicReference<TextToSpeech?>()
            val listener = TextToSpeech.OnInitListener { status ->
                val engine = holder.get()
                when {
                    !continuation.isActive -> runCatching { engine?.shutdown() }
                    status == TextToSpeech.SUCCESS && engine != null -> continuation.resume(engine)
                    else -> {
                        runCatching { engine?.shutdown() }
                        continuation.resume(null)
                    }
                }
            }
            holder.set(TextToSpeech(context.applicationContext, listener))
            continuation.invokeOnCancellation {
                runCatching { holder.getAndSet(null)?.shutdown() }
            }
        }
    }

    private companion object {
        const val MAX_TTS_CHARS = 4_000
        const val SPEAK_TIMEOUT_MS = 15_000L
    }
}
