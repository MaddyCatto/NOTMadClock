package com.madclock.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

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

    val activeAlarm: ActiveAlarm?
        get() = prefs.getString("active_alarm", null)?.let { encoded ->
            val json = JSONObject(encoded)
            ActiveAlarm(
                json.getString("id"), json.optString("song").takeIf { it.isNotEmpty() },
                json.getString("barcode"), json.getInt("volume"),
                json.getInt("originalVolume"), json.optBoolean("recoveryAttempted")
            )
        }

    // All callers run on the application's main thread, including receivers and service commands.
    // commit(), rather than apply(), finishes disk persistence before playback or acknowledgement.
    fun beginAlarm(originalVolume: Int): ActiveAlarm? {
        activeAlarm?.let { return it }
        val code = barcode?.takeIf { it.isNotBlank() } ?: return null
        val alarm = ActiveAlarm(UUID.randomUUID().toString(), songUri, code, volumePercent, originalVolume)
        return if (saveActive(alarm)) alarm else null
    }

    fun claimRecovery(id: String): Boolean {
        val alarm = activeAlarm ?: return false
        if (alarm.id != id || alarm.recoveryAttempted) return false
        return saveActive(alarm.copy(recoveryAttempted = true))
    }

    fun finishAlarm(id: String, scannedBarcode: String?): Boolean {
        val alarm = activeAlarm ?: return false
        if (alarm.id != id || scannedBarcode != alarm.barcode) return false
        return prefs.edit().remove("active_alarm").commit()
    }

    private fun saveActive(alarm: ActiveAlarm): Boolean = prefs.edit().putString(
        "active_alarm", JSONObject().apply {
            put("id", alarm.id)
            put("song", alarm.songUri ?: "")
            put("barcode", alarm.barcode)
            put("volume", alarm.volumePercent)
            put("originalVolume", alarm.originalVolume)
            put("recoveryAttempted", alarm.recoveryAttempted)
        }.toString()
    ).commit()
}
