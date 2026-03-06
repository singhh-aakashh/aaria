package com.aaria.app.queue

data class MessageObject(
    val id: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    val groupName: String?,
    val senderKey: String,
    val timestamp: Long,
    val replyAvailable: Boolean,
    /** Package name of the notification (e.g. com.whatsapp). Used to cancel the notification after TTS. */
    val notificationPackage: String,
    /** Notification tag (null if none). Required for [NotificationListenerService.cancelNotification]. */
    val notificationTag: String?,
    /** Notification id. Required for [NotificationListenerService.cancelNotification]. */
    val notificationId: Int
)
