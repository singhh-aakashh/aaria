package com.aaria.app.data

import android.content.Context
import android.content.SharedPreferences
import com.aaria.app.mode.AariaMode

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aaria_settings", Context.MODE_PRIVATE)

    var defaultMode: AariaMode
        get() = AariaMode.valueOf(prefs.getString("default_mode", AariaMode.NORMAL.name)!!)
        set(value) = prefs.edit().putString("default_mode", value.name).apply()

    var silenceThresholdMs: Long
        get() = prefs.getLong("silence_threshold_ms", 1500L)
        set(value) = prefs.edit().putLong("silence_threshold_ms", value).apply()

    var autoSendDelayMs: Long
        get() = prefs.getLong("auto_send_delay_ms", 3000L)
        set(value) = prefs.edit().putLong("auto_send_delay_ms", value).apply()

    var readBackEnabled: Boolean
        get() = prefs.getBoolean("read_back_enabled", true)
        set(value) = prefs.edit().putBoolean("read_back_enabled", value).apply()

    /** When true, the WhatsApp notification is dismissed after TTS reads the message (avoids same msg read twice). */
    var markAsReadAfterTts: Boolean
        get() = prefs.getBoolean("mark_as_read_after_tts", false)
        set(value) = prefs.edit().putBoolean("mark_as_read_after_tts", value).apply()

    var whisperApiKey: String
        get() = prefs.getString("whisper_api_key", "") ?: ""
        set(value) = prefs.edit().putString("whisper_api_key", value).apply()
}
