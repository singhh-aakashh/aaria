package com.aaria.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import com.aaria.app.audio.AudioFocusManager
import com.aaria.app.data.SettingsStore
import com.aaria.app.intelligence.outgoing.OutgoingTextCleaner
import com.aaria.app.mode.ModeManager
import com.aaria.app.network.ApiClient
import com.aaria.app.notification.RemoteInputStore
import com.aaria.app.notification.MarkAsReadStore
import com.aaria.app.queue.MessageObject
import com.aaria.app.queue.MessageQueue
import com.aaria.app.recording.DualStopDetector
import com.aaria.app.recording.SilenceDetector
import com.aaria.app.reply.ReplyManager
import com.aaria.app.stt.SttManager
import com.aaria.app.stt.WhisperClient
import com.aaria.app.intelligence.incoming.IncomingTextProcessor
import com.aaria.app.tts.MessageReader
import com.aaria.app.tts.TtsManager
import com.aaria.app.wakeword.WakeWordEngine

class AariaApplication : Application() {

    lateinit var messageQueue: MessageQueue
        private set

    lateinit var remoteInputStore: RemoteInputStore
        private set

    lateinit var markAsReadStore: MarkAsReadStore
        private set

    lateinit var modeManager: ModeManager
        private set

    lateinit var audioFocusManager: AudioFocusManager
        private set

    lateinit var ttsManager: TtsManager
        private set

    lateinit var messageReader: MessageReader
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var silenceDetector: SilenceDetector
        private set

    lateinit var wakeWordEngine: WakeWordEngine
        private set

    lateinit var dualStopDetector: DualStopDetector
        private set

    lateinit var sttManager: SttManager
        private set

    lateinit var outgoingTextCleaner: OutgoingTextCleaner
        private set

    lateinit var replyManager: ReplyManager
        private set

    /** The message we're replying to (set when reply window activates, cleared after send). */
    @Volatile
    var currentReplyTarget: MessageObject? = null
        private set

    fun setCurrentReplyTarget(message: MessageObject?) {
        currentReplyTarget = message
    }

    /** "idle" | "listening_for_wake" | "recording". UI can subscribe via [onRecordingStatusChanged]. */
    @Volatile
    var recordingStatus: String = "idle"
        private set

    /** Set when a recording has just finished (status goes to "idle" with a file path). */
    @Volatile
    var lastRecordedWavPath: String? = null
        private set

    /** Invoked on main thread when [recordingStatus] or [lastRecordedWavPath] changes. */
    var onRecordingStatusChanged: ((status: String, wavPath: String?) -> Unit)? = null

    /**
     * Invoked on main thread after each incoming message is processed through the
     * language intelligence pipeline. Used by MainActivity to display the language
     * profile and SSML output for Phase 5 validation.
     */
    var onProcessedMessage: ((IncomingTextProcessor.ProcessedMessage) -> Unit)? = null

    /**
     * Called by [WhatsAppNotificationListener] when connected. When set, the app can
     * trigger the "Mark as read" action (so the sender sees blue checkmarks) before
     * dismissing the notification. Invoke with [MessageObject.id].
     */
    var onTriggerMarkAsRead: ((messageId: String) -> Unit)? = null

    /**
     * Called by [WhatsAppNotificationListener] when connected. When set, the app can
     * dismiss WhatsApp notifications (e.g. after TTS has read the message) to avoid
     * the same message being read twice.
     */
    var onCancelNotification: ((pkg: String, tag: String?, id: Int) -> Unit)? = null

    fun setRecordingStatus(status: String, wavPath: String? = null) {
        recordingStatus = status
        lastRecordedWavPath = if (status == "idle") wavPath else null
        Handler(Looper.getMainLooper()).post {
            onRecordingStatusChanged?.invoke(status, wavPath)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        messageQueue = MessageQueue()
        remoteInputStore = RemoteInputStore()
        markAsReadStore = MarkAsReadStore()
        modeManager = ModeManager()
        audioFocusManager = AudioFocusManager(this)
        settingsStore = SettingsStore(this)

        ttsManager = TtsManager(
            context = this,
            onFocusRequest = { audioFocusManager.requestFocus() },
            onFocusAbandon = { audioFocusManager.abandonFocus() }
        )

        messageReader = MessageReader(
            ttsManager = ttsManager,
            audioFocusManager = audioFocusManager,
            modeManager = modeManager
        )
        messageReader.onMessageProcessed = { processed ->
            Handler(Looper.getMainLooper()).post {
                onProcessedMessage?.invoke(processed)
            }
        }

        silenceDetector = SilenceDetector(
            sampleRateHz = 16000,
            silenceThresholdMs = settingsStore.silenceThresholdMs
        )

        wakeWordEngine = WakeWordEngine(this, silenceDetector)

        dualStopDetector = DualStopDetector(silenceDetector, wakeWordEngine)

        val whisperClient = WhisperClient(ApiClient.whisperApi)
        sttManager = SttManager(this, whisperClient)
        outgoingTextCleaner = OutgoingTextCleaner()
        replyManager = ReplyManager(this)
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
