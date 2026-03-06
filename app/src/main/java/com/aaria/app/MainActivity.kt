package com.aaria.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aaria.app.intelligence.incoming.IncomingTextProcessor
import com.aaria.app.mode.AariaMode
import com.aaria.app.queue.MessageObject
import com.aaria.app.service.AariaForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var batteryStatusTextView: TextView
    private lateinit var ssmlStatusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: androidx.core.widget.NestedScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        batteryStatusTextView = findViewById(R.id.batteryStatusTextView)
        ssmlStatusTextView = findViewById(R.id.ssmlStatusTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        requestRecordAudioIfNeeded()

        // Start the foreground service immediately on launch — don't wait for a message
        startForegroundService(Intent(this, AariaForegroundService::class.java))

        findViewById<Button>(R.id.enableListenerButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.batteryOptButton).setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        findViewById<Button>(R.id.testTtsButton).setOnClickListener {
            val app = application as AariaApplication
            app.messageReader.announce("Aaria is working. Text to speech is active.")
        }

        setupModeSelector()
        updateNotificationAccessStatus()
        updateBatteryStatus()
        updateSsmlStatus()
        bindMarkAsReadAfterTtsToggle()
        bindToMessageQueue()
        bindRecordingStatus()
        bindProcessedMessageLog()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessStatus()
        updateBatteryStatus()
        updateSsmlStatus()
        updateRecordingStatusUi()
    }

    private fun requestRecordAudioIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as AariaApplication
        app.messageQueue.removeListener(LISTENER_TAG)
        app.onRecordingStatusChanged = null
        app.onProcessedMessage = null
        app.onCancelNotification = null
        app.onTriggerMarkAsRead = null
    }

    // -------------------------------------------------------------------------

    private fun setupModeSelector() {
        val app = application as AariaApplication
        val radioGroup = findViewById<RadioGroup>(R.id.modeRadioGroup)

        val modeForButton = mapOf(
            R.id.modeNormal to AariaMode.NORMAL,
            R.id.modeDriving to AariaMode.DRIVING,
            R.id.modeSilent to AariaMode.SILENT
        )

        // Reflect current mode on launch
        val currentMode = app.modeManager.currentMode
        val initialId = modeForButton.entries.firstOrNull { it.value == currentMode }?.key
        if (initialId != null) radioGroup.check(initialId)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForButton[checkedId] ?: return@setOnCheckedChangeListener
            app.modeManager.switchTo(mode)
            appendSystemLog("Mode switched to $mode")
        }
    }

    private fun bindMarkAsReadAfterTtsToggle() {
        val app = application as AariaApplication
        val switchView = findViewById<SwitchCompat>(R.id.markAsReadAfterTtsSwitch)
        switchView.isChecked = app.settingsStore.markAsReadAfterTts
        switchView.setOnCheckedChangeListener { _, isChecked ->
            app.settingsStore.markAsReadAfterTts = isChecked
            appendSystemLog("Mark as read after TTS: ${if (isChecked) "on" else "off"}")
        }
    }

    private fun updateNotificationAccessStatus() {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        val enabled = enabledPackages.contains(packageName)
        statusTextView.setText(
            if (enabled) R.string.notification_access_status_enabled
            else R.string.notification_access_status_disabled
        )
        statusTextView.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.aaria_success else R.color.aaria_error)
        )
    }

    private fun updateSsmlStatus() {
        val app = application as AariaApplication
        val supported = app.ttsManager.ssmlSupported
        ssmlStatusTextView.text = when (supported) {
            true -> getString(R.string.ssml_status_supported)
            false -> getString(R.string.ssml_status_unsupported)
            null -> getString(R.string.ssml_status_unknown)
        }
        ssmlStatusTextView.setTextColor(
            ContextCompat.getColor(
                this,
                when (supported) {
                    true -> R.color.aaria_success
                    false -> R.color.aaria_warning
                    null -> R.color.aaria_on_surface
                }
            )
        )
    }

    private fun updateBatteryStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatusTextView.setText(
            if (exempt) R.string.battery_opt_exempt
            else R.string.battery_opt_restricted
        )
        batteryStatusTextView.setTextColor(
            ContextCompat.getColor(this, if (exempt) R.color.aaria_success else R.color.aaria_warning)
        )
        findViewById<Button>(R.id.batteryOptButton).isEnabled = !exempt
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun bindToMessageQueue() {
        val app = application as AariaApplication
        app.messageQueue.addListener(LISTENER_TAG) { message ->
            runOnUiThread { appendMessageLog(message) }
        }
    }

    private fun bindRecordingStatus() {
        val app = application as AariaApplication
        app.onRecordingStatusChanged = { status, wavPath ->
            runOnUiThread {
                updateRecordingStatusUi()
                if (status == "idle" && !wavPath.isNullOrEmpty()) {
                    appendSystemLog("WAV saved: $wavPath")
                }
            }
        }
    }

    private fun updateRecordingStatusUi() {
        val app = application as AariaApplication
        val statusView = findViewById<TextView>(R.id.recordingStatusTextView)
        val indicator = findViewById<android.widget.ProgressBar>(R.id.recordingIndicator)
        when (app.recordingStatus) {
            "listening_for_wake" -> {
                statusView.setText(R.string.voice_reply_status_listening)
                indicator.visibility = android.view.View.VISIBLE
            }
            "recording" -> {
                statusView.setText(R.string.voice_reply_status_recording)
                indicator.visibility = android.view.View.VISIBLE
            }
            else -> {
                statusView.setText(R.string.voice_reply_status_idle)
                indicator.visibility = android.view.View.GONE
            }
        }
    }

    private fun appendMessageLog(message: MessageObject) {
        if (logTextView.text == getString(R.string.no_messages_yet)) {
            logTextView.text = ""
        }
        val builder = StringBuilder()
        builder.append("Sender: ${message.sender}\n")
        if (message.isGroup) builder.append("Group: ${message.groupName}\n")
        builder.append("Text: ${message.text}\n")
        builder.append("Reply available: ${message.replyAvailable}\n")
        builder.append("Timestamp: ${message.timestamp}")
        builder.append("\n\n")
        logTextView.append(builder.toString())
        scrollLogToBottom()
    }

    private fun bindProcessedMessageLog() {
        val app = application as AariaApplication
        app.onProcessedMessage = { processed ->
            appendProcessedLog(processed)
        }
    }

    private fun appendProcessedLog(processed: IncomingTextProcessor.ProcessedMessage) {
        val p = processed.languageProfile
        val builder = StringBuilder()
        builder.append("[Lang] primary=${p.primary}  hi=${"%.0f".format(p.hindiRatio * 100)}%  en=${"%.0f".format(p.englishRatio * 100)}%  script=${p.script}\n")
        builder.append("[Plain] ${processed.plainText}\n")
        builder.append("[SSML] ${processed.ssml.take(120)}${if (processed.ssml.length > 120) "…" else ""}\n\n")
        runOnUiThread {
            if (logTextView.text == getString(R.string.no_messages_yet)) logTextView.text = ""
            logTextView.append(builder.toString())
            scrollLogToBottom()
        }
    }

    private fun appendSystemLog(text: String) {
        runOnUiThread {
            if (logTextView.text == getString(R.string.no_messages_yet)) {
                logTextView.text = ""
            }
            logTextView.append("[System] $text\n\n")
            scrollLogToBottom()
        }
    }

    private fun scrollLogToBottom() {
        logScrollView.post { logScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        private const val LISTENER_TAG = "main_activity"
        private const val REQUEST_RECORD_AUDIO = 2001
    }
}
