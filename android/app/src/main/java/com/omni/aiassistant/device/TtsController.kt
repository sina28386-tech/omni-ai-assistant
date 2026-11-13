package com.omni.aiassistant.device

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

class AndroidTtsController(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    fun availableVoices(languageTag: String): List<Voice> {
        if (!ready) return emptyList()
        val locale = Locale.forLanguageTag(languageTag)
        return tts.voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .sortedBy { it.name }
    }

    fun speak(text: String, languageTag: String, voiceName: String?, speed: Float, pitch: Float): Boolean {
        if (!ready || text.isBlank()) return false
        val locale = Locale.forLanguageTag(languageTag)
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val voice = voiceName?.let { name -> tts.voices.orEmpty().firstOrNull { it.name == name } }
        if (voice != null) tts.voice = voice
        tts.setSpeechRate(speed.coerceIn(0.5f, 2f))
        tts.setPitch(pitch.coerceIn(0.5f, 2f))
        tts.stop()
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) == TextToSpeech.SUCCESS
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }
}
