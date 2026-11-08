package com.madclock.app

import android.content.Context
import android.net.Uri

/** Only local state. No server, account, or network permission. */
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE)

    var hour: Int
        get() = prefs.getInt("hour", 7)
        set(value) = prefs.edit().putInt("hour", value.coerceIn(0, 23)).apply()

    var minute: Int
        get() = prefs.getInt("minute", 0)
        set(value) = prefs.edit().putInt("minute", value.coerceIn(0, 59)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var songUri: String?
        get() = prefs.getString("song_uri", null)
        set(value) = prefs.edit().putString("song_uri", value).apply()

    var songName: String?
        get() = prefs.getString("song_name", null)
        set(value) = prefs.edit().putString("song_name", value).apply()

    var barcode: String?
        get() = prefs.getString("barcode", null)
        set(value) = prefs.edit().putString("barcode", value).apply()

    var volumePercent: Int
        get() = prefs.getInt("volume_percent", 80)
        set(value) = prefs.edit().putInt("volume_percent", value.coerceIn(1, 100)).apply()

    fun isConfigured(): Boolean = !songUri.isNullOrBlank() && !barcode.isNullOrBlank()

    fun getSongUri(): Uri? = songUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
}
