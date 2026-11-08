package com.madclock.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Up to ten records, one durable sounding occurrence, and a durable stop acknowledgement. All mutations use main thread. */
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    init { AlarmMigration.upgrade(context, prefs) }
    var barcode: String?
        get() = prefs.getString("barcode", null)
        set(value) { check(prefs.edit().putString("barcode", value).commit()) }
    val alarms: List<AlarmRecord> get() {
        val array = JSONArray(prefs.getString("alarms_v3", "[]"))
        return (0 until array.length()).map { decodeRecord(array.getJSONObject(it)) }
    }
    fun find(id: String) = alarms.firstOrNull { it.id == id }
    fun put(alarm: AlarmRecord): Boolean {
        require(alarm.hour in 0..23 && alarm.minute in 0..59 && alarm.volumePercent in 1..100)
        val list = alarms.toMutableList()
        val index = list.indexOfFirst { it.id == alarm.id }
        if (index < 0) {
            if (list.size >= MAX_ALARMS) return false
            list.add(alarm)
        } else list[index] = alarm
        return prefs.edit().putString("alarms_v3", JSONArray().apply { list.forEach { put(encodeRecord(it)) } }.toString()).commit()
    }
    fun remove(id: String): Boolean {
        val remaining = alarms.filterNot { it.id == id }
        return prefs.edit().putString("alarms_v3", JSONArray().apply { remaining.forEach { put(encodeRecord(it)) } }.toString()).commit()
    }
    val activeAlarm: ActiveAlarm? get() = prefs.getString("active_session_v3", null)?.let { raw ->
        val j = JSONObject(raw)
        ActiveAlarm(j.getString("id"), j.optString("alarmId").takeIf { it.isNotEmpty() },
            j.getString("song"), j.getString("barcode"), j.getInt("volume"), j.getLong("startedAtMs"))
    }
    val pendingAudioStop: String? get() = prefs.getString("pending_audio_stop", null)
    fun activate(active: ActiveAlarm): Boolean {
        if (activeAlarm != null || pendingAudioStop != null) return false
        return prefs.edit().putString("active_session_v3", JSONObject().apply {
            put("id", active.id); put("alarmId", active.alarmId ?: ""); put("song", active.songUri)
            put("barcode", active.barcode); put("volume", active.volumePercent); put("startedAtMs", active.startedAtMs)
        }.toString()).commit()
    }
    fun finishAlarm(id: String, code: String): Boolean {
        val active = activeAlarm ?: return false
        if (active.id != id || active.barcode != code) return false
        return prefs.edit().remove("active_session_v3").putString("pending_audio_stop", id).commit()
    }
    fun acknowledgeStop(id: String) {
        if (pendingAudioStop == id) prefs.edit().remove("pending_audio_stop").commit()
    }
    val needsRestore: Boolean get() = prefs.getBoolean("restore_v3", false)
    fun restored() { prefs.edit().putBoolean("restore_v3", false).commit() }
    companion object {
        const val PREFS = "madclock_preferences"
        const val MAX_ALARMS = 10
        internal fun encodeRecord(a: AlarmRecord) = JSONObject().apply {
            put("id", a.id); put("hour", a.hour); put("minute", a.minute); put("repeat", a.repeat.name)
            put("enabled", a.enabled); put("song", a.songUri ?: ""); put("songName", a.songName ?: "")
            put("volume", a.volumePercent); a.nextAtMs?.let { put("nextAtMs", it) }
            a.generation?.let { put("generation", it) }
        }
        private fun decodeRecord(j: JSONObject) = AlarmRecord(j.getString("id"), j.getInt("hour"), j.getInt("minute"),
            AlarmRepeat.valueOf(j.getString("repeat")), j.getBoolean("enabled"),
            j.optString("song").takeIf { it.isNotEmpty() }, j.optString("songName").takeIf { it.isNotEmpty() },
            j.getInt("volume"), if (j.has("nextAtMs")) j.getLong("nextAtMs") else null,
            j.optString("generation").takeIf { it.isNotEmpty() })
    }
}
