package com.aaria.app.intelligence.outgoing

class OutgoingTextCleaner {

    private val stopWords = listOf("done", "send", "terminator", "cancel")
    private val fillerWords = listOf("um", "uh", "you know", "like", "basically", "actually")

    fun clean(rawTranscription: String): String {
        var text = rawTranscription.trim()
        text = stripStopWord(text)
        text = stripFillers(text)
        text = devanagariToRoman(text)
        return text.trim()
    }

    private fun stripStopWord(text: String): String {
        var result = text
        var changed = true
        while (changed) {
            changed = false
            val lower = result.lowercase()
            for (word in stopWords) {
                if (lower.endsWith(word)) {
                    result = result.dropLast(word.length).trimEnd()
                    changed = true
                    break
                }
            }
        }
        return result
    }

    private fun stripFillers(text: String): String {
        var result = text
        for (filler in fillerWords) {
            result = result.replace(Regex("\\b${Regex.escape(filler)}\\b", RegexOption.IGNORE_CASE), "")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun devanagariToRoman(text: String): String {
        if (text.none { it in '\u0900'..'\u097F' }) return text
        val sb = StringBuilder()
        for (c in text) {
            sb.append(DEVANAGARI_TO_ROMAN[c] ?: c)
        }
        return sb.toString()
    }

    companion object {
        // Basic Devanagari → Roman mapping for common characters (Whisper sometimes outputs Devanagari)
        private val DEVANAGARI_TO_ROMAN = mapOf(
            '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ee", '\u0909' to "u",
            '\u090A' to "oo", '\u090F' to "e", '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au",
            '\u0901' to "m", '\u0902' to "ng", '\u0903' to "h",
            '\u0915' to "ka", '\u0916' to "kha", '\u0917' to "ga", '\u0918' to "gha", '\u0919' to "nga",
            '\u091A' to "cha", '\u091B' to "chha", '\u091C' to "ja", '\u091D' to "jha", '\u091E' to "nya",
            '\u091F' to "ta", '\u0920' to "tha", '\u0921' to "da", '\u0922' to "dha", '\u0923' to "na",
            '\u0924' to "ta", '\u0925' to "tha", '\u0926' to "da", '\u0927' to "dha", '\u0928' to "na",
            '\u092A' to "pa", '\u092B' to "pha", '\u092C' to "ba", '\u092D' to "bha", '\u092E' to "ma",
            '\u092F' to "ya", '\u0930' to "ra", '\u0932' to "la", '\u0935' to "va", '\u0936' to "sha",
            '\u0937' to "ssa", '\u0938' to "sa", '\u0939' to "ha",
            '\u093C' to "", // nukta
            '\u0947' to "e", '\u0948' to "ai", '\u094B' to "o", '\u094C' to "au",
            '\u094D' to "", // virama (halant)
            '\u0964' to ".", '\u0965' to "..",
            '\u093E' to "a", '\u093F' to "i", '\u0940' to "ee", '\u0941' to "u", '\u0942' to "oo",
            '\u0943' to "ri", '\u0944' to "ree", '\u0946' to "e", '\u094A' to "o"
        )
    }
}
