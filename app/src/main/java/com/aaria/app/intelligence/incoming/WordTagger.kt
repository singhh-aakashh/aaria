package com.aaria.app.intelligence.incoming

class WordTagger {

    enum class Language { HINDI, ENGLISH }

    data class TaggedWord(
        val word: String,
        val language: Language
    )

    fun tag(text: String, profile: LanguageDetector.LanguageProfile): List<TaggedWord> {
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { word ->
            TaggedWord(word, classifyWord(word))
        }
    }

    private fun classifyWord(word: String): Language {
        val lower = word.lowercase().trimEnd('.', ',', '!', '?', ';', ':')
        return when {
            // Any Devanagari character → definitely Hindi
            word.any { it.code in 0x0900..0x097F } -> Language.HINDI
            // Known English words take priority
            lower in ENGLISH_VOCAB -> Language.ENGLISH
            // Known Hindi romanised words
            lower in HINDI_VOCAB -> Language.HINDI
            // Heuristic: ends with common Hindi suffixes
            HINDI_SUFFIXES.any { lower.endsWith(it) } && lower.length > 3 -> Language.HINDI
            // Heuristic: contains double vowels common in romanised Hindi
            Regex("aa|ii|oo|ee|uu").containsMatchIn(lower) -> Language.HINDI
            // Default: treat as English
            else -> Language.ENGLISH
        }
    }

    companion object {

        private val HINDI_SUFFIXES = setOf(
            "na", "ne", "ni", "ko", "ka", "ki", "ke", "se", "mein", "par",
            "wala", "wali", "wale", "kar", "karo", "karna", "karta", "karti",
            "hai", "hain", "tha", "thi", "the", "hoga", "hogi", "honge",
            "raha", "rahi", "rahe", "liya", "liye", "diya", "diye",
            "gaya", "gayi", "gaye", "aaya", "aayi", "aaye",
            "ata", "ati", "ate", "jata", "jati", "jate",
            "isko", "usko", "inko", "unko", "isne", "usne",
            "bhi", "toh", "aur", "lekin", "magar", "phir"
        )

        private val HINDI_VOCAB = setOf(
            // Pronouns
            "main", "mai", "mein", "tu", "tum", "aap", "woh", "vo", "hum",
            "yeh", "ye", "iska", "uska", "inka", "unka",
            // Common verbs
            "hai", "hain", "tha", "thi", "the", "hoga", "hogi",
            "kar", "karo", "karna", "karta", "karti", "karte",
            "ja", "jao", "jana", "jata", "jati", "jate",
            "aa", "aao", "aana", "aata", "aati", "aate",
            "le", "lo", "lena", "leta", "leti", "lete",
            "de", "do", "dena", "deta", "deti", "dete",
            "bol", "bolo", "bolna", "bolta", "bolti",
            "sun", "suno", "sunna", "sunta", "sunti",
            "dekh", "dekho", "dekhna", "dekhta", "dekhti",
            "reh", "raho", "rehna", "rehta", "rehti",
            "chal", "chalo", "chalna", "chalta", "chalti",
            "bata", "batao", "batana",
            "pata", "pta",
            "soch", "socho", "sochna",
            "ruk", "ruko", "rukna",
            "khao", "khana", "khata", "khati",
            "piyo", "pina", "pita", "piti",
            "so", "soo", "sona", "sota", "soti",
            "utha", "utho", "uthna",
            "baith", "baitho", "baithna",
            // Common nouns
            "bhai", "didi", "bhaiya", "behan", "maa", "papa", "dada", "dadi",
            "nana", "nani", "chacha", "chachi", "mama", "mami",
            "yaar", "dost", "saathi",
            "ghar", "daftar", "school", "college",
            "kaam", "khaana", "paani", "chai", "doodh",
            "raat", "din", "subah", "shaam", "dopahar",
            "kal", "aaj", "abhi", "parso",
            "waqt", "time", "jagah", "taraf",
            "cheez", "baat", "khabar", "news",
            "paise", "rupaye", "kharcha",
            "raasta", "sadak", "gaadi",
            // Adjectives / adverbs
            "accha", "acha", "bura", "sahi", "galat",
            "theek", "thik", "bilkul", "ekdum",
            "bahut", "bhut", "bht", "thoda", "thodi",
            "zyada", "jyada", "kam", "jaldi", "dheere",
            "seedha", "ulta", "naya", "purana",
            "bada", "badi", "bade", "chota", "choti", "chote",
            "lamba", "lambi", "lambe", "mota", "patla",
            "garam", "thanda", "meetha", "teekha", "khatta",
            // Particles / connectors
            "aur", "ya", "lekin", "magar", "par", "toh", "bhi",
            "hi", "na", "nahi", "nhi", "mat",
            "kya", "kyun", "kyu", "kaise", "kab", "kahan", "kha",
            "kaun", "kitna", "kitni", "kitne",
            "agar", "tab", "jab", "phir", "dobara",
            "isliye", "kyunki", "taaki",
            "haan", "ha", "hn", "arre", "are", "oye",
            "bas", "sirf", "bhi", "toh", "hi",
            "sab", "sb", "koi", "kuch", "kch",
            "ek", "do", "teen", "char", "paanch",
            "pehle", "baad", "saath", "bina",
            // Greetings / expressions
            "namaste", "namaskar", "shukriya", "dhanyawad",
            "maafi", "sorry", "please", "shukriya",
            "chalo", "chalte", "aacha", "theek",
            "haha", "hehe", "lol", "wah", "wow",
            "uff", "arrey", "yaar", "boss"
        )

        private val ENGLISH_VOCAB = setOf(
            "i", "me", "my", "we", "our", "you", "your", "he", "she", "it",
            "they", "their", "this", "that", "these", "those",
            "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did",
            "will", "would", "shall", "should", "may", "might",
            "can", "could", "must", "need", "dare",
            "a", "an", "the", "and", "or", "but", "so", "yet",
            "in", "on", "at", "by", "for", "with", "about",
            "to", "from", "of", "up", "down", "out", "off",
            "not", "no", "yes", "ok", "okay",
            "what", "when", "where", "who", "why", "how",
            "which", "whose", "whom",
            "all", "any", "each", "every", "both", "either",
            "some", "many", "much", "more", "most", "few",
            "just", "only", "also", "too", "very", "really",
            "well", "then", "now", "here", "there",
            "good", "bad", "big", "small", "new", "old",
            "first", "last", "next", "same", "other",
            "come", "go", "get", "make", "know", "think",
            "take", "give", "see", "look", "want", "need",
            "like", "love", "hate", "feel", "say", "tell",
            "work", "call", "send", "check", "meet", "wait",
            "today", "tomorrow", "yesterday", "morning", "evening",
            "night", "time", "day", "week", "month", "year",
            "home", "office", "phone", "message", "number",
            "thanks", "thank", "please", "sorry", "hello", "hi", "bye",
            "sure", "fine", "great", "nice", "cool", "done",
            "right", "wrong", "true", "false", "maybe", "probably"
        )
    }
}
