package com.aaria.app.tts

import android.util.Log
import com.aaria.app.audio.AudioFocusManager
import com.aaria.app.intelligence.incoming.IncomingTextProcessor
import com.aaria.app.mode.ModeManager
import com.aaria.app.queue.MessageObject

/**
 * Converts a [MessageObject] into spoken audio.
 *
 * Responsibilities:
 * - Suppresses TTS during active phone calls.
 * - Requests AudioFocus before speaking; abandons it after the last utterance.
 * - Builds the spoken prefix for group messages ("Message from X in Y group").
 * - Runs text through [IncomingTextProcessor] for emoji expansion, abbreviation
 *   expansion, word-level language tagging, transliteration, and SSML construction.
 * - Uses SSML (per-segment voice switching) when [TtsManager.ssmlSupported] is true;
 *   falls back to plain processed text otherwise.
 * - Delegates to [TtsManager] for sequential, non-overlapping playback.
 * - Respects [ModeManager.shouldReadMessage] — silently skips if the current
 *   mode does not want this message read aloud.
 */
class MessageReader(
    private val ttsManager: TtsManager,
    private val audioFocusManager: AudioFocusManager,
    private val modeManager: ModeManager,
    private val textProcessor: IncomingTextProcessor = IncomingTextProcessor()
) {

    /**
     * Attempt to read [message] aloud.
     *
     * @param message  The message to read.
     * @param onDone   Called when TTS finishes (or is skipped). Always fires.
     */
    fun read(message: MessageObject, onDone: (() -> Unit)? = null) {
        if (audioFocusManager.isInCall()) {
            Log.d(TAG, "Suppressing TTS — phone call in progress")
            onDone?.invoke()
            return
        }

        if (!modeManager.shouldReadMessage(message.sender)) {
            Log.d(TAG, "Mode ${modeManager.currentMode} — skipping read for ${message.sender}")
            onDone?.invoke()
            return
        }

        val prefix = buildPrefix(message)
        val processed = textProcessor.process(message.text)

        if (ttsManager.ssmlSupported == true) {
            // Wrap prefix in English, then append processed SSML body
            val ssml = buildString {
                append("<speak>")
                append("<lang xml:lang=\"en-IN\">$prefix</lang>")
                // Strip outer <speak> tags from processed SSML and embed inline
                val inner = processed.ssml
                    .removePrefix("<speak>")
                    .removeSuffix("</speak>")
                append(inner)
                append("</speak>")
            }
            Log.d(TAG, "Reading SSML: ${ssml.take(120)}")
            ttsManager.speakSsml(ssml, plainFallback = prefix + processed.plainText, onDone = onDone)
        } else {
            val plain = prefix + processed.plainText
            Log.d(TAG, "Reading plain: $plain")
            ttsManager.speak(plain, onDone)
        }
    }

    /**
     * Read a plain string directly (used for system announcements like
     * "Sending: …" or "Reply window expired").
     */
    fun announce(text: String, onDone: (() -> Unit)? = null) {
        if (audioFocusManager.isInCall()) {
            onDone?.invoke()
            return
        }
        ttsManager.speak(text, onDone)
    }

    /**
     * Stop any currently playing TTS and clear the queue.
     */
    fun stop() {
        ttsManager.stop()
    }

    // -------------------------------------------------------------------------

    private fun buildPrefix(message: MessageObject): String {
        return if (message.isGroup && message.groupName != null) {
            "Message from ${message.sender} in ${message.groupName}: "
        } else {
            "Message from ${message.sender}: "
        }
    }

    companion object {
        private const val TAG = "MessageReader"
    }
}
