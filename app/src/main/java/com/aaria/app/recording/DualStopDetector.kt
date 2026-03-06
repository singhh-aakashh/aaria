package com.aaria.app.recording

import com.aaria.app.wakeword.WakeWordEngine
import java.io.File

class DualStopDetector(
    private val silenceDetector: SilenceDetector,
    private val wakeWordEngine: WakeWordEngine
) {

    var onStopDetected: ((StopReason) -> Unit)? = null

    enum class StopReason { SILENCE, STOP_WORD }

    /**
     * Start recording to [outputFile] (WAV) and listening for silence or stop word.
     * First to fire wins and [onStopDetected] is invoked; then [stop] is called.
     */
    fun start(outputFile: File) {
        silenceDetector.onSilenceDetected = {
            onStopDetected?.invoke(StopReason.SILENCE)
            wakeWordEngine.finishRecording()
            stop()
        }

        wakeWordEngine.onStopWordDetected = {
            onStopDetected?.invoke(StopReason.STOP_WORD)
            wakeWordEngine.finishRecording()
            stop()
        }

        silenceDetector.start()
        wakeWordEngine.startListeningForStop(outputFile)
    }

    fun stop() {
        silenceDetector.stop()
        wakeWordEngine.stop()
    }
}
