package com.aaria.app.intelligence.incoming

class AbbreviationExpander {

    private val dictionary = mutableMapOf(
        "yr" to "yaar",
        "k" to "ok",
        "m" to "main",
        "srsly" to "seriously",
        "tbh" to "to be honest",
        "brb" to "be right back",
        "btw" to "by the way",
        "ngl" to "not gonna lie",
        "idk" to "I don't know",
        "bc" to "because",
        "rn" to "right now",
        "nvm" to "never mind",
        "ig" to "I guess",
        "omw" to "on my way"
    )

    fun expand(text: String): String {
        val words = text.split(" ")
        return words.joinToString(" ") { word ->
            dictionary[word.lowercase()] ?: word
        }
    }

    fun addCustomExpansion(abbreviation: String, expansion: String) {
        dictionary[abbreviation.lowercase()] = expansion
    }
}
