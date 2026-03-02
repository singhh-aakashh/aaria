package com.aaria.app.intelligence.outgoing

class OutgoingTextCleaner {

    private val stopWords = listOf("done", "send")
    private val fillerWords = listOf("um", "uh", "you know", "like", "basically", "actually")

    fun clean(rawTranscription: String): String {
        var text = rawTranscription.trim()
        text = stripStopWord(text)
        text = stripFillers(text)
        text = devanagariToRoman(text)
        return text.trim()
    }

    private fun stripStopWord(text: String): String {
        val lower = text.lowercase()
        for (word in stopWords) {
            if (lower.endsWith(word)) {
                return text.dropLast(word.length).trimEnd()
            }
        }
        return text
    }

    private fun stripFillers(text: String): String {
        var result = text
        for (filler in fillerWords) {
            result = result.replace(Regex("\\b${Regex.escape(filler)}\\b", RegexOption.IGNORE_CASE), "")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun devanagariToRoman(text: String): String {
        // TODO Phase 5: Devanagari to Roman transliteration for Whisper output normalization
        return text
    }
}
