package com.aaria.app.intelligence.incoming

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions

/**
 * Detects the language profile of a text string using ML Kit Language Identification.
 *
 * The bundled model (~900 KB) works fully offline and supports romanized Hindi,
 * which is essential for Hinglish WhatsApp messages.
 *
 * [detect] is a blocking call — must be invoked from a background thread (not main).
 * Use [IncomingTextProcessor.process] which runs on Dispatchers.IO.
 */
class LanguageDetector {

    data class LanguageProfile(
        val primary: String,
        val hindiRatio: Float,
        val englishRatio: Float,
        val script: String,
        val devanagariPresent: Boolean
    )

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.01f)   // low threshold — we want all candidates
            .build()
    )

    /**
     * Blocking language detection. Returns a [LanguageProfile] with Hindi/English
     * confidence ratios derived from ML Kit's per-language scores.
     *
     * Falls back to the heuristic profile if ML Kit fails or times out.
     */
    fun detect(text: String): LanguageProfile {
        val hasDevanagari = text.any { it.code in 0x0900..0x097F }

        // ML Kit can't handle very short strings reliably — fall back for <3 chars
        if (text.length < 3) {
            return heuristicProfile(text, hasDevanagari)
        }

        return try {
            val candidates = Tasks.await(
                identifier.identifyPossibleLanguages(text),
                TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )

            var hindiScore = 0f
            var englishScore = 0f

            for (lang in candidates) {
                when (lang.languageTag) {
                    "hi" -> hindiScore += lang.confidence
                    // romanized Hindi is returned as "hi" by ML Kit; also pick up
                    // related scripts that indicate Hindi content
                    "ur" -> hindiScore += lang.confidence * 0.5f  // Urdu shares vocabulary
                    "en" -> englishScore += lang.confidence
                }
            }

            // Normalize so ratios sum to 1 (ignoring other languages)
            val total = hindiScore + englishScore
            val (hRatio, eRatio) = if (total > 0f) {
                Pair(hindiScore / total, englishScore / total)
            } else {
                // ML Kit returned no Hindi or English — treat as unknown Hinglish
                Pair(0.5f, 0.5f)
            }

            val primary = when {
                hRatio >= 0.6f -> "hindi"
                eRatio >= 0.6f -> "english"
                else -> "hinglish"
            }

            Log.d(TAG, "detect: primary=$primary hi=${"%.2f".format(hRatio)} en=${"%.2f".format(eRatio)}")

            LanguageProfile(
                primary = primary,
                hindiRatio = hRatio,
                englishRatio = eRatio,
                script = if (hasDevanagari) "devanagari" else "roman",
                devanagariPresent = hasDevanagari
            )
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit language detection failed, using heuristic fallback: ${e.message}")
            heuristicProfile(text, hasDevanagari)
        }
    }

    fun close() {
        identifier.close()
    }

    // -------------------------------------------------------------------------

    private fun heuristicProfile(text: String, hasDevanagari: Boolean): LanguageProfile {
        return LanguageProfile(
            primary = "hinglish",
            hindiRatio = if (hasDevanagari) 0.7f else 0.5f,
            englishRatio = if (hasDevanagari) 0.3f else 0.5f,
            script = if (hasDevanagari) "devanagari" else "roman",
            devanagariPresent = hasDevanagari
        )
    }

    companion object {
        private const val TAG = "LanguageDetector"
        private const val TIMEOUT_MS = 500L
    }
}
