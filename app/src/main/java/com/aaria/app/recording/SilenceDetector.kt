package com.aaria.app.recording

import android.util.Log
import kotlin.math.sqrt

/**
 * Energy-based voice activity detection. Receives PCM frames via [feed];
 * when continuous silence exceeds [silenceThresholdMs], invokes [onSilenceDetected].
 *
 * Uses RMS (root mean square) of each frame; below [silenceThresholdAmplitude]
 * counts as silence. Tuned for 16 kHz mono 16-bit PCM (Porcupine frame size).
 */
class SilenceDetector(
    private val sampleRateHz: Int = 16000,
    private val silenceThresholdMs: Long = 1500L,
    /** RMS below this is considered silence. Tune per device if needed. */
    private val silenceThresholdAmplitude: Double = 500.0
) {

    var onSilenceDetected: (() -> Unit)? = null

    private var silenceStartMs: Long = -1L
    private var running = false

    fun start() {
        silenceStartMs = -1L
        running = true
        Log.d(TAG, "SilenceDetector started (threshold ${silenceThresholdMs}ms)")
    }

    fun stop() {
        running = false
        Log.d(TAG, "SilenceDetector stopped")
    }

    /**
     * Feed one frame of PCM (e.g. 512 samples at 16 kHz). Call from the same thread
     * that drives the audio loop to avoid races.
     */
    fun feed(pcm: ShortArray) {
        if (!running || pcm.isEmpty()) return

        val rms = computeRms(pcm)

        if (rms < silenceThresholdAmplitude) {
            if (silenceStartMs < 0) silenceStartMs = System.currentTimeMillis()
            val elapsed = System.currentTimeMillis() - silenceStartMs
            if (elapsed >= silenceThresholdMs) {
                Log.i("Aaria", "Silence detected (${elapsed}ms) — stopping recording")
                Log.d(TAG, "Silence detected (${elapsed}ms)")
                running = false
                onSilenceDetected?.invoke()
            }
        } else {
            silenceStartMs = -1L
        }
    }

    private fun computeRms(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) {
            val n = s.toDouble() / 32768.0
            sum += n * n
        }
        return sqrt(sum / samples.size) * 32768.0
    }

    companion object {
        private const val TAG = "SilenceDetector"
    }
}
