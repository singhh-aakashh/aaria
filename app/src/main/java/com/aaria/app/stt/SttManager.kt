package com.aaria.app.stt

import android.content.Context
import java.io.File

class SttManager(
    private val context: Context,
    private val whisperClient: WhisperClient
) {

    suspend fun transcribe(audioFile: File): String {
        return try {
            whisperClient.transcribe(audioFile).text
        } catch (e: Exception) {
            transcribeOffline(audioFile)
        }
    }

    private fun transcribeOffline(audioFile: File): String {
        // TODO Phase 4: Android SpeechRecognizer offline fallback
        return ""
    }
}
