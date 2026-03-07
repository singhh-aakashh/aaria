package com.aaria.app.stt

import java.io.File

/**
 * Thin wrapper kept for naming continuity.
 * All transcription now happens on-device via [SherpaWhisperEngine] (sherpa-onnx).
 * No network call is made.
 */
class WhisperClient(private val engine: SherpaWhisperEngine) {

    suspend fun transcribe(audioFile: File): TranscriptionResult {
        val start = System.currentTimeMillis()
        val text = engine.transcribe(audioFile)
        return TranscriptionResult(text, System.currentTimeMillis() - start)
    }

    data class TranscriptionResult(
        val text: String,
        val durationMs: Long,
    )
}
