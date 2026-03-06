package com.aaria.app.queue

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens for a short spoken utterance and maps it to one of the pending senderKeys.
 *
 * Two matching strategies, tried in order:
 *  1. **Number** — user says "1", "2", etc. → maps to the nth key in [pendingKeys].
 *  2. **Name fuzzy match** — user says a name; each word of the recognised phrase is
 *     checked against each pending sender's display name (case-insensitive substring).
 *     First match wins.
 *
 * "Later" (or "skip", "not now") → [SelectionResult.Later].
 * No match → [SelectionResult.NoMatch].
 *
 * Uses Android [SpeechRecognizer] (offline-capable, no API cost, fast for short phrases).
 * Must be called on the main thread (SpeechRecognizer requirement).
 */
class ContactSelector(private val context: Context) {

    sealed class SelectionResult {
        /** User selected this senderKey. */
        data class Selected(val senderKey: String) : SelectionResult()
        /** User said "later" / "skip". */
        object Later : SelectionResult()
        /** Recognition succeeded but no match found. */
        object NoMatch : SelectionResult()
        /** Recognition failed (timeout, no mic, etc.). */
        data class Error(val reason: String) : SelectionResult()
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * Start a short recognition session.
     *
     * @param pendingKeys  Ordered list of senderKeys (index 0 = "1", index 1 = "2", …).
     * @param displayNames Parallel list of human-readable sender names for fuzzy matching.
     * @param onResult     Called exactly once with the result (on main thread).
     */
    fun listen(
        pendingKeys: List<String>,
        displayNames: List<String>,
        onResult: (SelectionResult) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            onResult(SelectionResult.Error("Speech recognition not available"))
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList<String>()
                Log.d(TAG, "Recognition results: $matches")
                val result = resolveMatch(matches, pendingKeys, displayNames)
                Log.i(TAG, "Contact selection resolved: $result")
                onResult(result)
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onError(error: Int) {
                val reason = errorDescription(error)
                Log.w(TAG, "Recognition error: $reason ($error)")
                onResult(SelectionResult.Error(reason))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Short utterance — stop listening after 3s of speech or 5s total
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        recognizer?.startListening(intent)
        Log.i(TAG, "Listening for contact selection (${pendingKeys.size} options)")
    }

    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    // -------------------------------------------------------------------------

    private fun resolveMatch(
        candidates: List<String>,
        pendingKeys: List<String>,
        displayNames: List<String>
    ): SelectionResult {
        for (candidate in candidates) {
            val lower = candidate.trim().lowercase()

            // "later", "skip", "not now", "ignore"
            if (LATER_WORDS.any { lower.contains(it) }) return SelectionResult.Later

            // Number: "1", "2", "one", "two", etc.
            val number = parseNumber(lower)
            if (number != null && number in 1..pendingKeys.size) {
                return SelectionResult.Selected(pendingKeys[number - 1])
            }

            // Name fuzzy: any word in the recognised phrase matches any word in a display name
            for ((index, name) in displayNames.withIndex()) {
                val nameWords = name.lowercase().split(Regex("\\s+"))
                val candidateWords = lower.split(Regex("\\s+"))
                if (candidateWords.any { cw -> nameWords.any { nw -> nw.contains(cw) || cw.contains(nw) } }) {
                    return SelectionResult.Selected(pendingKeys[index])
                }
            }
        }
        return SelectionResult.NoMatch
    }

    private fun parseNumber(text: String): Int? {
        // Digit string
        text.trim().toIntOrNull()?.let { return it }
        // Word numbers
        return WORD_NUMBERS[text.trim()]
    }

    private fun errorDescription(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Unknown error $error"
    }

    companion object {
        private const val TAG = "ContactSelector"

        private val LATER_WORDS = setOf("later", "skip", "not now", "ignore", "dismiss", "baad mein", "baad")

        private val WORD_NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5
        )
    }
}
