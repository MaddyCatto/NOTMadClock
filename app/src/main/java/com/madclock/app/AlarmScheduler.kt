package com.madclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    private const val DAILY_ID = 1101
    private const val TEST_ID = 1102
    private const val RECOVERY_ID = 1105
    const val RECOVERY_DELAY_MS = 5_000L

    private fun recoveryIntent(context: Context, active: ActiveAlarm, flags: Int): PendingIntent? {
        val token = active.recoveryToken ?: return null
        return PendingIntent.getBroadcast(
            context, RECOVERY_ID,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RECOVER)
                .setData(Uri.parse("madclock://recovery/${active.id}/$token"))
                .putExtra(AlarmService.EXTRA_SESSION, active.id)
                .putExtra(AlarmService.EXTRA_RECOVERY_TOKEN, token),
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** One ticket per interruption, rearmed only by a successful MediaPlayer.start(). */
    fun recoverAfterTaskRemoval(context: Context, id: String) {
        if (!hasExactAlarmAccess(context)) {
            Log.w("MadClock", "Recovery not scheduled: exact-alarm access denied; session=$id")
            return
        }
        val store = AlarmStore(context)
        val current = store.activeAlarm
        if (current == null || current.id != id) {
            Log.i("MadClock", "Recovery not scheduled: no matching active session; session=$id")
            return
        }
        if (current.recoveryToken != null) {
            Log.i("MadClock", "Recovery not scheduled: ticket already pending/in-flight; " +
                "session=$id ticket=${current.recoveryToken} delivered=${current.recoveryDelivered}")
            return
        }
        val active = store.claimRecovery(id)
        if (active == null) {
            Log.e("MadClock", "Recovery not scheduled: ticket persistence failed; session=$id")
            return
        }
        try {
            alarmManager(context).setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, active.recoveryRequestedElapsedMs + RECOVERY_DELAY_MS,
                recoveryIntent(context, active, PendingIntent.FLAG_UPDATE_CURRENT)!!
            )
            Log.i("MadClock", "Recovery scheduled: session=$id ticket=${active.recoveryToken} delayMs=$RECOVERY_DELAY_MS")
        } catch (error: RuntimeException) {
            // Retain the ticket until real playback succeeds; never requeue after a failed start.
            Log.w("MadClock", "Recovery scheduling rejected: session=$id ticket=${active.recoveryToken}", error)
        }
    }

    fun cancelRecovery(context: Context, active: ActiveAlarm) {
        recoveryIntent(context, active, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager(context).cancel(it)
            it.cancel()
        }
    }

    fun playbackStarted(context: Context, id: String) {
        val store = AlarmStore(context)
        val active = store.activeAlarm ?: return
        if (!store.confirmPlayback(id)) {
            Log.w("MadClock", "Playback started but recovery rearm could not be persisted; session=$id")
            return
        }
        // Clear the durable token before cancellation: a queued stale broadcast is harmless.
        runCatching { cancelRecovery(context, active) }
            .onFailure { Log.w("MadClock", "Old recovery ticket cancellation failed", it) }
        val elapsed = SystemClock.elapsedRealtime() - active.recoveryRequestedElapsedMs
        Log.i("MadClock", "Playback started: session=$id recoveryRearmed=true " +
            "sinceTaskRemovalMs=${if (active.recoveryToken != null && elapsed >= 0) elapsed else -1}")
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun hasExactAlarmAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    private fun pendingAlarm(context: Context, test: Boolean): PendingIntent {
        val requestCode = if (test) TEST_ID else DAILY_ID
        val action = if (test) AlarmReceiver.ACTION_TEST else AlarmReceiver.ACTION_DAILY
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showAlarmIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1103,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleNext(context: Context): Boolean {
        val store = AlarmStore(context)
        if (!store.enabled || !store.isConfigured() || !hasExactAlarmAccess(context)) return false
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, store.hour)
            set(Calendar.MINUTE, store.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return try {
            alarmManager(context).setAlarmClock(
                AlarmManager.AlarmClockInfo(next.timeInMillis, showAlarmIntent(context)),
                pendingAlarm(context, test = false)
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun scheduleTest(context: Context): Boolean {
        val store = AlarmStore(context)
        if (!store.isConfigured() || !hasExactAlarmAccess(context)) return false
        return try {
            alarmManager(context).setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 60_000L, showAlarmIntent(context)),
                pendingAlarm(context, test = true)
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun disableDaily(context: Context) {
        alarmManager(context).cancel(pendingAlarm(context, test = false))
    }

    fun cancelTest(context: Context) {
        alarmManager(context).cancel(pendingAlarm(context, test = true))
    }
}
