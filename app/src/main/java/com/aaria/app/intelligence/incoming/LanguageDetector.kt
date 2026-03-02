package com.aaria.app.intelligence.incoming

class LanguageDetector {

    data class LanguageProfile(
        val primary: String,
        val hindiRatio: Float,
        val englishRatio: Float,
        val script: String,
        val devanagariPresent: Boolean
    )

    fun detect(text: String): LanguageProfile {
        // TODO Phase 5: fastText classifier via TFLite
        val hasDevanagari = text.any { it.code in 0x0900..0x097F }

        return LanguageProfile(
            primary = "hinglish",
            hindiRatio = 0.5f,
            englishRatio = 0.5f,
            script = if (hasDevanagari) "devanagari" else "roman",
            devanagariPresent = hasDevanagari
        )
    }
}
