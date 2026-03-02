package com.aaria.app.stt

import com.aaria.app.network.WhisperApi
import java.io.File

class WhisperClient(private val api: WhisperApi) {

    suspend fun transcribe(audioFile: File, languageHint: String = "hi"): TranscriptionResult {
        // TODO Phase 4: send audio to Whisper API with Hinglish prompt priming
        return TranscriptionResult("", 0L)
    }

    data class TranscriptionResult(
        val text: String,
        val durationMs: Long
    )
}
