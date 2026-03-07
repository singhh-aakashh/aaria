package com.aaria.app.stt

import android.content.Context
import android.util.Log
import java.io.File

class SttManager(
    private val context: Context,
    private val whisperClient: WhisperClient,
) {

    /**
     * Returns [Result.success] with transcribed text, or [Result.failure] with the exception.
     */
    suspend fun transcribe(audioFile: File): Result<String> {
        return try {
            val text = whisperClient.transcribe(audioFile).text
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "On-device transcription failed — ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Human-readable failure reason spoken back to the user via TTS. */
    fun failureReason(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            e is IllegalStateException && msg.contains("missing") ->
                "Whisper model files are missing. Please add them to the assets folder and rebuild."
            msg.contains("onnx") || msg.contains("runtime") ->
                "On-device model error. Please restart the app."
            else -> "Transcription failed. Please try again."
        }
    }

    companion object {
        private const val TAG = "SttManager"
    }
}
