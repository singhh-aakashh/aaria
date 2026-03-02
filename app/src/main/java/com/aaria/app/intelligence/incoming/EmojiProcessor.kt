package com.aaria.app.intelligence.incoming

class EmojiProcessor {

    private val mediaPlaceholders = mapOf(
        "\uD83D\uDCF7" to "shared a photo",
        "\uD83C\uDFA4" to "sent a voice message",
        "\uD83C\uDFA5" to "shared a video",
        "\uD83D\uDCCE" to "shared a file"
    )

    fun process(text: String): String {
        var result = text
        mediaPlaceholders.forEach { (emoji, description) ->
            result = result.replace(emoji, description)
        }
        // TODO Phase 5: broader emoji-to-description or strip
        return result
    }
}
