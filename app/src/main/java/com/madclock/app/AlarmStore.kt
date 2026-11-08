package com.madclock.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE)
    init { SignalMigration.upgrade(context, prefs) }

    var hour: Int
        get() = prefs.getInt("hour", 7)
        set(value) = prefs.edit().putInt("hour", value.coerceIn(0, 23)).apply()
    var minute: Int
        get() = prefs.getInt("minute", 0)
        set(value) = prefs.edit().putInt("minute", value.coerceIn(0, 59)).apply()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
    var songUri: String?
        get() = prefs.getString("song_uri", null)
        set(value) = prefs.edit().putString("song_uri", value).apply()
    var songName: String?
        get() = prefs.getString("song_name", null)
        set(value) = prefs.edit().putString("song_name", value).apply()
    var barcode: String?
        get() = prefs.getString("barcode", null)
        set(value) = prefs.edit().putString("barcode", value).apply()
    var volumePercent: Int
        get() = prefs.getInt("volume_percent", 80)
        set(value) = prefs.edit().putInt("volume_percent", value.coerceIn(1, 100)).apply()
    var mode: SignalMode
        get() = SignalMode.valueOf(prefs.getString("signal_mode", SignalMode.NOTIFICATIONS.name)!!)
        set(value) = prefs.edit().putString("signal_mode", value.name).apply()

    fun isConfigured(): Boolean = !barcode.isNullOrBlank() &&
        (mode == SignalMode.NOTIFICATIONS || !songUri.isNullOrBlank())
    fun getSongUri(): Uri? = songUri?.let(Uri::parse)
    val activeAlarm: ActiveAlarm? get() = decode(prefs.getString("active_alarm", null))
    val pendingAudioStop: String? get() = prefs.getString("pending_audio_stop", null)
    fun plan(kind: AlarmKind): ActiveAlarm? = decode(prefs.getString("plan_${kind.name}", null))
    fun newPlan(kind: AlarmKind, at: Long) = ActiveAlarm(
        UUID.randomUUID().toString(), songUri, barcode!!, volumePercent, mode, kind, at)
    fun savePlan(kind: AlarmKind, plan: ActiveAlarm?): Boolean = prefs.edit().apply {
        if (plan == null) remove("plan_${kind.name}") else putString("plan_${kind.name}", encode(plan))
    }.commit()

    fun activate(plan: ActiveAlarm): ActiveAlarm? {
        activeAlarm?.let { return if (it.id == plan.id) it else null }
        if (pendingAudioStop != null) return null
        val edit = prefs.edit().putString("active_alarm", encode(plan))
        if (plan.kind == AlarmKind.TEST) edit.remove("plan_TEST")
        return if (edit.commit()) plan else null
    }
    fun updateActive(active: ActiveAlarm): Boolean {
        if (activeAlarm?.id != active.id) return false
        return prefs.edit().putString("active_alarm", encode(active)).commit()
    }
    fun finishAlarm(id: String, code: String): ActiveAlarm? {
        val active = activeAlarm ?: return null
        if (active.id != id || active.barcode != code) return null
        val edit = prefs.edit().remove("active_alarm")
        if (active.mode == SignalMode.AUDIO_APP) edit.putString("pending_audio_stop", active.id)
        return if (edit.commit()) active else null
    }
    fun acknowledgeStop(id: String) {
        if (pendingAudioStop == id) prefs.edit().remove("pending_audio_stop").commit()
    }

    private fun decode(raw: String?): ActiveAlarm? = raw?.let {
        val j = JSONObject(it)
        ActiveAlarm(j.getString("id"), j.optString("song").takeIf(String::isNotEmpty),
            j.getString("barcode"), j.getInt("volume"), SignalMode.valueOf(j.getString("mode")),
            AlarmKind.valueOf(j.getString("kind")), j.getLong("startAtMs"),
            if (j.has("originalVolume")) j.getInt("originalVolume") else null,
            j.optBoolean("audioQuietConfirmed"), j.optInt("lastNotificationSlot", -1))
    }
    private fun encode(a: ActiveAlarm) = JSONObject().apply {
        put("id", a.id); put("song", a.songUri ?: ""); put("barcode", a.barcode)
        put("volume", a.volumePercent); put("mode", a.mode.name); put("kind", a.kind.name)
        put("startAtMs", a.startAtMs); a.originalVolume?.let { put("originalVolume", it) }
        put("audioQuietConfirmed", a.audioQuietConfirmed); put("lastNotificationSlot", a.lastNotificationSlot)
    }.toString()
}
