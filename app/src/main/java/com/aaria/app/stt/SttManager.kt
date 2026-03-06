package com.aaria.app.stt

import android.content.Context
import android.util.Log
import retrofit2.HttpException
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SttManager(
    private val context: Context,
    private val whisperClient: WhisperClient
) {

    /**
     * Returns [Result.success] with transcribed text, or [Result.failure] with the exception.
     * Caller can use this to show a specific error (e.g. "Invalid API key" vs "Network error").
     */
    suspend fun transcribe(audioFile: File): Result<String> {
        return try {
            val text = whisperClient.transcribe(audioFile).text
            Result.success(text)
        } catch (e: Exception) {
            val body = if (e is HttpException) {
                runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            } else null
            Log.e(TAG, "Whisper transcription failed — ${e.javaClass.simpleName}: ${e.message} | body: $body", e)
            Result.failure(e)
        }
    }

    /** Human-readable reason for the user (for TTS). */
    fun failureReason(e: Throwable): String {
        if (e is HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string()?.lowercase() }.getOrNull() ?: ""
            return when (e.code()) {
                401 -> "Invalid OpenAI API key. Please check your key and rebuild."
                400 -> when {
                    body.contains("too short") || body.contains("duration") ->
                        "Recording too short. Please speak for at least one second."
                    body.contains("invalid") || body.contains("format") ->
                        "Audio format error. Please try again."
                    else -> "Whisper rejected the audio. Please try again."
                }
                413 -> "Recording too long. Try a shorter message."
                429 -> "OpenAI rate limit reached. Please wait a moment and try again."
                500, 503 -> "OpenAI server error. Please try again shortly."
                else -> "Whisper error ${e.code()}. Please try again."
            }
        }
        return when (e) {
            is UnknownHostException -> "No internet connection. Check your network."
            is SocketTimeoutException -> "Request timed out. Check your connection and try again."
            else -> {
                val msg = e.message?.lowercase() ?: ""
                when {
                    msg.contains("401") || msg.contains("unauthorized") ->
                        "Invalid OpenAI API key. Please check your key and rebuild."
                    msg.contains("timeout") || msg.contains("connection") ->
                        "Network error. Check your internet connection."
                    else -> "Transcription failed. Please try again."
                }
            }
        }
    }

    companion object {
        private const val TAG = "SttManager"
    }
}
