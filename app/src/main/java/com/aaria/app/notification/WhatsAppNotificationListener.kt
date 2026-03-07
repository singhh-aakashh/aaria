package com.aaria.app.notification

import android.app.PendingIntent
import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aaria.app.AariaApplication
import com.aaria.app.service.AariaForegroundService

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppNLS"
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
        private val MARK_AS_READ_TITLES = setOf("read", "mark")
    }

    private val messageExtractor = MessageExtractor()

    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = application as AariaApplication
        app.onCancelNotification = { pkg, tag, id -> cancelNotification(pkg, tag, id) }
        app.onTriggerMarkAsRead = { messageId ->
            val pi = app.markAsReadStore.retrieve(messageId)
            if (pi != null) {
                try {
                    pi.send()
                    Log.d(TAG, "Mark as read triggered for $messageId")
                } catch (e: PendingIntent.CanceledException) {
                    Log.w(TAG, "Mark as read PendingIntent canceled: ${e.message}")
                }
            }
            app.markAsReadStore.remove(messageId)
        }
        startForegroundService(Intent(this, AariaForegroundService::class.java))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val app = application as? AariaApplication
        app?.onCancelNotification = null
        app?.onTriggerMarkAsRead = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication
        val actions = sbn.notification.actions ?: emptyArray()

        val actionWithRemoteInput = actions.firstOrNull { action ->
            action.remoteInputs != null && action.remoteInputs!!.isNotEmpty()
        }

        if (actionWithRemoteInput != null) {
            val remoteInput = actionWithRemoteInput.remoteInputs?.firstOrNull()
            if (remoteInput != null) {
                val storedAction = RemoteInputStore.StoredAction(
                    pendingIntent = actionWithRemoteInput.actionIntent,
                    resultKey = remoteInput.resultKey,
                    timestamp = System.currentTimeMillis()
                )
                app.remoteInputStore.store(message.senderKey, storedAction)
            }
        }

        val markAsReadAction = actions.firstOrNull { action ->
            if (action.remoteInputs != null && action.remoteInputs!!.isNotEmpty()) return@firstOrNull false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return@firstOrNull true
            action.title?.toString()?.lowercase()?.let { t ->
                MARK_AS_READ_TITLES.any { keyword -> keyword in t }
            } == true
        }
        if (markAsReadAction != null && markAsReadAction.actionIntent != null) {
            app.markAsReadStore.store(sbn.key, markAsReadAction.actionIntent!!)
        }

        startForegroundService(Intent(this, AariaForegroundService::class.java))
        app.messageQueue.add(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        // Only expire the RemoteInput when the user explicitly dismissed the notification
        // (REASON_CANCEL or REASON_CANCEL_ALL). WhatsApp frequently replaces a notification
        // with an updated one (REASON_APP_CANCEL / snoozed / updated) — in those cases the
        // RemoteInput in the new sbn is still valid and must NOT be expired.
        val userDismissed = reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
        if (!userDismissed) return

        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication
        app.remoteInputStore.markExpired(message.senderKey)
        app.markAsReadStore.remove(sbn.key)
    }
}
