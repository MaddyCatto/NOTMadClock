package com.madclock.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One-time cancellation/decoding of pre-list formats; no legacy sound path is retained. */
object AlarmMigration {
    fun upgrade(context: Context, prefs: SharedPreferences) {
        if (prefs.contains("alarms_v3")) return
        val manager = context.getSystemService(AlarmManager::class.java)
        val snapshots = listOf("active_alarm", "plan_DAILY", "plan_TEST").mapNotNull {
            prefs.getString(it, null)?.let(::JSONObject)
        }
        snapshots.forEach { j ->
            val id = j.getString("id")
            val kind = j.optString("kind", "TEST")
            repeat(20) { slot ->
                cancel(context, manager, 2200 + slot, "com.madclock.app.SIGNAL_EVENT_V2",
                    Uri.parse("madclock://event/$kind/$id/$slot"))
            }
            j.optString("recoveryToken").takeIf { it.isNotEmpty() }?.let {
                cancel(context, manager, 1105, "com.madclock.app.ALARM_RECOVER", Uri.parse("madclock://recovery/$id/$it"))
            }
        }
        listOf(1101 to "com.madclock.app.ALARM_DAILY", 1102 to "com.madclock.app.ALARM_TEST",
            1105 to "com.madclock.app.ALARM_RECOVER").forEach { (id, action) -> cancel(context, manager, id, action, null) }
        context.getSystemService(NotificationManager::class.java).apply {
            cancelAll(); deleteNotificationChannel("madclock_system_alarm_v1")
        }
        val hasOld = listOf("hour", "minute", "enabled", "song_uri").any(prefs::contains) || snapshots.isNotEmpty()
        val record = if (hasOld) AlarmRecord(
            id = UUID.randomUUID().toString(), hour = prefs.getInt("hour", 7), minute = prefs.getInt("minute", 0),
            enabled = prefs.getBoolean("enabled", false) && !prefs.getString("song_uri", null).isNullOrBlank() &&
                !prefs.getString("barcode", null).isNullOrBlank(),
            songUri = prefs.getString("song_uri", null), songName = prefs.getString("song_name", null),
            volumePercent = prefs.getInt("volume_percent", 80).coerceIn(1, 100)) else null
        val edit = prefs.edit().putString("alarms_v3", JSONArray().apply { record?.let { put(AlarmStore.encodeRecord(it)) } }.toString())
            .putBoolean("restore_v3", hasOld)
        prefs.getString("active_alarm", null)?.let { raw ->
            val old = JSONObject(raw)
            val source = old.optString("song")
            if (source.isNotBlank()) edit.putString("active_session_v3", JSONObject().apply {
                put("id", old.getString("id")); put("alarmId", record?.id ?: ""); put("song", source)
                put("barcode", old.getString("barcode")); put("volume", old.getInt("volume"))
                put("startedAtMs", old.optLong("startAtMs", System.currentTimeMillis()))
            }.toString())
        }
        listOf("hour", "minute", "enabled", "song_uri", "song_name", "volume_percent", "signal_mode",
            "active_alarm", "plan_DAILY", "plan_TEST", "architecture_v2").forEach(edit::remove)
        check(edit.commit())
    }
    private fun cancel(c: Context, manager: AlarmManager, code: Int, action: String, data: Uri?) {
        PendingIntent.getBroadcast(c, code, Intent(c, AlarmReceiver::class.java).setAction(action).setData(data),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
    }
}
