package com.omni.aiassistant.device.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import com.omni.aiassistant.core.agent.containsWakePhrase
import com.omni.aiassistant.core.agent.normalizeWakePhrase
import com.omni.aiassistant.core.wake.WakeCommandBus
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.AndroidTtsController
import com.omni.aiassistant.domain.model.AppLanguage
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The wake-word listener.
 *
 * The user's request: the phone is in the pocket, the screen is off, and they
 * say "Arman" - Arman wakes up, says "I am ready for orders", and listens for
 * the actual command.
 *
 * How it works:
 * - a foreground service of type `microphone` keeps the microphone open even
 *   when the screen is off, which is the only way Android allows it;
 * - a `SpeechRecognizer` session runs in a loop and reports partial results;
 * - the partial text is checked against the wake phrase; when it matches, the
 *   service speaks the confirmation and treats the *next* utterance as the
 *   command;
 * - the command is handed to the shell through [WakeCommandBus].
 *
 * Privacy: nothing is recorded, stored or uploaded. Only the live stream of
 * the on-device recognizer is read.
 */
class ArmanWakeService : Service() {

    private val settings: SettingsRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var tts: AndroidTtsController? = null
    private val recognition = WakeRecognitionSession()
    /** The service is alive and keeps listening for the wake word. */
    private var running = false
    private var wakePhrase: String = DEFAULT_WAKE_PHRASE
    private var languageTag: String = "fa-IR"
    private var appLanguage: AppLanguage = AppLanguage.PERSIAN
    private var pendingRestart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = AndroidTtsController(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        scope.launch {
            val snapshot = settings.snapshot()
            wakePhrase = snapshot.wakeWordPhrase.ifBlank { DEFAULT_WAKE_PHRASE }
            languageTag = snapshot.voiceLanguageTag.ifBlank { "fa-IR" }
            appLanguage = snapshot.language
            if (!snapshot.wakeWordEnabled) {
                running = false
                stopSelf()
                return@launch
            }
            running = true
            ensureRecognizer()
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        recognition.reset()
        running = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* Recognition                                                         */
    /* ------------------------------------------------------------------ */

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val created = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(applicationContext)
            }
        }.getOrNull()
        if (created == null) {
            // The recogniser is busy (another screen is using it). Try again
            // instead of giving up: the service must keep waiting for the
            // wake word.
            scheduleRestart()
            return
        }
        created.setRecognitionListener(listener)
        recognizer = created
    }

    private fun startListening() {
        if (!running) return
        if (recognizer == null) {
            ensureRecognizer()
            if (recognizer == null) {
                scheduleRestart()
                return
            }
        }
        val engine = recognizer ?: return
        if (!recognition.tryStartListening()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            recognition.recognitionFinished()
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (pendingRestart) return
        pendingRestart = true
        scope.launch {
            kotlinx.coroutines.delay(RESTART_DELAY_MS)
            pendingRestart = false
            if (!running) {
                stopSelf()
                return@launch
            }
            startListening()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            handleText(partialResults, isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            recognition.recognitionFinished()
            handleText(results, isFinal = true)
            scheduleRestart()
        }

        override fun onError(error: Int) {
            recognition.recognitionFinished()
            when (error) {
                // The recogniser could not be created or could not reach its
                // service. Retrying is the only sensible answer: the phone
                // may get the pack back, or the user may open the app later.
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    scheduleRestart()

                // The language pack is missing for good. Stopping is better
                // than burning the battery, and the user is told why.
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                    running = false
                    recognition.reset()
                    notifyStatus(getString(R.string.wake_status_unavailable))
                    stopSelf()
                }

                // Nothing was heard: that is normal, keep listening.
                else -> scheduleRestart()
            }
        }
    }

    private fun handleText(bundle: Bundle?, isFinal: Boolean) {
        val spoken = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?: return
        when (val event = recognition.accept(spoken, wakePhrase, isFinal)) {
            WakeRecognitionEvent.Activated -> {
                speakReady()
                notifyStatus(getString(R.string.wake_status_listening))
            }
            is WakeRecognitionEvent.Command -> {
                notifyStatus(getString(R.string.wake_status_idle))
                WakeCommandBus.publish(event.text)
                openApp()
            }
            null -> Unit
        }
    }

    private fun speakReady() {
        val controller = tts ?: return
        val message = if (appLanguage == AppLanguage.PERSIAN) {
            getString(R.string.wake_ready_persian)
        } else {
            getString(R.string.wake_ready_english)
        }
        controller.speak(message, languageTag, null, 1f, 1f)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    /* ------------------------------------------------------------------ */
    /* Notification                                                        */
    /* ------------------------------------------------------------------ */

    private fun startForegroundSafely() {
        val notification = buildNotification(getString(R.string.wake_status_idle))
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Android 14+ refuses a microphone foreground service that was
            // started from the background. Failing softly is better than
            // crashing; the user can start it from the app.
            stopSelf()
        }
    }

    private fun buildNotification(status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ArmanWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification_wake)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.wake_stop), stopIntent)
            .build()
    }

    private fun notifyStatus(status: String) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            runCatching {
                manager.notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wake_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.omni.aiassistant.action.WAKE_START"
        const val ACTION_STOP = "com.omni.aiassistant.action.WAKE_STOP"
        const val DEFAULT_WAKE_PHRASE = "آرمان"

        private const val CHANNEL_ID = "omar_wake"
        private const val NOTIFICATION_ID = 4201
        private const val RESTART_DELAY_MS = 700L

        fun start(context: Context) {
            val intent = Intent(context, ArmanWakeService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ArmanWakeService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}

/** Recognition callbacks are incremental: only a final utterance may become an action. */
internal sealed interface WakeRecognitionEvent {
    data object Activated : WakeRecognitionEvent
    data class Command(val text: String) : WakeRecognitionEvent
}

internal class WakeRecognitionSession {
    private var listening = false
    private var armed = false

    fun tryStartListening(): Boolean {
        if (listening) return false
        listening = true
        return true
    }

    fun recognitionFinished() { listening = false }

    fun reset() {
        listening = false
        armed = false
    }

    fun accept(spoken: String, wakePhrase: String, isFinal: Boolean): WakeRecognitionEvent? {
        val text = spoken.trim()
        if (text.isEmpty()) return null
        if (!armed) {
            if (!containsWakePhrase(text, wakePhrase)) return null
            armed = true
            if (!isFinal || normalizeWakePhrase(text) == normalizeWakePhrase(wakePhrase)) {
                return WakeRecognitionEvent.Activated
            }
        }
        if (!isFinal || normalizeWakePhrase(text) == normalizeWakePhrase(wakePhrase)) return null
        armed = false
        return WakeRecognitionEvent.Command(text)
    }
}
