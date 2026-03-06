package com.aaria.app.queue

import com.aaria.app.tts.MessageReader

/**
 * Builds and speaks the queue-summary announcement after a message is read.
 *
 * Examples:
 *  - 1 remaining: "1 message remaining — Mom. Say 1 to reply, or say later."
 *  - 2 remaining: "2 messages remaining — Mom, Team group. Say 1 for Mom, 2 for Team group, or say later."
 *  - 0 remaining: (nothing — caller handles silence)
 *
 * The numbered option approach avoids the need for open-vocabulary name recognition
 * via Porcupine. The user says "1", "2", etc. which are matched by [ContactSelector].
 */
class QueueAnnouncer(
    private val messageQueue: MessageQueue,
    private val messageReader: MessageReader
) {

    /**
     * Announce the current queue state.
     * Calls [onDone] when TTS finishes, passing the ordered list of pending senderKeys
     * so the caller can map "1" → first key, "2" → second key, etc.
     *
     * If the queue is empty, [onDone] is called immediately with an empty list.
     */
    fun announceRemaining(onDone: (pendingKeys: List<String>) -> Unit) {
        val allKeys = messageQueue.pendingSenderKeys()

        if (allKeys.isEmpty()) {
            onDone(emptyList())
            return
        }

        // Build display names for each key (use the first queued message's sender name)
        val displayNames = allKeys.map { key ->
            messageQueue.findBySenderKey(key)?.sender ?: key
        }

        val count = allKeys.size
        val noun = if (count == 1) "message" else "messages"

        val announcement = buildString {
            append("$count $noun remaining — ")
            append(displayNames.joinToString(", "))
            append(". ")
            if (count == 1) {
                append("Say 1 to reply, or say later.")
            } else {
                val options = displayNames.mapIndexed { i, name -> "${i + 1} for $name" }
                append("Say ")
                append(options.joinToString(", "))
                append(", or say later.")
            }
        }

        messageReader.announce(announcement) {
            onDone(allKeys)
        }
    }

}
