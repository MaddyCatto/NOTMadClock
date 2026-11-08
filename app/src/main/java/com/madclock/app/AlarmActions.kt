package com.madclock.app

import android.content.Context

/** Transaction boundary for edits: only the addressed persistent ID is written/cancelled. */
object AlarmActions {
    fun save(context: Context, input: AlarmRecord, now: Long = System.currentTimeMillis()): Boolean {
        val store = AlarmStore(context)
        val old = store.find(input.id)
        val next = if (input.enabled) AlarmScheduler.prepared(input, AlarmScheduler.nextTime(input, now))
            else input.copy(nextAtMs = null, generation = null)
        if (!store.put(next)) return false
        if (!next.enabled) { AlarmScheduler.cancel(context, next); return true }
        if (AlarmScheduler.arm(context, next)) return true
        // Restore both the record and PendingIntent extras if the OS rejects the new schedule.
        if (old != null) {
            store.put(old)
            if (old.enabled) AlarmScheduler.arm(context, old) else AlarmScheduler.cancel(context, next)
        }
        else { store.remove(next.id); AlarmScheduler.cancel(context, next) }
        return false
    }
    fun delete(context: Context, id: String): Boolean {
        val store = AlarmStore(context)
        val old = store.find(id) ?: return false
        if (!store.remove(id)) return false
        AlarmScheduler.cancel(context, old)
        return true
    }
}
