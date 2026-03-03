package com.aaria.app.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aaria.app.AariaApplication

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    }

    private val messageExtractor = MessageExtractor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication

        // Store RemoteInput action for this conversation, if present
        val actionWithRemoteInput = sbn.notification.actions
            ?.firstOrNull { action ->
                val inputs = action.remoteInputs
                inputs != null && inputs.isNotEmpty()
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

        // Push to in-memory queue so UI can log messages
        app.messageQueue.add(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        // On removal we treat the associated RemoteInput as expired
        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication
        app.remoteInputStore.markExpired(message.senderKey)
    }
}
