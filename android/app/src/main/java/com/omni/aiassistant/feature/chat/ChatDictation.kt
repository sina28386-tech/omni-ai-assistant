package com.omni.aiassistant.feature.chat

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/** A single foreground dictation session. Android's chosen recognition service provides the words. */
internal class ChatDictation(
    private val context: Context,
    private val onListening: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start(languageTag: String): Boolean {
        if (active || !SpeechRecognizer.isRecognitionAvailable(context)) return false
        val service = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return false
        recognizer = service
        active = true
        onListening(true)
        service.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (active && recognizer === service) onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onResults(results: Bundle?) {
                if (!active || recognizer !== service) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish()
                if (text.isNotBlank()) onFinal(text)
            }
            override fun onError(error: Int) {
                if (!active || recognizer !== service) return
                finish()
                onFailure(error)
            }
        })
        return runCatching { service.startListening(recognizerIntent(languageTag)); true }.getOrElse {
            finish(); false
        }
    }

    fun stop() { if (active) runCatching { recognizer?.stopListening() }.onFailure { finish() } }
    fun close() { active = false; runCatching { recognizer?.cancel() }; recognizer?.destroy(); recognizer = null }
    private fun finish() { active = false; onListening(false); onPartial(""); recognizer?.destroy(); recognizer = null }
}

internal fun appendDictation(draft: String, spoken: String): String {
    val words = spoken.trim()
    if (words.isEmpty()) return draft
    val existing = draft.trimEnd()
    return if (existing.isEmpty()) words else "$existing $words"
}
