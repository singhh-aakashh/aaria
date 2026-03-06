package com.aaria.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.aaria.app.AariaApplication
import com.aaria.app.BuildConfig
import com.aaria.app.MainActivity
import com.aaria.app.R
import com.aaria.app.reply.ReplyManager
import com.aaria.app.queue.MessageObject
import com.aaria.app.recording.DualStopDetector
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
 * On each new WhatsApp message it:
 *  1. Pulls the [MessageReader] from the application singleton.
 *  2. Calls [MessageReader.read] — which checks mode, call state, and audio focus.
 *  3. When TTS finishes and mode allows reply, starts wake word ("Hey Aaria").
 *  4. On wake word, starts recording + dual stop (silence / "Done"); on stop, transcribes via Whisper,
 *     cleans text, reads back, 3s auto-send window with cancel detection, then RemoteInput send.
 */
class AariaForegroundService : LifecycleService() {

    @Volatile
    private var notificationContentText: String = "Listening for messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerMessageCallback()
        registerWakeWordAndRecording()
        Log.i(AARIA_TAG, "Service created — TTS pipeline ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        val app = application as AariaApplication
        app.messageQueue.removeListener(LISTENER_TAG)
        app.ttsManager.shutdown()
        app.wakeWordEngine.release()
        app.setRecordingStatus("idle")
        app.setCurrentReplyTarget(null)
        Log.i(AARIA_TAG, "Service destroyed — TTS shut down")
    }

    // -------------------------------------------------------------------------

    private fun registerMessageCallback() {
        val app = application as AariaApplication
        app.messageQueue.addListener(LISTENER_TAG) { message ->
            onNewMessage(message)
        }
    }

    private fun onNewMessage(message: MessageObject) {
        val app = application as AariaApplication
        app.messageReader.read(message) {
            if (app.modeManager.shouldActivateReplyWindow()) {
                app.setCurrentReplyTarget(message)
                Log.i(AARIA_TAG, "TTS done — starting wake word (say 'Porcupine' to reply)")
                app.setRecordingStatus("listening_for_wake")
                updateNotification("Say 'Porcupine' to reply")
                app.wakeWordEngine.startListeningForReply()
            }
        }
    }

    private fun registerWakeWordAndRecording() {
        val app = application as AariaApplication
        app.wakeWordEngine.onWakeWordDetected = {
            Log.i(AARIA_TAG, "Wake word detected — starting recording (say 'Terminator' or stay silent 1.5s to stop)")
            val outputFile = File(cacheDir, "aaria_reply_${System.currentTimeMillis()}.wav")
            app.setRecordingStatus("recording")
            updateNotification("Recording… Say 'Terminator' or stay silent")
            app.dualStopDetector.onStopDetected = { reason ->
                val path = outputFile.absolutePath
                Log.i(AARIA_TAG, "Recording stopped: $reason — WAV saved: $path")
                app.setRecordingStatus("idle", path)
                updateNotification("Transcribing…")
                app.dualStopDetector.onStopDetected = null
                runReplyPipeline(app, outputFile)
            }
            app.dualStopDetector.start(outputFile)
        }
    }

    private fun runReplyPipeline(app: AariaApplication, wavFile: File) {
        val target: MessageObject? = app.currentReplyTarget
        if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            app.messageReader.announce("OpenAI API key not set. Add OPENAI_API_KEY to gradle.properties and rebuild.") {
                finishReplyPipeline(app, wavFile, target)
            }
            return
        }
        if (!wavFile.exists() || wavFile.length() < MIN_WAV_SIZE_BYTES) {
            Log.w(AARIA_TAG, "WAV file too short or missing: ${wavFile.length()} bytes")
            app.messageReader.announce("Recording too short. Please try again.") {
                finishReplyPipeline(app, wavFile, target)
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
                        finishReplyPipeline(app, wavFile, target)
                    }
                    return@launch
                }
                val cleanedText = app.outgoingTextCleaner.clean(rawText)

                if (cleanedText.isBlank()) {
                    Log.w(AARIA_TAG, "Transcription empty (silent or inaudible)")
                    app.messageReader.announce("No speech detected. Please try again.") {
                        finishReplyPipeline(app, wavFile, target)
                    }
                    return@launch
                }

                if (target == null || !target.replyAvailable) {
                    Log.w(AARIA_TAG, "No reply target or reply not available")
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyPipeline(app, wavFile, target)
                    }
                    return@launch
                }

                val action = app.remoteInputStore.retrieve(target.senderKey)
                if (action == null || action.expired) {
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyPipeline(app, wavFile, target)
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
                    finishReplyPipeline(app, wavFile, target)
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
                    finishReplyPipeline(app, wavFile, target)
                }
                return@launch
            }

            when (val result = app.replyManager.sendReply(target.senderKey, text, app.remoteInputStore)) {
                is ReplyManager.ReplyResult.Sent -> {
                    app.messageReader.announce("Sent.") {
                        finishReplyPipeline(app, wavFile, target)
                    }
                }
                is ReplyManager.ReplyResult.Expired,
                is ReplyManager.ReplyResult.NoAction -> {
                    app.messageReader.announce("Reply window expired. Open WhatsApp manually.") {
                        finishReplyPipeline(app, wavFile, target)
                    }
                }
                is ReplyManager.ReplyResult.Failed -> {
                    Log.e(AARIA_TAG, "Reply send failed", result.error)
                    app.messageReader.announce("Failed to send. Please try again.") {
                        finishReplyPipeline(app, wavFile, target)
                    }
                }
            }
        }
    }

    private fun finishReplyPipeline(app: AariaApplication, wavFile: File, target: MessageObject?) {
        wavFile.delete()
        app.setCurrentReplyTarget(null)
        updateNotification("Listening for messages")
    }

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
        private const val TAG = "AariaForegroundService"
        /** Minimum WAV size (header + a little audio); avoids sending empty/short files to Whisper. */
        private const val MIN_WAV_SIZE_BYTES = 2000L
        /** Use this tag to see all Aaria logs: adb logcat -s Aaria */
        const val AARIA_TAG = "Aaria"
        private const val LISTENER_TAG = "foreground_service"
    }
}
