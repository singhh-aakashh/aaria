package com.aaria.app.notification

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

class RemoteInputStore {

    private val actions = ConcurrentHashMap<String, StoredAction>()

    data class StoredAction(
        val pendingIntent: PendingIntent,
        val resultKey: String,
        val timestamp: Long,
        val expired: Boolean = false
    )

    fun store(senderKey: String, action: StoredAction) {
        actions[senderKey] = action
    }

    fun retrieve(senderKey: String): StoredAction? = actions[senderKey]

    fun markExpired(senderKey: String) {
        actions[senderKey]?.let {
            actions[senderKey] = it.copy(expired = true)
        }
    }

    fun remove(senderKey: String) {
        actions.remove(senderKey)
    }
}
