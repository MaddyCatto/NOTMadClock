package com.madclock.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.roundToInt

object SignalController {
    fun onEvent(context: Context, id: String, kind: AlarmKind, slot: Int, done: () -> Unit) {
        val store = AlarmStore(context)
        if (slot !in 0 until AlarmScheduler.EVENT_COUNT) { done(); return }
        val planned = store.activeAlarm ?: store.plan(kind)
        if (planned == null || planned.id != id || (kind == AlarmKind.DAILY &&
                store.activeAlarm == null && !store.enabled)) { done(); return }
        if (planned.mode == SignalMode.AUDIO_APP && slot != 0) { done(); return }
        if (store.pendingAudioStop != null) {
            AudioBridge.flushPendingStop(context) { done() }
            return
        }
        val fresh = store.activeAlarm == null
        val active = store.activate(planned) ?: run { done(); return }
        if (fresh && kind == AlarmKind.DAILY) AlarmScheduler.scheduleNext(context)
        if (active.mode == SignalMode.AUDIO_APP) {
            NotificationSignals.cancel(context)
            if (!AudioBridge.start(context, active)) Log.w("MadClock", "Mode B unavailable; signal remains active")
            done()
        } else if (active.audioQuietConfirmed) {
            postSlot(context, active.id, slot); done()
        } else AudioBridge.ensureQuiet(context) { quiet ->
            if (quiet) postSlot(context, active.id, slot)
            else Log.w("MadClock", "Mode A suppressed: helper silence not confirmed")
            done()
        }
    }
    private fun postSlot(context: Context, id: String, slot: Int) {
        val store = AlarmStore(context)
        val active = store.activeAlarm ?: return
        if (active.id != id || active.mode != SignalMode.NOTIFICATIONS || slot <= active.lastNotificationSlot) return
        val audio = context.getSystemService(AudioManager::class.java)
        val updated = active.copy(audioQuietConfirmed = true, lastNotificationSlot = slot,
            originalVolume = active.originalVolume ?: audio.getStreamVolume(AudioManager.STREAM_ALARM))
        if (!store.updateActive(updated)) return
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM,
                (max * active.volumePercent / 100.0).roundToInt().coerceIn(1, max), 0)
        }.onFailure { Log.w("MadClock", "Alarm volume setting rejected", it) }
        runCatching { NotificationSignals.post(context, slot) }
            .onFailure { Log.e("MadClock", "System notification rejected", it) }
    }
    fun stopAfterScan(context: Context, id: String, code: String): Boolean {
        val active = AlarmStore(context).finishAlarm(id, code) ?: return false
        AlarmScheduler.cancelRun(context, active)
        NotificationSignals.cancel(context)
        if (active.mode == SignalMode.AUDIO_APP) AudioBridge.flushPendingStop(context)
        else active.originalVolume?.let { before ->
            runCatching { context.getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
        }
        return true
    }
    fun onVisibleLaunch(context: Context) {
        AudioBridge.flushPendingStop(context)
        val active = AlarmStore(context).activeAlarm ?: return
        if (active.mode == SignalMode.NOTIFICATIONS) AlarmScheduler.scheduleRun(context, active, futureOnly = true)
        else {
            NotificationSignals.cancel(context)
            AudioBridge.start(context, active)
        }
    }
}
