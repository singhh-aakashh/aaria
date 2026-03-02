package com.aaria.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var ready = false
    private val queue = TtsQueue()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "IN")
            ready = true
            queue.drain { speak(it) }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            queue.add(text)
            return
        }

        val utteranceId = System.currentTimeMillis().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { onDone?.invoke() }
            override fun onError(id: String?) {}
        })

        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
