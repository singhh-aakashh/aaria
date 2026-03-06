package com.aaria.app.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aaria.app.BuildConfig
import com.aaria.app.recording.SilenceDetector
import com.aaria.app.recording.WavWriter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class WakeWordState {
    INACTIVE,
    LISTENING_FOR_REPLY,
    LISTENING_FOR_STOP,
    LISTENING_FOR_CANCEL
}

/**
 * Wake word ("Hey Aaria") and stop word ("Done") detection using Picovoice Porcupine.
 * Uses built-in keywords PORCUPINE (index 0) and TERMINATOR (index 1) for Phase 3.
 * Replace with custom .ppn from Picovoice Console for "Hey Aaria" / "Done" in production.
 *
 * Single AudioRecord thread: in [WakeWordState.LISTENING_FOR_REPLY] only Porcupine runs;
 * in [WakeWordState.LISTENING_FOR_STOP] each frame is also written to a WAV file and fed to [SilenceDetector].
 */
class WakeWordEngine(
    private val context: Context,
    private val silenceDetector: SilenceDetector
) {

    @Volatile
    private var _state: WakeWordState = WakeWordState.INACTIVE
    var state: WakeWordState
        get() = _state
        private set(value) { _state = value }

    var onWakeWordDetected: (() -> Unit)? = null
    var onStopWordDetected: (() -> Unit)? = null
    var onCancelDetected: (() -> Unit)? = null

    @Volatile private var wavOutputStream: WavWriter.WavOutputStream? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var porcupine: ai.picovoice.porcupine.Porcupine? = null
    private var audioRecord: AudioRecord? = null

    private val accessKey: String
        get() = BuildConfig.PICOVOICE_ACCESS_KEY.ifBlank { "" }

    fun startListeningForReply() {
        if (state != WakeWordState.INACTIVE) return
        if (accessKey.isBlank()) {
            Log.w(AARIA, "Picovoice access key not set — wake word disabled. Set PICOVOICE_ACCESS_KEY in gradle.properties.")
            return
        }
        initPorcupineIfNeeded() ?: return
        state = WakeWordState.LISTENING_FOR_REPLY
        wavOutputStream = null
        startCaptureThread()
        Log.i(AARIA, "Listening for wake word — say 'Porcupine' to start recording")
        Log.d(TAG, "Listening for wake word (Hey Aaria / PORCUPINE)")
    }

    /**
     * Call after [onWakeWordDetected] to start recording and listening for stop word.
     * Same capture thread continues; frames are written to [outputFile] (WAV) and fed to [silenceDetector].
     */
    fun startListeningForStop(outputFile: File) {
        if (state != WakeWordState.LISTENING_FOR_REPLY) return
        state = WakeWordState.LISTENING_FOR_STOP
        wavOutputStream = WavWriter.createWavFile(outputFile)
        Log.i(AARIA, "Recording started → ${outputFile.name} (say 'Terminator' or stay silent 1.5s to stop)")
        Log.d(TAG, "Recording to ${outputFile.name}; listening for stop word (Done / TERMINATOR)")
    }

    /**
     * Listen for cancel keyword (TERMINATOR) during read-back window.
     * Call [onCancelDetected] when user says "Terminator" to abort the send.
     */
    fun startListeningForCancel() {
        if (state != WakeWordState.INACTIVE) return
        if (accessKey.isBlank()) return
        initPorcupineIfNeeded() ?: return
        state = WakeWordState.LISTENING_FOR_CANCEL
        wavOutputStream = null
        startCaptureThread()
        Log.i(AARIA, "Listening for cancel — say 'Terminator' to abort send")
    }

    fun stop() {
        running.set(false)
        captureThread?.interrupt()
        captureThread = null
        wavOutputStream?.cancel()
        wavOutputStream = null
        state = WakeWordState.INACTIVE
        // Do not release AudioRecord here — the capture thread may be the one that called stop()
        // (e.g. from onStopWordDetected). It will exit the loop and call rec.stop()/rec.release() itself.
        // releaseAudioRecord() is only for release() when we're sure the thread has ended.
        Log.d(TAG, "Stopped")
    }

    /** Call before [stop] when recording ended normally (silence or stop word) to finalize the WAV file. */
    fun finishRecording() {
        finishWavAndStop()
    }

    private fun initPorcupineIfNeeded(): ai.picovoice.porcupine.Porcupine? {
        if (porcupine != null) return porcupine
        return try {
            val p = ai.picovoice.porcupine.Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywords(
                    arrayOf(
                        ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE,
                        ai.picovoice.porcupine.Porcupine.BuiltInKeyword.TERMINATOR
                    )
                )
                .setSensitivities(floatArrayOf(0.5f, 0.5f))
                .build(context)
            porcupine = p
            Log.d(TAG, "Porcupine initialized, frameLength=${p.frameLength}, sampleRate=${p.sampleRate}")
            p
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed", e)
            null
        }
    }

    private fun startCaptureThread() {
        val p = porcupine ?: return
        val sampleRate = p.sampleRate
        val frameLength = p.frameLength
        val bufferSizeBytes = 2 * frameLength.coerceAtLeast(1024)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            rec.release()
            return
        }
        audioRecord = rec
        running.set(true)
        captureThread = Thread({
            val frame = ShortArray(frameLength)
            rec.startRecording()
            Log.i(AARIA, "Capture thread started — microphone active")
            Log.d(TAG, "Capture thread started")
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = rec.read(frame, 0, frameLength)
                if (read <= 0) continue
                val currentState = state
                if (currentState == WakeWordState.LISTENING_FOR_STOP) {
                    wavOutputStream?.write(frame)
                    silenceDetector.feed(frame)
                }
                try {
                    val index = p.process(frame)
                    if (index == 0 && currentState == WakeWordState.LISTENING_FOR_REPLY) {
                        Log.i(AARIA, "Wake word 'Porcupine' detected — switching to recording")
                        onWakeWordDetected?.invoke()
                    } else if (index == 1 && currentState == WakeWordState.LISTENING_FOR_STOP) {
                        Log.i(AARIA, "Stop word 'Terminator' detected — stopping recording")
                        finishWavAndStop()
                        onStopWordDetected?.invoke()
                    } else if (index == 1 && currentState == WakeWordState.LISTENING_FOR_CANCEL) {
                        Log.i(AARIA, "Cancel detected — aborting send")
                        onCancelDetected?.invoke()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Porcupine process error", e)
                }
            }
            rec.stop()
            rec.release()
            audioRecord = null
            finishWavAndStop()
            Log.d(TAG, "Capture thread ended")
        }, "AariaWakeWord").apply { start() }
    }

    private fun finishWavAndStop() {
        wavOutputStream?.finish()
        wavOutputStream = null
    }

    private fun releaseAudioRecord() {
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stop()
        try {
            porcupine?.delete()
        } catch (_: Exception) { }
        porcupine = null
    }

    companion object {
        private const val TAG = "WakeWordEngine"
        /** Same tag as service so adb logcat -s Aaria shows everything */
        private const val AARIA = "Aaria"
    }
}
