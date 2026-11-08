package com.madclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/** Retire old recovery tickets; retain the barcode and active session when upgrading from 0.1. */
object SignalMigration {
    fun upgrade(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean("architecture_v2", false)) return
        val manager = context.getSystemService(AlarmManager::class.java)
        listOf(1101 to "com.madclock.app.ALARM_DAILY", 1102 to "com.madclock.app.ALARM_TEST").forEach { (id, action) ->
            PendingIntent.getBroadcast(context, id, Intent(context, AlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
        }
        val raw = prefs.getString("active_alarm", null)
        val j = raw?.let(::JSONObject)
        val edit = prefs.edit().putBoolean("architecture_v2", true)
        if (j == null || j.has("mode")) { check(edit.commit()); return }
        val token = j.optString("recoveryToken")
        val legacy = Intent(context, AlarmReceiver::class.java).setAction("com.madclock.app.ALARM_RECOVER")
        listOf(null, token.takeIf(String::isNotEmpty)?.let {
            Uri.parse("madclock://recovery/${j.getString("id")}/$it")
        }).forEach { data ->
            PendingIntent.getBroadcast(context, 1105, Intent(legacy).setData(data),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                manager.cancel(it); it.cancel()
            }
        }
        j.put("mode", SignalMode.NOTIFICATIONS.name)
        j.put("kind", AlarmKind.TEST.name)
        j.put("startAtMs", System.currentTimeMillis() + 1_000)
        j.put("audioQuietConfirmed", false)
        j.put("lastNotificationSlot", -1)
        listOf("recoveryToken", "recoveryAttempted", "recoveryDelivered",
            "recoveryRequestedElapsedMs", "recoveryRequestedAtMs").forEach(j::remove)
        check(edit.putString("active_alarm", j.toString()).commit())
    }
}
