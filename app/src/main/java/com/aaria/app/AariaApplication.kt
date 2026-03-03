package com.aaria.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aaria.app.notification.RemoteInputStore
import com.aaria.app.queue.MessageQueue

class AariaApplication : Application() {

    // Global in-memory stores used across the app
    lateinit var messageQueue: MessageQueue
        private set

    lateinit var remoteInputStore: RemoteInputStore
        private set

    override fun onCreate() {
        super.onCreate()
        messageQueue = MessageQueue()
        remoteInputStore = RemoteInputStore()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
