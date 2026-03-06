package com.aaria.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aaria.app.service.AariaForegroundService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — starting AariaForegroundService")
        context.startForegroundService(Intent(context, AariaForegroundService::class.java))
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
