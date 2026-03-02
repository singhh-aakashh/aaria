package com.aaria.app.intelligence.incoming

class WordTagger {

    enum class Language { HINDI, ENGLISH }

    data class TaggedWord(
        val word: String,
        val language: Language
    )

    fun tag(text: String, profile: LanguageDetector.LanguageProfile): List<TaggedWord> {
        // TODO Phase 5: word-level classification using fastText + vocabulary lookups
        return text.split(" ").map { word ->
            TaggedWord(word, Language.ENGLISH)
        }
    }
}
