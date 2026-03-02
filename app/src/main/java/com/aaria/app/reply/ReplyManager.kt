package com.aaria.app.reply

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput as AndroidRemoteInput
import com.aaria.app.notification.RemoteInputStore

class ReplyManager(private val context: Context) {

    fun sendReply(senderKey: String, message: String, store: RemoteInputStore): ReplyResult {
        val action = store.retrieve(senderKey) ?: return ReplyResult.NoAction
        if (action.expired) return ReplyResult.Expired

        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(action.resultKey, message)
            AndroidRemoteInput.addResultsToIntent(
                arrayOf(AndroidRemoteInput.Builder(action.resultKey).build()),
                intent,
                bundle
            )
            action.pendingIntent.send(context, 0, intent)
            store.remove(senderKey)
            ReplyResult.Sent
        } catch (e: Exception) {
            ReplyResult.Failed(e)
        }
    }

    sealed class ReplyResult {
        data object Sent : ReplyResult()
        data object NoAction : ReplyResult()
        data object Expired : ReplyResult()
        data class Failed(val error: Exception) : ReplyResult()
    }
}
