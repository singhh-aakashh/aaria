package com.aaria.app.notification

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aaria.app.AariaApplication
import com.aaria.app.service.AariaForegroundService

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    }

    private val messageExtractor = MessageExtractor()

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Ensure the foreground service is alive whenever the listener (re)connects.
        // This covers the case where the service was killed by the OS while the
        // notification listener was still bound.
        startForegroundService(Intent(this, AariaForegroundService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication

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

        // Ensure the foreground service is running so TTS has a valid context
        startForegroundService(Intent(this, AariaForegroundService::class.java))

        // Push to queue — the foreground service's callback will trigger TTS
        app.messageQueue.add(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val message = messageExtractor.extract(sbn) ?: return
        val app = application as AariaApplication
        app.remoteInputStore.markExpired(message.senderKey)
    }
}
