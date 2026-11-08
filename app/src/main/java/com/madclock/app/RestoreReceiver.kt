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
                Log.i("MadClock", "Reschedule after ${intent.action}: $restored")
            }
        }
    }
}
