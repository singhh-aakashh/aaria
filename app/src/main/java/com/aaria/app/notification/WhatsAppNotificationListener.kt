package com.aaria.app.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    }

    private val messageExtractor = MessageExtractor()
    private val remoteInputStore = RemoteInputStore()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        // TODO Phase 1: extract message, store RemoteInput, push to queue
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        // TODO Phase 1: mark RemoteInput as expired for this notification
    }
}
