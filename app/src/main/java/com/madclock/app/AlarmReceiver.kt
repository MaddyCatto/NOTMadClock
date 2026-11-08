package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/** SetAlarmClock sends an exact, user-visible alarm broadcast, even during Doze. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        if (intent.action == ACTION_RECOVER) {
            val active = store.activeAlarm ?: return
            if (intent.getStringExtra(AlarmService.EXTRA_SESSION) != active.id) return
            AlarmService.resume(context, active.id)
            return
        }
        val test = intent.action == ACTION_TEST
        if (!test && intent.action != ACTION_DAILY) return
        if (!test && !store.enabled) return
        if (!store.isConfigured()) return

        // Schedule tomorrow before starting the player: protects against activity/process restarts.
        if (!test) AlarmScheduler.scheduleNext(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val active = store.beginAlarm(audio.getStreamVolume(AudioManager.STREAM_ALARM))
        if (active == null) Log.e("MadClock", "Could not persist active alarm")
        else AlarmService.resume(context, active.id)
    }

    companion object {
        const val ACTION_DAILY = "com.madclock.app.ALARM_DAILY"
        const val ACTION_TEST = "com.madclock.app.ALARM_TEST"
        const val ACTION_RECOVER = "com.madclock.app.ALARM_RECOVER"
    }
}
