package com.aaria.app.intelligence.incoming

class Transliterator {

    fun transliterate(taggedWords: List<WordTagger.TaggedWord>): List<TransliteratedWord> {
        return taggedWords.map { tagged ->
            if (tagged.language == WordTagger.Language.HINDI) {
                val devanagari = romanToDevanagari(tagged.word)
                TransliteratedWord(
                    original = tagged.word,
                    transliterated = devanagari,
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

    /**
     * Rule-based Roman-Hindi → Devanagari transliteration.
     * Applies longest-match substitution on the lowercase input.
     * Common words are handled via a dictionary first for accuracy;
     * unknown words fall back to phoneme rules.
     */
    private fun romanToDevanagari(word: String): String {
        // If already Devanagari, return as-is
        if (word.any { it.code in 0x0900..0x097F }) return word

        val lower = word.lowercase()
        val trailingPunct = lower.takeLastWhile { it in PUNCT_CHARS }
        val core = if (trailingPunct.isNotEmpty()) lower.dropLast(trailingPunct.length) else lower

        // Dictionary lookup first (most accurate)
        WORD_DICT[core]?.let { return it + trailingPunct }

        // Phoneme rule-based conversion
        return applyPhonemeRules(core) + trailingPunct
    }

    private fun applyPhonemeRules(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val remaining = input.substring(i)
            val match = PHONEME_RULES.entries.firstOrNull { (roman, _) ->
                remaining.startsWith(roman)
            }
            if (match != null) {
                sb.append(match.value)
                i += match.key.length
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    data class TransliteratedWord(
        val original: String,
        val transliterated: String,
        val language: WordTagger.Language
    )

    companion object {

        // Common Hindi words — direct Roman→Devanagari dictionary
        private val WORD_DICT = mapOf(
            "main" to "मैं", "mai" to "मैं", "mein" to "मैं",
            "tu" to "तू", "tum" to "तुम", "aap" to "आप",
            "woh" to "वो", "vo" to "वो", "yeh" to "यह", "ye" to "यह",
            "hum" to "हम", "sab" to "सब",
            "hai" to "है", "hain" to "हैं", "tha" to "था", "thi" to "थी", "the" to "थे",
            "hoga" to "होगा", "hogi" to "होगी", "honge" to "होंगे",
            "kar" to "कर", "karo" to "करो", "karna" to "करना",
            "karta" to "करता", "karti" to "करती", "karte" to "करते",
            "ja" to "जा", "jao" to "जाओ", "jana" to "जाना",
            "jata" to "जाता", "jati" to "जाती", "jate" to "जाते",
            "aa" to "आ", "aao" to "आओ", "aana" to "आना",
            "aata" to "आता", "aati" to "आती", "aate" to "आते",
            "le" to "ले", "lo" to "लो", "lena" to "लेना",
            "de" to "दे", "do" to "दो", "dena" to "देना",
            "bol" to "बोल", "bolo" to "बोलो", "bolna" to "बोलना",
            "sun" to "सुन", "suno" to "सुनो", "sunna" to "सुनना",
            "dekh" to "देख", "dekho" to "देखो", "dekhna" to "देखना",
            "reh" to "रह", "raho" to "रहो", "rehna" to "रहना",
            "chal" to "चल", "chalo" to "चलो", "chalna" to "चलना",
            "bata" to "बता", "batao" to "बताओ", "batana" to "बताना",
            "pata" to "पता", "pta" to "पता",
            "soch" to "सोच", "socho" to "सोचो", "sochna" to "सोचना",
            "ruk" to "रुक", "ruko" to "रुको", "rukna" to "रुकना",
            "utha" to "उठ", "utho" to "उठो", "uthna" to "उठना",
            "baith" to "बैठ", "baitho" to "बैठो", "baithna" to "बैठना",
            "khao" to "खाओ", "khana" to "खाना", "khata" to "खाता", "khati" to "खाती",
            "piyo" to "पियो", "pina" to "पीना", "pita" to "पीता", "piti" to "पीती",
            "so" to "सो", "sona" to "सोना", "sota" to "सोता", "soti" to "सोती",
            "bhai" to "भाई", "didi" to "दीदी", "bhaiya" to "भैया",
            "behan" to "बहन", "maa" to "माँ", "papa" to "पापा",
            "yaar" to "यार", "dost" to "दोस्त",
            "ghar" to "घर", "daftar" to "दफ्तर",
            "kaam" to "काम", "khaana" to "खाना", "paani" to "पानी",
            "chai" to "चाय", "doodh" to "दूध",
            "raat" to "रात", "din" to "दिन", "subah" to "सुबह",
            "shaam" to "शाम", "dopahar" to "दोपहर",
            "kal" to "कल", "aaj" to "आज", "abhi" to "अभी", "parso" to "परसों",
            "waqt" to "वक्त", "jagah" to "जगह",
            "cheez" to "चीज़", "baat" to "बात", "khabar" to "खबर",
            "paise" to "पैसे", "rupaye" to "रुपये",
            "raasta" to "रास्ता", "sadak" to "सड़क", "gaadi" to "गाड़ी",
            "accha" to "अच्छा", "acha" to "अच्छा", "bura" to "बुरा",
            "sahi" to "सही", "galat" to "गलत",
            "theek" to "ठीक", "thik" to "ठीक", "bilkul" to "बिल्कुल", "ekdum" to "एकदम",
            "bahut" to "बहुत", "bhut" to "बहुत", "thoda" to "थोड़ा", "thodi" to "थोड़ी",
            "zyada" to "ज़्यादा", "jyada" to "ज़्यादा", "kam" to "कम",
            "jaldi" to "जल्दी", "dheere" to "धीरे",
            "bada" to "बड़ा", "badi" to "बड़ी", "chota" to "छोटा", "choti" to "छोटी",
            "naya" to "नया", "purana" to "पुराना",
            "garam" to "गरम", "thanda" to "ठंडा", "meetha" to "मीठा",
            "aur" to "और", "ya" to "या", "lekin" to "लेकिन", "magar" to "मगर",
            "toh" to "तो", "bhi" to "भी", "hi" to "ही",
            "nahi" to "नहीं", "nhi" to "नहीं", "na" to "ना", "mat" to "मत",
            "kya" to "क्या", "kyun" to "क्यों", "kyu" to "क्यों",
            "kaise" to "कैसे", "kab" to "कब", "kahan" to "कहाँ",
            "kaun" to "कौन", "kitna" to "कितना", "kitni" to "कितनी",
            "agar" to "अगर", "tab" to "तब", "jab" to "जब",
            "phir" to "फिर", "dobara" to "दोबारा",
            "isliye" to "इसलिए", "kyunki" to "क्योंकि", "taaki" to "ताकि",
            "haan" to "हाँ", "ha" to "हाँ", "hn" to "हाँ",
            "arre" to "अरे", "are" to "अरे", "oye" to "ओए",
            "bas" to "बस", "sirf" to "सिर्फ",
            "koi" to "कोई", "kuch" to "कुछ",
            "ek" to "एक", "do" to "दो", "teen" to "तीन",
            "char" to "चार", "paanch" to "पाँच",
            "pehle" to "पहले", "baad" to "बाद", "saath" to "साथ", "bina" to "बिना",
            "namaste" to "नमस्ते", "shukriya" to "शुक्रिया",
            "maafi" to "माफ़ी", "wah" to "वाह",
            "yaar" to "यार", "boss" to "बॉस",
            "ji" to "जी", "hnji" to "हाँ जी", "hnj" to "हाँ जी"
        )

        private val PUNCT_CHARS = setOf('.', ',', '!', '?', ';', ':')

        // Longest-match phoneme rules: Roman → Devanagari syllables
        // Ordered longest-first so "kh" matches before "k", etc.
        private val PHONEME_RULES = linkedMapOf(
            "ksh" to "क्ष", "gya" to "ज्ञ", "tra" to "त्र",
            "shri" to "श्री",
            "aa" to "आ", "ii" to "ई", "uu" to "ऊ", "ee" to "ई", "oo" to "ऊ",
            "ai" to "ऐ", "au" to "औ", "ou" to "औ",
            "kh" to "ख", "gh" to "घ", "ch" to "च", "chh" to "छ",
            "jh" to "झ", "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ",
            "sh" to "श", "ng" to "ङ",
            "a" to "अ", "i" to "इ", "u" to "उ", "e" to "ए", "o" to "ओ",
            "k" to "क", "g" to "ग", "c" to "क",
            "j" to "ज", "t" to "त", "d" to "द",
            "n" to "न", "p" to "प", "b" to "ब",
            "m" to "म", "y" to "य", "r" to "र",
            "l" to "ल", "v" to "व", "w" to "व",
            "s" to "स", "h" to "ह", "f" to "फ", "z" to "ज़"
        )
    }
}
