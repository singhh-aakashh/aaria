package com.aaria.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Android TTS with a strictly sequential speak queue.
 * Each utterance waits for the previous one to finish before starting.
 * Integrates with AudioFocusManager: requests focus before speaking,
 * abandons it after the last utterance in the current batch completes.
 *
 * Also probes SSML <lang> tag support on first use (Phase 2 validation).
 */
class TtsManager(
    private val context: Context,
    private val onFocusRequest: () -> Boolean = { true },
    private val onFocusAbandon: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var ready = false

    // Pending items queued before TTS is initialised
    private val preInitQueue = TtsQueue()

    // Sequential work queue: each item is (text, isSsml, onDone)
    private data class SpeakJob(val text: String, val isSsml: Boolean, val onDone: (() -> Unit)?)
    private val jobQueue = ConcurrentLinkedQueue<SpeakJob>()
    private val isSpeaking = AtomicBoolean(false)

    var ssmlSupported: Boolean? = null
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "IN")
            ready = true
            tts.setOnUtteranceProgressListener(utteranceListener)
            probeSsmlSupport()
            preInitQueue.drain { speak(it) }
        } else {
            Log.e(TAG, "TTS init failed with status $status")
        }
    }

    /**
     * Speak plain text. Queued sequentially — never overlaps.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        speakInternal(text, isSsml = false, onDone = onDone)
    }

    /**
     * Speak SSML markup. Falls back to plain text if SSML is not supported.
     */
    fun speakSsml(ssml: String, plainFallback: String, onDone: (() -> Unit)? = null) {
        if (ssmlSupported == false) {
            speakInternal(plainFallback, isSsml = false, onDone = onDone)
        } else {
            speakInternal(ssml, isSsml = true, onDone = onDone)
        }
    }

    private fun speakInternal(text: String, isSsml: Boolean, onDone: (() -> Unit)?) {
        if (!ready) {
            preInitQueue.add(text)
            return
        }
        jobQueue.add(SpeakJob(text, isSsml, onDone))
        driveQueue()
    }

    private fun driveQueue() {
        if (!isSpeaking.compareAndSet(false, true)) return
        val job = jobQueue.poll()
        if (job == null) {
            isSpeaking.set(false)
            return
        }
        onFocusRequest()
        dispatchToTts(job)
    }

    private fun dispatchToTts(job: SpeakJob) {
        val utteranceId = System.currentTimeMillis().toString()
        if (job.isSsml) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts.speak(job.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        } else {
            tts.speak(job.text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
        pendingOnDone = job.onDone
    }

    @Volatile private var pendingOnDone: (() -> Unit)? = null

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}

        override fun onDone(id: String?) {
            val cb = pendingOnDone
            pendingOnDone = null
            cb?.invoke()

            isSpeaking.set(false)
            val next = jobQueue.peek()
            if (next != null) {
                driveQueue()
            } else {
                onFocusAbandon()
            }
        }

        @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(id: String?) {
            Log.e(TAG, "TTS utterance error for id=$id")
            pendingOnDone = null
            isSpeaking.set(false)
            val next = jobQueue.peek()
            if (next != null) {
                driveQueue()
            } else {
                onFocusAbandon()
            }
        }
    }

    /**
     * Probes whether the device TTS engine honours SSML <lang> tags.
     * Writes a short SSML string to a temp file and checks whether the engine
     * accepts it without error. Result stored in [ssmlSupported].
     *
     * Uses a separate UtteranceProgressListener so the probe never interferes
     * with the main speak queue.
     */
    private fun probeSsmlSupport() {
        val probeId = "ssml_probe"
        val outFile = java.io.File(context.cacheDir, "ssml_probe.wav")
        val testSsml = "<speak><lang xml:lang=\"hi-IN\">test</lang></speak>"

        val probeListener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == probeId) {
                    ssmlSupported = true
                    Log.d(TAG, "SSML probe: SUPPORTED")
                    outFile.delete()
                }
            }
            @Deprecated("Deprecated in API 21")
            override fun onError(id: String?) {
                if (id == probeId) {
                    ssmlSupported = false
                    Log.d(TAG, "SSML probe: NOT SUPPORTED")
                    outFile.delete()
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == probeId) {
                    ssmlSupported = false
                    Log.d(TAG, "SSML probe: NOT SUPPORTED (code $errorCode)")
                    outFile.delete()
                }
            }
        }

        // Use a temporary TTS instance just for the probe so it doesn't
        // interfere with the main utterance listener on [tts].
        val probeTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                probeTts?.setOnUtteranceProgressListener(probeListener)
                probeTts?.synthesizeToFile(testSsml, null, outFile, probeId)
            } else {
                ssmlSupported = false
                Log.w(TAG, "SSML probe TTS init failed")
            }
        }
        this.probeTts = probeTts
        Log.d(TAG, "SSML probe started")
    }

    // Held only to prevent GC during the async probe; released after probe completes.
    @Volatile private var probeTts: TextToSpeech? = null

    fun setSsmlSupported(supported: Boolean) {
        ssmlSupported = supported
        Log.i(TAG, "SSML support manually set to $supported")
    }

    fun stop() {
        jobQueue.clear()
        isSpeaking.set(false)
        tts.stop()
        onFocusAbandon()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
        probeTts?.shutdown()
        probeTts = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
