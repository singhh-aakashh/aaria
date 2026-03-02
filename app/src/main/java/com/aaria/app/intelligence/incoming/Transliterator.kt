package com.aaria.app.intelligence.incoming

class Transliterator {

    fun transliterate(taggedWords: List<WordTagger.TaggedWord>): List<TransliteratedWord> {
        return taggedWords.map { tagged ->
            if (tagged.language == WordTagger.Language.HINDI) {
                TransliteratedWord(
                    original = tagged.word,
                    transliterated = romanToDevanagari(tagged.word),
                    language = tagged.language
                )
            } else {
                TransliteratedWord(
                    original = tagged.word,
                    transliterated = tagged.word,
                    language = tagged.language
                )
            }
        }
    }

    private fun romanToDevanagari(word: String): String {
        // TODO Phase 5: rule-based Roman Hindi to Devanagari mapping
        return word
    }

    data class TransliteratedWord(
        val original: String,
        val transliterated: String,
        val language: WordTagger.Language
    )
}
