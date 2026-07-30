package dev.pesnik.roro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TextToSpeechHelper(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String) {
        if (!ready) return
        tts.language = Locale.getDefault()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roro-reply")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
