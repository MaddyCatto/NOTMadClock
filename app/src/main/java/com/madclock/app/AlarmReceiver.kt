package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** SetAlarmClock sends an exact, user-visible alarm broadcast, even during Doze. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val test = intent.action == ACTION_TEST
        if (!test && intent.action != ACTION_DAILY) return
        val store = AlarmStore(context)
        if (!test && !store.enabled) return
        if (!store.isConfigured()) return

        // Schedule tomorrow before starting the player: protects against activity/process restarts.
        if (!test) AlarmScheduler.scheduleNext(context)
        try {
            val service = Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (error: RuntimeException) {
            // No generic background-start guarantee on every OEM firmware.
            Log.e("MadClock", "Could not start alarm service", error)
        }
    }

    companion object {
        const val ACTION_DAILY = "com.madclock.app.ALARM_DAILY"
        const val ACTION_TEST = "com.madclock.app.ALARM_TEST"
    }
}
