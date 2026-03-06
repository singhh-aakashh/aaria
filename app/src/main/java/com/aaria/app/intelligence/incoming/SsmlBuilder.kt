package com.aaria.app.intelligence.incoming

class SsmlBuilder {

    fun build(words: List<Transliterator.TransliteratedWord>): String {
        if (words.isEmpty()) return "<speak></speak>"

        val segments = mutableListOf<SsmlSegment>()
        var currentLang = words.first().language
        var currentWords = mutableListOf(words.first().transliterated)

        for (word in words.drop(1)) {
            if (word.language == currentLang) {
                currentWords.add(word.transliterated)
            } else {
                segments.add(SsmlSegment(currentLang, currentWords.joinToString(" ")))
                currentLang = word.language
                currentWords = mutableListOf(word.transliterated)
            }
        }
        segments.add(SsmlSegment(currentLang, currentWords.joinToString(" ")))

        return buildString {
            append("<speak>")
            for (segment in segments) {
                val langTag = if (segment.language == WordTagger.Language.HINDI) "hi-IN" else "en-IN"
                append("<lang xml:lang=\"$langTag\">${segment.text}</lang>")
            }
            append("</speak>")
        }
    }

    private data class SsmlSegment(
        val language: WordTagger.Language,
        val text: String
    )
}
