package com.madclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    const val EVENT_COUNT = 20
    const val EVENT_INTERVAL_MS = 30_000L
    private fun manager(c: Context) = c.getSystemService(AlarmManager::class.java)
    fun hasExactAlarmAccess(c: Context) = Build.VERSION.SDK_INT < 31 || manager(c).canScheduleExactAlarms()
    private fun event(c: Context, a: ActiveAlarm, slot: Int, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(c, 2200 + slot,
            Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_EVENT)
                .setData(Uri.parse("madclock://event/${a.kind}/${a.id}/$slot"))
                .putExtra("session", a.id).putExtra("kind", a.kind.name).putExtra("slot", slot),
            flags or PendingIntent.FLAG_IMMUTABLE)
    private fun show(c: Context) = PendingIntent.getActivity(c, 1103,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun scheduleNext(c: Context): Boolean {
        val store = AlarmStore(c)
        if (!store.enabled || !store.isConfigured()) return false
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, store.hour); set(Calendar.MINUTE, store.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1_000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return replace(c, store.newPlan(AlarmKind.DAILY, at))
    }
    fun scheduleTest(c: Context): Boolean {
        val store = AlarmStore(c)
        return store.isConfigured() && replace(c, store.newPlan(AlarmKind.TEST, System.currentTimeMillis() + 60_000))
    }
    private fun replace(c: Context, run: ActiveAlarm): Boolean {
        if (!hasExactAlarmAccess(c)) return false
        val store = AlarmStore(c)
        store.plan(run.kind)?.takeIf { it.id != store.activeAlarm?.id }?.let { cancelRun(c, it) }
        if (!store.savePlan(run.kind, run)) return false
        if (scheduleRun(c, run)) return true
        store.savePlan(run.kind, null)
        return false
    }
    /** All A events exist before the first sound; receivers never schedule the next A slot. */
    fun scheduleRun(c: Context, run: ActiveAlarm, futureOnly: Boolean = false): Boolean {
        if (!hasExactAlarmAccess(c)) return false
        val count = if (run.mode == SignalMode.NOTIFICATIONS) EVENT_COUNT else 1
        return try {
            repeat(count) { slot ->
                val at = run.startAtMs + slot * EVENT_INTERVAL_MS
                if (!futureOnly || (slot > run.lastNotificationSlot && at > System.currentTimeMillis())) {
                    manager(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show(c)),
                        event(c, run, slot, PendingIntent.FLAG_UPDATE_CURRENT)!!)
                }
            }
            Log.i("MadClock", "Planned ${run.mode} session=${run.id} events=$count")
            true
        } catch (e: RuntimeException) {
            cancelRun(c, run)
            Log.e("MadClock", "Cannot plan signal ${run.mode}", e)
            false
        }
    }
    fun cancelRun(c: Context, run: ActiveAlarm) {
        repeat(EVENT_COUNT) { slot ->
            event(c, run, slot, PendingIntent.FLAG_NO_CREATE)?.let { manager(c).cancel(it); it.cancel() }
        }
    }
    private fun cancelPlan(c: Context, kind: AlarmKind) {
        val store = AlarmStore(c)
        store.plan(kind)?.takeIf { it.id != store.activeAlarm?.id }?.let { cancelRun(c, it) }
        store.savePlan(kind, null)
        // Retire the pre-migration daily/test seed; it must not start another run.
        val oldAction = if (kind == AlarmKind.DAILY) "com.madclock.app.ALARM_DAILY" else "com.madclock.app.ALARM_TEST"
        PendingIntent.getBroadcast(c, if (kind == AlarmKind.DAILY) 1101 else 1102,
            Intent(c, AlarmReceiver::class.java).setAction(oldAction),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager(c).cancel(it); it.cancel() }
    }
    fun disableDaily(c: Context) = cancelPlan(c, AlarmKind.DAILY)
    fun cancelTest(c: Context) = cancelPlan(c, AlarmKind.TEST)
}
