package com.aaria.app.intelligence.incoming

class EmojiProcessor {

    fun process(text: String): String {
        var result = text
        EMOJI_MAP.forEach { (emoji, description) ->
            result = result.replace(emoji, " $description ")
        }
        // Strip any remaining Unicode emoji characters (Supplementary Multilingual Plane)
        result = result.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), " ")
        // Strip common single-codepoint symbols/dingbats
        result = result.replace(Regex("[\\u2600-\\u27BF]"), " ")
        return result.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val EMOJI_MAP = mapOf(
            // Media / WhatsApp placeholders
            "📷" to "shared a photo",
            "📸" to "shared a photo",
            "🎤" to "sent a voice message",
            "🎵" to "sent audio",
            "🎶" to "sent audio",
            "🎥" to "shared a video",
            "📹" to "shared a video",
            "📎" to "shared a file",
            "📄" to "shared a document",
            "📃" to "shared a document",
            "📑" to "shared a document",
            "🗺" to "shared a location",
            "📍" to "shared a location",
            "📌" to "shared a location",
            "💳" to "shared a contact",
            "👤" to "shared a contact",
            "🔗" to "shared a link",
            "🔔" to "notification",
            "🔕" to "muted notification",
            "🗑" to "deleted message",
            // Reactions / sentiment
            "😂" to "laughing",
            "🤣" to "laughing hard",
            "😭" to "crying",
            "😢" to "sad",
            "😍" to "love",
            "🥰" to "love",
            "❤" to "heart",
            "💔" to "broken heart",
            "😡" to "angry",
            "😠" to "angry",
            "😤" to "frustrated",
            "🙄" to "eye roll",
            "😒" to "unimpressed",
            "🤔" to "thinking",
            "😮" to "surprised",
            "😱" to "shocked",
            "🤯" to "mind blown",
            "😴" to "sleepy",
            "😪" to "tired",
            "🤒" to "sick",
            "🤮" to "disgusted",
            "🙏" to "please",
            "👍" to "thumbs up",
            "👎" to "thumbs down",
            "👏" to "clapping",
            "🤝" to "handshake",
            "✌" to "peace",
            "🤞" to "fingers crossed",
            "💪" to "strong",
            "🤦" to "facepalm",
            "🤷" to "shrug",
            "😊" to "smiling",
            "😁" to "grinning",
            "😆" to "laughing",
            "😅" to "nervous laugh",
            "😬" to "awkward",
            "😎" to "cool",
            "🥳" to "celebrating",
            "🎉" to "celebration",
            "🎊" to "celebration",
            "🎂" to "birthday",
            "🎁" to "gift",
            // Common objects / actions
            "🔥" to "fire",
            "💯" to "hundred percent",
            "✅" to "done",
            "❌" to "no",
            "⚠" to "warning",
            "🚀" to "rocket",
            "⏰" to "alarm",
            "📞" to "call",
            "📱" to "phone",
            "💬" to "message",
            "🏠" to "home",
            "🏢" to "office",
            "🚗" to "car",
            "✈" to "flight",
            "🍕" to "pizza",
            "🍔" to "burger",
            "☕" to "coffee",
            "🍺" to "beer",
            "💰" to "money",
            "💸" to "money",
            "🛒" to "shopping",
            "🎮" to "gaming",
            "📺" to "TV",
            "💻" to "laptop",
            "⚽" to "football",
            "🏏" to "cricket"
        )
    }
}
