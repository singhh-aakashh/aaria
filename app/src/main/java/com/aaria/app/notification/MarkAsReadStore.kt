package com.aaria.app.notification

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the "Mark as read" action [PendingIntent] for WhatsApp notifications
 * so we can trigger it after TTS has read the message (sender sees blue checkmarks).
 * Keyed by notification key (e.g. [com.aaria.app.queue.MessageObject.id]).
 */
class MarkAsReadStore {

    private val pendingIntents = ConcurrentHashMap<String, PendingIntent>()

    fun store(notificationKey: String, intent: PendingIntent) {
        pendingIntents[notificationKey] = intent
    }

    fun retrieve(notificationKey: String): PendingIntent? = pendingIntents[notificationKey]

    fun remove(notificationKey: String) {
        pendingIntents.remove(notificationKey)
    }
}
