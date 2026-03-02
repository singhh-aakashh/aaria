package com.aaria.app.recording

import com.aaria.app.wakeword.WakeWordEngine

class DualStopDetector(
    private val silenceDetector: SilenceDetector,
    private val wakeWordEngine: WakeWordEngine
) {

    var onStopDetected: ((StopReason) -> Unit)? = null

    enum class StopReason { SILENCE, STOP_WORD }

    fun start() {
        silenceDetector.onSilenceDetected = {
            onStopDetected?.invoke(StopReason.SILENCE)
            stop()
        }

        wakeWordEngine.onStopWordDetected = {
            onStopDetected?.invoke(StopReason.STOP_WORD)
            stop()
        }

        silenceDetector.start()
        wakeWordEngine.startListeningForStop()
    }

    fun stop() {
        silenceDetector.stop()
        wakeWordEngine.stop()
    }
}
