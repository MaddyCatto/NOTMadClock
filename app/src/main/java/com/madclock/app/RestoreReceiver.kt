package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val restored = AlarmScheduler.scheduleNext(context)
                val store = AlarmStore(context)
                store.plan(AlarmKind.TEST)?.let { AlarmScheduler.scheduleRun(context, it, futureOnly = true) }
                store.activeAlarm?.takeIf { it.mode == SignalMode.NOTIFICATIONS }
                    ?.let { AlarmScheduler.scheduleRun(context, it, futureOnly = true) }
                Log.i("MadClock", "Reschedule after ${intent.action}: $restored")
            }
        }
    }
}
