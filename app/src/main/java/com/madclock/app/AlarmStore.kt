package com.madclock.app

import android.content.Context
import android.util.Log
import com.madclock.ipc.InputChecks
import org.json.JSONArray
import org.json.JSONObject

/** Up to ten records, one durable sounding occurrence, and a durable stop acknowledgement. All mutations use main thread. */
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    init { AlarmMigration.upgrade(context, prefs) }
    var barcode: String?
        get() = prefs.getString("barcode", null)?.takeIf(InputChecks::barcode)
        set(value) {
            require(value == null || InputChecks.barcode(value))
            check(prefs.edit().putString("barcode", value).commit())
        }
    val alarms: List<AlarmRecord> get() {
        val array = runCatching { JSONArray(prefs.getString("alarms_v3", "[]")) }.getOrElse {
            Log.w("MadClock", "Invalid saved alarm list"); return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            runCatching { decodeRecord(array.getJSONObject(index)).also { require(validRecord(it)) } }
                .getOrElse { Log.w("MadClock", "Invalid saved alarm record"); null }
        }.distinctBy { it.id }.take(MAX_ALARMS)
    }
    fun find(id: String) = alarms.firstOrNull { it.id == id }
    fun put(alarm: AlarmRecord): Boolean {
        if (!validRecord(alarm)) return false
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
        runCatching {
            val j = JSONObject(raw)
            ActiveAlarm(j.getString("id"), j.optString("alarmId").takeIf { it.isNotEmpty() },
                j.getString("song"), j.getString("barcode"), j.getInt("volume"), j.getLong("startedAtMs"))
                .also { require(validActive(it)) }
        }.getOrElse { Log.w("MadClock", "Invalid saved active session"); null }
    }
    val pendingAudioStop: String? get() = prefs.getString("pending_audio_stop", null)?.takeIf(InputChecks::session)
    val pendingAudioStopUri: String? get() = prefs.getString("pending_audio_stop_uri", null)?.takeIf(InputChecks::song)
    fun activate(active: ActiveAlarm): Boolean {
        if (!validActive(active) || activeAlarm != null || pendingAudioStop != null) return false
        return prefs.edit().putString("active_session_v3", JSONObject().apply {
            put("id", active.id); put("alarmId", active.alarmId ?: ""); put("song", active.songUri)
            put("barcode", active.barcode); put("volume", active.volumePercent); put("startedAtMs", active.startedAtMs)
        }.toString()).commit()
    }
    fun finishAlarm(id: String, code: String): Boolean {
        val active = activeAlarm ?: return false
        if (active.id != id || active.barcode != code) return false
        return prefs.edit().remove("active_session_v3").putString("pending_audio_stop", id)
            .putString("pending_audio_stop_uri", active.songUri).commit()
    }
    fun acknowledgeStop(id: String) {
        if (pendingAudioStop == id) prefs.edit().remove("pending_audio_stop").remove("pending_audio_stop_uri").commit()
    }
    val needsRestore: Boolean get() = prefs.getBoolean("restore_v3", false)
    fun restored() { prefs.edit().putBoolean("restore_v3", false).commit() }
    companion object {
        const val PREFS = "madclock_preferences"
        const val MAX_ALARMS = 10
        private fun validRecord(a: AlarmRecord) = InputChecks.session(a.id) && a.hour in 0..23 && a.minute in 0..59 &&
            a.volumePercent in 1..100 && (a.songUri == null || InputChecks.song(a.songUri)) &&
            (a.generation == null || InputChecks.session(a.generation)) && (a.nextAtMs == null || a.nextAtMs > 0)
        private fun validActive(a: ActiveAlarm) = InputChecks.session(a.id) && InputChecks.song(a.songUri) &&
            InputChecks.barcode(a.barcode) && a.volumePercent in 1..100 &&
            (a.alarmId == null || InputChecks.session(a.alarmId))
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
