package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log

/** SetAlarmClock sends an exact, user-visible alarm broadcast, even during Doze. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AlarmStore(context)
        if (intent.action == ACTION_RECOVER) {
            val id = intent.getStringExtra(AlarmService.EXTRA_SESSION)
            val token = intent.getStringExtra(AlarmService.EXTRA_RECOVERY_TOKEN)
            val active = if (id != null && token != null) store.acceptRecovery(id, token) else null
            if (active == null) {
                Log.i("MadClock", "Recovery broadcast ignored: completed, duplicate or stale ticket; session=$id")
                return
            }
            Log.i("MadClock", "Recovery delivered: session=$id ticket=$token " +
                "sinceTaskRemovalMs=${SystemClock.elapsedRealtime() - active.recoveryRequestedElapsedMs}")
            if (RecoveryStartPolicy.permitsBackgroundRecovery(context, active)) {
                AlarmService.resume(context, active.id, token)
            }
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
