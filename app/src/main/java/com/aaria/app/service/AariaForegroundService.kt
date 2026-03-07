package com.aaria.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.aaria.app.AariaApplication
import com.aaria.app.MainActivity
import com.aaria.app.R
import com.aaria.app.queue.ContactSelector
import com.aaria.app.queue.MessageObject
import com.aaria.app.queue.QueueAnnouncer
import com.aaria.app.reply.ReplyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-running foreground service that owns the TTS/audio pipeline.
 *
 * Phase 6 queue flow:
 *  1. New message arrives → if pipeline is idle, start reading it immediately.
 *     If pipeline is busy (reading/recording), the message sits in MessageQueue.
 *  2. After reading a message, if the queue has more pending senders:
 *     - Announce: "N messages remaining — Rahul, Mom. Say 1 for Rahul, 2 for Mom, or say later."
 *     - Open a [ContactSelector] window (SpeechRecognizer burst).
 *     - On selection: pull that sender's messages from the queue and read them.
 *     - On "later" or no-match: stop pipeline; messages remain queued for next trigger.
 *  3. After reading a selected conversation, offer reply window (wake word), then
 *     after reply completes, announce remaining queue again.
 *  4. If only one sender remains, skip the selection step and read directly.
 */
class AariaForegroundService : LifecycleService() {

    @Volatile
    private var notificationContentText: String = "Listening for messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** True while the service is actively reading/recording/replying — prevents re-entrant pipeline starts. */
    private val pipelineBusy = AtomicBoolean(false)

    private lateinit var queueAnnouncer: QueueAnnouncer
    private lateinit var contactSelector: ContactSelector

    override fun onCreate() {
        super.onCreate()
        // Clean orphaned WAV files from previous crash/kill (OEM battery killer, etc.)
        cacheDir.listFiles()
            ?.filter { it.name.startsWith("aaria_reply_") && it.extension == "wav" }
            ?.forEach { it.delete() }
        val app = application as AariaApplication
        queueAnnouncer = QueueAnnouncer(app.messageQueue, app.messageReader)
        contactSelector = ContactSelector(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        registerMessageCallback()
        Log.i(AARIA_TAG, "Service created — TTS pipeline ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        contactSelector.cancel()
        val app = application as AariaApplication
        app.messageQueue.removeListener(LISTENER_TAG)
        app.messageReader.close()
        app.ttsManager.shutdown()
        app.wakeWordEngine.release()
        app.sherpaWhisperEngine.release()
        app.setRecordingStatus("idle")
        app.setCurrentReplyTarget(null)
        Log.i(AARIA_TAG, "Service destroyed — TTS shut down")
    }

    // -------------------------------------------------------------------------
    // Message arrival
    // -------------------------------------------------------------------------

    private fun registerMessageCallback() {
        val app = application as AariaApplication
        app.messageQueue.addListener(LISTENER_TAG) { _ ->
            // Kick off the pipeline only if it's not already running.
            // The newly arrived message is already in the queue.
            if (pipelineBusy.compareAndSet(false, true)) {
                scope.launch { startQueuePipeline(app) }
            }
        }
    }

    /**
     * Entry point for the queue pipeline. Reads the next available message (or lets
     * the user select from multiple senders), then loops until the queue is empty or
     * the user says "later".
     */
    private suspend fun startQueuePipeline(app: AariaApplication) {
        val pendingKeys = app.messageQueue.pendingSenderKeys()
        when {
            pendingKeys.isEmpty() -> goIdle(app)
            pendingKeys.size == 1 -> {
                // Only one sender — read immediately, no selection needed
                readConversation(app, pendingKeys.first())
            }
            else -> {
                // Multiple senders — announce and ask user to select
                announceAndSelect(app)
            }
        }
    }

    /**
     * Read all queued messages from [senderKey], then offer reply window,
     * then announce remaining queue.
     */
    private suspend fun readConversation(app: AariaApplication, senderKey: String) {
        val messages = app.messageQueue.removeAllBySenderKey(senderKey)
        if (messages.isEmpty()) {
            continueQueueOrFinish(app)
            return
        }

        // Read each message in the conversation sequentially.
        // Do NOT cancel the last message's notification here — it would invalidate the
        // RemoteInput before the user can reply. Cancel only non-final messages.
        val lastMessage = messages.last()
        for (message in messages) {
            val cancelNotificationOnTtsDone = (message != lastMessage)
            readSingleMessage(app, message, cancelNotificationOnTtsDone)
        }

        // After reading, offer reply window if mode allows
        if (app.modeManager.shouldActivateReplyWindow()) {
            openReplyWindow(app, lastMessage)
        } else {
            dismissNotification(app, lastMessage)
            continueQueueOrFinish(app)
        }
    }

    /**
     * Opens the wake-word reply window for [target].
     * If the user doesn't say the wake word within [REPLY_WINDOW_TIMEOUT_MS], the window
     * closes automatically and the pipeline continues to the next queued message.
     * This prevents the pipeline from stalling indefinitely when the user ignores a message.
     */
    private fun openReplyWindow(app: AariaApplication, target: MessageObject) {
        app.setCurrentReplyTarget(target)
        Log.i(AARIA_TAG, "TTS done — starting wake word for reply (${REPLY_WINDOW_TIMEOUT_MS / 1000}s window)")
        app.setRecordingStatus("listening_for_wake")
        updateNotification("Say 'Porcupine' to reply")

        val windowClosed = AtomicBoolean(false)

        // Timeout: if no wake word within the window, move on
        val timeoutJob = scope.launch {
            delay(REPLY_WINDOW_TIMEOUT_MS)
            if (windowClosed.compareAndSet(false, true)) {
                Log.i(AARIA_TAG, "Reply window timed out — continuing queue")
                app.wakeWordEngine.onWakeWordDetected = null
                app.wakeWordEngine.stop()
                dismissNotification(app, target)
                app.setCurrentReplyTarget(null)
                continueQueueOrFinish(app)
            }
        }

        // Override wake word callback just for this window
        app.wakeWordEngine.onWakeWordDetected = {
            if (windowClosed.compareAndSet(false, true)) {
                timeoutJob.cancel()
                Log.i(AARIA_TAG, "Wake word detected in reply window — starting recording")
                val outputFile = File(cacheDir, "aaria_reply_${System.currentTimeMillis()}.wav")
                app.setRecordingStatus("recording")
                updateNotification("Recording… Say 'Terminator' or stay silent")
                app.dualStopDetector.onStopDetected = { reason ->
                    Log.i(AARIA_TAG, "Recording stopped: $reason")
                    app.setRecordingStatus("idle", outputFile.absolutePath)
                    updateNotification("Transcribing…")
                    app.dualStopDetector.onStopDetected = null
                    runReplyPipeline(app, outputFile)
                }
                app.dualStopDetector.start(outputFile)
            }
        }

        app.wakeWordEngine.startListeningForReply()
    }

    private suspend fun readSingleMessage(
        app: AariaApplication,
        message: MessageObject,
        cancelNotificationOnTtsDone: Boolean = true
    ) {
        // Suspend until TTS finishes
        val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
        app.messageReader.read(message) { wasRead ->
            // onDone fires on the TTS engine's background thread.
            // NotificationListenerService methods (cancelNotification, markAsRead)
            // must be called on the main thread — post them there explicitly.
            if (wasRead && app.settingsStore.markAsReadAfterTts) {
                Handler(Looper.getMainLooper()).post {
                    app.onTriggerMarkAsRead?.invoke(message.id)
                    if (cancelNotificationOnTtsDone) {
                        dismissNotification(app, message)
                    }
                }
            }
            latch.complete(wasRead)
        }
        latch.await()
    }

    /** Dismisses the notification for [message]. Dismissing invalidates the RemoteInput. */
    private fun dismissNotification(app: AariaApplication, message: MessageObject) {
        app.onCancelNotification?.invoke(
            message.notificationPackage,
            message.notificationTag,
            message.notificationId
        )
    }

    /**
     * After reading/replying to a conversation, check what's left in the queue.
     * - 0 remaining → pipeline done, go idle (no announcement — avoids false "all handled").
     * - 1 remaining → read it directly.
     * - 2+ remaining → announce senders and ask user to select.
     *
     * Note: messages were already removed from the queue before reading via
     * [removeAllBySenderKey], so whatever is in the queue now is genuinely new/pending.
     * We never filter by the just-read key — new messages from the same sender that
     * arrived during reading are legitimate and must not be skipped.
     */
    private fun continueQueueOrFinish(app: AariaApplication) {
        val remaining = app.messageQueue.pendingSenderKeys()

        when {
            remaining.isEmpty() -> finishPipeline(app)
            remaining.size == 1 -> scope.launch { readConversation(app, remaining.first()) }
            else -> scope.launch { announceAndSelect(app) }
        }
    }

    /**
     * Announce remaining senders and open a [ContactSelector] window.
     * On selection, read that conversation. On "later"/error, release pipeline.
     */
    private suspend fun announceAndSelect(app: AariaApplication) {
        val latch = kotlinx.coroutines.CompletableDeferred<List<String>>()
        queueAnnouncer.announceRemaining { pendingKeys ->
            latch.complete(pendingKeys)
        }
        val pendingKeys = latch.await()

        if (pendingKeys.isEmpty()) {
            finishPipeline(app)
            return
        }

        val displayNames = pendingKeys.map { key ->
            app.messageQueue.findBySenderKey(key)?.sender ?: key
        }

        updateNotification("Waiting for contact selection…")
        Log.i(AARIA_TAG, "Waiting for contact selection: $displayNames")

        // ContactSelector must run on main thread
        withContext(Dispatchers.Main) {
            contactSelector.listen(pendingKeys, displayNames) { result ->
                scope.launch {
                    when (result) {
                        is ContactSelector.SelectionResult.Selected -> {
                            Log.i(AARIA_TAG, "Contact selected: ${result.senderKey}")
                            readConversation(app, result.senderKey)
                        }
                        is ContactSelector.SelectionResult.Later -> {
                            Log.i(AARIA_TAG, "User said later — releasing pipeline")
                            app.messageReader.announce("Okay, messages saved for later.") {
                                finishPipeline(app)
                            }
                        }
                        is ContactSelector.SelectionResult.NoMatch -> {
                            Log.w(AARIA_TAG, "No contact match — re-announcing")
                            // Give one retry, then give up
                            app.messageReader.announce("Sorry, I didn't catch that. Say a number or say later.") {
                                scope.launch {
                                    contactSelector.listen(pendingKeys, displayNames) { retryResult ->
                                        scope.launch {
                                            when (retryResult) {
                                                is ContactSelector.SelectionResult.Selected ->
                                                    readConversation(app, retryResult.senderKey)
                                                else -> {
                                                    app.messageReader.announce("Okay, messages saved for later.") {
                                                        finishPipeline(app)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is ContactSelector.SelectionResult.Error -> {
                            Log.w(AARIA_TAG, "Contact selection error: ${result.reason}")
                            finishPipeline(app)
                        }
                    }
                }
            }
        }
    }

    private fun finishPipeline(app: AariaApplication) {
        app.setCurrentReplyTarget(null)
        app.setRecordingStatus("idle")
        updateNotification("Listening for messages")
        goIdle(app)
        Log.i(AARIA_TAG, "Pipeline finished — idle")
    }

    /**
     * Transition pipeline to idle, then re-check queue. Fixes race where messages that
     * arrived while pipeline was busy were never processed until the next message arrived.
     */
    private fun goIdle(app: AariaApplication) {
        pipelineBusy.set(false)
        val remaining = app.messageQueue.pendingSenderKeys()
        if (remaining.isNotEmpty() && pipelineBusy.compareAndSet(false, true)) {
            scope.launch { startQueuePipeline(app) }
        }
    }

    // -------------------------------------------------------------------------
    // Reply pipeline (transcribe → read back → auto-send)
    // -------------------------------------------------------------------------

    private fun runReplyPipeline(app: AariaApplication, wavFile: File) {
        val target: MessageObject? = app.currentReplyTarget
        if (!wavFile.exists() || wavFile.length() < MIN_WAV_SIZE_BYTES) {
            Log.w(AARIA_TAG, "WAV file too short or missing: ${wavFile.length()} bytes")
            app.messageReader.announce("Recording too short. Please try again.") {
                finishReplyAndContinueQueue(app, wavFile, target)
            }
            return
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    app.sttManager.transcribe(wavFile)
                }
                val rawText = result.getOrElse { e ->
                    app.messageReader.announce(app.sttManager.failureReason(e)) {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                    return@launch
                }
                val cleanedText = app.outgoingTextCleaner.clean(rawText)

                if (cleanedText.isBlank()) {
                    Log.w(AARIA_TAG, "Transcription empty (silent or inaudible)")
                    app.messageReader.announce("No speech detected. Please try again.") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                    return@launch
                }

                if (target == null || !target.replyAvailable) {
                    Log.w(AARIA_TAG, "No reply target or replyAvailable=false — target=$target")
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                    return@launch
                }

                val action = app.remoteInputStore.retrieve(target.senderKey)
                if (action == null || action.expired) {
                    Log.w(AARIA_TAG, "RemoteInput missing or expired for senderKey=${target.senderKey} — action=$action")
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                    return@launch
                }

                updateNotification("Sending…")
                app.messageReader.announce("Sending: $cleanedText") {
                    runAutoSendWindow(app, cleanedText, target, wavFile)
                }
            } catch (e: Exception) {
                Log.e(AARIA_TAG, "Reply pipeline error", e)
                app.messageReader.announce("Something went wrong. Please try again.") {
                    finishReplyAndContinueQueue(app, wavFile, target)
                }
            }
        }
    }

    private fun runAutoSendWindow(app: AariaApplication, text: String, target: MessageObject, wavFile: File) {
        val cancelled = AtomicBoolean(false)

        app.wakeWordEngine.onCancelDetected = {
            cancelled.set(true)
            app.wakeWordEngine.onCancelDetected = null
            app.wakeWordEngine.stop()
        }
        app.wakeWordEngine.startListeningForCancel()

        scope.launch {
            delay(3000)
            app.wakeWordEngine.onCancelDetected = null
            app.wakeWordEngine.stop()

            if (cancelled.get()) {
                app.messageReader.announce("Cancelled.") {
                    finishReplyAndContinueQueue(app, wavFile, target)
                }
                return@launch
            }

            when (val result = app.replyManager.sendReply(target.senderKey, text, app.remoteInputStore)) {
                is ReplyManager.ReplyResult.Sent -> {
                    val remaining = app.messageQueue.pendingSenderKeys()
                    val suffix = when {
                        remaining.isEmpty() -> ""
                        remaining.size == 1 -> " 1 message remaining."
                        else -> " ${remaining.size} messages remaining."
                    }
                    app.messageReader.announce("Sent.$suffix") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                }
                is ReplyManager.ReplyResult.Expired,
                is ReplyManager.ReplyResult.NoAction -> {
                    Log.w(AARIA_TAG, "sendReply returned $result for senderKey=${target.senderKey}")
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                }
                is ReplyManager.ReplyResult.Failed -> {
                    Log.e(AARIA_TAG, "Reply send failed", result.error)
                    app.messageReader.announce("Failed to send. Please try again.") {
                        finishReplyAndContinueQueue(app, wavFile, target)
                    }
                }
            }
        }
    }

    /**
     * Called after a reply attempt (sent, cancelled, or failed).
     * Cleans up the WAV file, dismisses the notification, and continues the queue pipeline.
     */
    private fun finishReplyAndContinueQueue(app: AariaApplication, wavFile: File, target: MessageObject?) {
        wavFile.delete()
        target?.let { dismissNotification(app, it) }
        app.setCurrentReplyTarget(null)
        continueQueueOrFinish(app)
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun updateNotification(contentText: String) {
        notificationContentText = contentText
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle("Aaria")
            .setContentText(notificationContentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        /** Minimum WAV size (header + a little audio); avoids sending empty/short files to Whisper. */
        private const val MIN_WAV_SIZE_BYTES = 2000L
        /** How long to wait for wake word before closing the reply window and moving on. */
        private const val REPLY_WINDOW_TIMEOUT_MS = 10_000L
        /** Use this tag to see all Aaria logs: adb logcat -s Aaria */
        const val AARIA_TAG = "Aaria"
        private const val LISTENER_TAG = "foreground_service"
    }
}
