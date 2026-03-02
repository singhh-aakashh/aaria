package com.aaria.app.tts

import java.util.concurrent.ConcurrentLinkedQueue

class TtsQueue {

    private val pending = ConcurrentLinkedQueue<String>()

    fun add(text: String) {
        pending.add(text)
    }

    fun drain(action: (String) -> Unit) {
        while (pending.isNotEmpty()) {
            pending.poll()?.let(action)
        }
    }

    fun clear() {
        pending.clear()
    }
}
