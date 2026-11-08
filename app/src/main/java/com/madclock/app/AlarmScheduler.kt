package com.madclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.UUID

object AlarmScheduler {
    private fun manager(c: Context) = c.getSystemService(AlarmManager::class.java)
    fun hasExactAlarmAccess(c: Context) = Build.VERSION.SDK_INT < 31 || manager(c).canScheduleExactAlarms()
    fun nextTime(a: AlarmRecord, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    fun prepared(a: AlarmRecord, at: Long) = a.copy(nextAtMs = at, generation = UUID.randomUUID().toString())
    private fun event(c: Context, a: AlarmRecord, flags: Int): PendingIntent? = PendingIntent.getBroadcast(c, 2300,
        Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM)
            .setData(Uri.parse("madclock://alarm/${a.id}"))
            .putExtra("alarm_id", a.id).putExtra("generation", a.generation), flags or PendingIntent.FLAG_IMMUTABLE)
    fun arm(c: Context, a: AlarmRecord): Boolean {
        if (!a.enabled || a.nextAtMs == null || a.generation == null || !hasExactAlarmAccess(c)) return false
        return try {
            val show = PendingIntent.getActivity(c, 1103, Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager(c).setAlarmClock(AlarmManager.AlarmClockInfo(a.nextAtMs, show),
                event(c, a, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            true
        } catch (e: RuntimeException) { Log.e("MadClock", "Cannot schedule alarm=${a.id}", e); false }
    }
    fun cancel(c: Context, a: AlarmRecord) {
        event(c, a, PendingIntent.FLAG_NO_CREATE)?.let { manager(c).cancel(it); it.cancel() }
    }
    fun restoreAll(c: Context, clockChanged: Boolean = false, now: Long = System.currentTimeMillis()) {
        val store = AlarmStore(c)
        store.alarms.forEach { old ->
            if (!old.enabled) { cancel(c, old); return@forEach }
            val at = when {
                clockChanged || old.nextAtMs == null -> nextTime(old, now)
                old.repeat == AlarmRepeat.ONCE -> maxOf(old.nextAtMs, now + 1_000)
                old.nextAtMs > now -> old.nextAtMs
                else -> nextTime(old, now)
            }
            val updated = prepared(old, at)
            if (store.put(updated)) arm(c, updated)
        }
        store.restored()
    }
}
