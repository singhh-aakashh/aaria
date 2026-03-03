package com.aaria.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.aaria.app.queue.MessageObject

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        val enableButton: Button = findViewById(R.id.enableListenerButton)

        enableButton.setOnClickListener {
            // Opens system settings where the user can grant notification access
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        updateNotificationAccessStatus()
        bindToMessageQueue()
    }

    override fun onResume() {
        super.onResume()
        // In case the user changed notification access while in settings
        updateNotificationAccessStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Avoid leaking the Activity via the queue callback
        val app = application as AariaApplication
        app.messageQueue.onMessageAdded = null
    }

    private fun updateNotificationAccessStatus() {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        val enabled = enabledPackages.contains(packageName)
        val textRes = if (enabled) {
            R.string.notification_access_status_enabled
        } else {
            R.string.notification_access_status_disabled
        }
        statusTextView.setText(textRes)
    }

    private fun bindToMessageQueue() {
        val app = application as AariaApplication

        // If messages arrive while the Activity is visible, append them to the debug log
        app.messageQueue.onMessageAdded = { message ->
            runOnUiThread {
                appendMessageLog(message)
            }
        }
    }

    private fun appendMessageLog(message: MessageObject) {
        if (logTextView.text == getString(R.string.no_messages_yet)) {
            logTextView.text = ""
        }

        val builder = StringBuilder()
        builder.append("Sender: ${message.sender}\n")
        if (message.isGroup) {
            builder.append("Group: ${message.groupName}\n")
        }
        builder.append("Text: ${message.text}\n")
        builder.append("Reply available: ${message.replyAvailable}\n")
        builder.append("Timestamp: ${message.timestamp}")
        builder.append("\n\n")

        logTextView.append(builder.toString())
    }
}
