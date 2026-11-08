package com.madclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    private const val DAILY_ID = 1101
    private const val TEST_ID = 1102
    private const val RECOVERY_ID = 1105

    private fun recoveryIntent(context: Context, id: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context, RECOVERY_ID,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RECOVER)
                .putExtra(AlarmService.EXTRA_SESSION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** One continuation of an already ringing, user-requested alarm, never a repeating watchdog. */
    fun recoverAfterTaskRemoval(context: Context, id: String) {
        if (!hasExactAlarmAccess(context) || !AlarmStore(context).claimRecovery(id)) return
        try {
            alarmManager(context).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5_000L,
                recoveryIntent(context, id)
            )
        } catch (error: RuntimeException) {
            // Keep the attempt consumed even on failure: do not create a restart loop.
            Log.w("MadClock", "Task-removal continuation unavailable", error)
        }
    }

    fun cancelRecovery(context: Context) {
        alarmManager(context).cancel(recoveryIntent(context))
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
