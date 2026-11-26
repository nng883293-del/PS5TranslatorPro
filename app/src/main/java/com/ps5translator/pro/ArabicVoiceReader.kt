package com.ps5translator.pro

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class ArabicVoiceReader(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var isReady = false

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var speechRate: Float = 1.2f
        set(value) {
            field = value.coerceIn(0.8f, 1.8f)
            if (isReady) tts?.setSpeechRate(field)
        }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttrs)

        selectBestArabicVoice(engine)
        engine.setSpeechRate(speechRate)
        engine.setPitch(1.0f)
        isReady = true
    }

    private fun selectBestArabicVoice(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull()
        if (!voices.isNullOrEmpty()) {
            val arabicVoices = voices.filter {
                it.locale?.language.equals("ar", ignoreCase = true)
            }
            val preferred: Voice? =
                arabicVoices.firstOrNull {
                    it.locale.country.equals("IQ", ignoreCase = true) && !it.isNetworkConnectionRequired
                } ?: arabicVoices.firstOrNull {
                    it.locale.country.equals("IQ", ignoreCase = true)
                } ?: arabicVoices.firstOrNull {
                    !it.isNetworkConnectionRequired && it.quality >= Voice.QUALITY_HIGH
                } ?: arabicVoices.firstOrNull {
                    !it.isNetworkConnectionRequired
                } ?: arabicVoices.firstOrNull()

            if (preferred != null) {
                engine.voice = preferred
                return
            }
        }
        engine.language = Locale("ar")
    }

    fun speak(arabicText: String) {
        if (!enabled || !isReady || arabicText.isBlank()) return
        val clean = arabicText
            .replace(Regex("[\\[\\](){}*_\"']"), "")
            .replace("...", "، ")
            .trim()
        if (clean.isEmpty()) return

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sub_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
