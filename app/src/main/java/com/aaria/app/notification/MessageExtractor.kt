package com.aaria.app.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.aaria.app.queue.MessageObject

class MessageExtractor {

    private companion object {
        // "12 messages from 4 chats"  — global summary
        // "2 new messages"            — per-contact collapsed summary
        private val SUMMARY_PATTERN = Regex(
            """^\d+\s+message[s]?\s+from\s+\d+\s+chat[s]?$|^\d+\s+new\s+message[s]?$""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Extracts a normalized [MessageObject] from a WhatsApp [StatusBarNotification].
     *
     * Phase 1 requirements:
     * - Read sender / title
     * - Read message text (handling BIG_TEXT when present)
     * - Detect group vs 1:1 chats
     * - Build a stable senderKey for storing RemoteInput actions
     */
    fun extract(sbn: StatusBarNotification): MessageObject? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null

        // Conversation or chat title (group name when present)
        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        // Title is either contact name (1:1) or per-message sender name in a group
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null

        // Prefer BIG_TEXT when available, fall back to TEXT
        val bigText =
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text =
            bigText ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return null

        val isGroup = !conversationTitle.isNullOrEmpty()
        val groupName = if (isGroup) conversationTitle else null

        val sender = title

        // Ignore WhatsApp's aggregate summary notification ("12 messages from 4 chats")
        if (sender.equals("WhatsApp", ignoreCase = true)) return null
        if (SUMMARY_PATTERN.matches(text.trim())) return null

        // Sender key used to correlate RemoteInput actions with conversations
        val senderKey = if (isGroup) {
            "$groupName::$sender"
        } else {
            sender
        }

        val replyAvailable = notification.actions?.any { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty()
        } == true

        return MessageObject(
            id = sbn.key,
            sender = sender,
            text = text,
            isGroup = isGroup,
            groupName = groupName,
            senderKey = senderKey,
            timestamp = sbn.postTime,
            replyAvailable = replyAvailable
        )
    }
}
