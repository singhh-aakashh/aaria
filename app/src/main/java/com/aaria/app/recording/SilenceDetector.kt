package com.aaria.app.recording

class SilenceDetector(
    private val silenceThresholdMs: Long = 1500L
) {

    var onSilenceDetected: (() -> Unit)? = null

    fun start() {
        // TODO Phase 3: init Silero VAD, feed audio frames, track silence duration
    }

    fun stop() {
        // TODO Phase 3: release VAD resources
    }
}
