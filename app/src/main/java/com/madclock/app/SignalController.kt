package com.madclock.app

import android.content.Context
import android.util.Log
import java.util.UUID

object SignalController {
    fun onAlarm(context: Context, alarmId: String, generation: String, now: Long = System.currentTimeMillis()) {
        val store = AlarmStore(context)
        val due = store.find(alarmId) ?: return
        if (!due.enabled || due.generation != generation) return
        val next = if (due.repeat == AlarmRepeat.DAILY)
            AlarmScheduler.prepared(due, AlarmScheduler.nextTime(due, now))
        else due.copy(enabled = false, nextAtMs = null, generation = null)
        if (!store.put(next)) return
        if (next.enabled) AlarmScheduler.arm(context, next) else AlarmScheduler.cancel(context, next)
        // Consume/reschedule every due alarm, even when its sound coalesces into an existing signal.
        if (store.activeAlarm != null || store.pendingAudioStop != null) {
            Log.i("MadClock", "Overlapping alarm consumed without interrupting audio: alarm=$alarmId")
            return
        }
        start(context, due, now)
    }
    fun test(context: Context, alarm: AlarmRecord): Boolean {
        val store = AlarmStore(context)
        if (store.activeAlarm != null || store.pendingAudioStop != null) return false
        return start(context, alarm, System.currentTimeMillis(), test = true)
    }
    private fun start(context: Context, alarm: AlarmRecord, now: Long, test: Boolean = false): Boolean {
        val store = AlarmStore(context)
        val code = store.barcode?.takeIf { it.isNotBlank() } ?: return false
        val song = alarm.songUri?.takeIf { it.isNotBlank() } ?: return false
        val active = ActiveAlarm(UUID.randomUUID().toString(), if (test) null else alarm.id, song, code, alarm.volumePercent, now)
        if (!store.activate(active)) return false
        if (!AudioBridge.start(context, active)) Log.w("MadClock", "Audio start unavailable; active occurrence retained")
        return true
    }
    fun stopAfterScan(context: Context, id: String, code: String): Boolean {
        if (!AlarmStore(context).finishAlarm(id, code)) return false
        AudioBridge.flushPendingStop(context)
        return true
    }
    fun onVisibleLaunch(context: Context) {
        AudioBridge.flushPendingStop(context)
        val store = AlarmStore(context)
        if (store.needsRestore) AlarmScheduler.restoreAll(context)
        if (store.pendingAudioStop == null) store.activeAlarm?.let { AudioBridge.start(context, it) }
    }
}
