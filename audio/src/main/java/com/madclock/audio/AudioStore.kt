package com.madclock.audio

import android.content.Context
import org.json.JSONObject

data class AudioSession(val id: String, val source: String, val volume: Int, val originalVolume: Int)

/** This file is private to com.madclock.audio, never read as main-app preferences. */
class AudioStore(context: Context) {
    private val prefs = context.getSharedPreferences("madclock_audio", Context.MODE_PRIVATE)
    val active: AudioSession? get() = prefs.getString("active", null)?.let {
        val j = JSONObject(it)
        AudioSession(j.getString("id"), j.getString("source"), j.getInt("volume"), j.getInt("original"))
    }
    fun completed(id: String) = prefs.getStringSet("completed", emptySet())!!.contains(id)
    fun begin(a: AudioSession): Boolean {
        if (completed(a.id)) return false
        return prefs.edit().putString("active", JSONObject().apply {
            put("id", a.id); put("source", a.source); put("volume", a.volume); put("original", a.originalVolume)
        }.toString()).commit()
    }
    fun finish(id: String): Boolean {
        val completed = prefs.getStringSet("completed", emptySet())!!.toMutableSet().apply { add(id) }
        val edit = prefs.edit().putStringSet("completed", completed)
        if (active?.id == id) edit.remove("active")
        // Durable tombstone rejects an old START even if it arrives after STOP or after process death.
        return edit.commit()
    }
}
