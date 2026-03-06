package com.aaria.app.stt

import android.util.Log
import com.aaria.app.network.WhisperApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class WhisperClient(private val api: WhisperApi) {

    suspend fun transcribe(audioFile: File, languageHint: String = "hi"): TranscriptionResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val filePart = MultipartBody.Part.createFormData(
            "file",
            audioFile.name,
            audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        )
        val modelPart = "whisper-1".toRequestBody("text/plain".toMediaTypeOrNull())
        val promptPart = HINGLISH_PROMPT.toRequestBody("text/plain".toMediaTypeOrNull())
        val response = api.transcribe(
            file = filePart,
            model = modelPart,
            language = null,
            prompt = promptPart
        )
        val text = response.text.trim()
        val durationMs = System.currentTimeMillis() - start
        Log.i(TAG, "Whisper transcription: ${text.take(100)}... (${durationMs}ms)")
        TranscriptionResult(text, durationMs)
    }

    data class TranscriptionResult(
        val text: String,
        val durationMs: Long
    )

    companion object {
        private const val TAG = "WhisperClient"
        private const val HINGLISH_PROMPT =
            "Hinglish conversation, Roman script Hindi mixed with English, casual WhatsApp style."
    }
}
