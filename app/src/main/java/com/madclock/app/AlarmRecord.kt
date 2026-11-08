package com.madclock.app

import java.util.UUID

enum class AlarmRepeat { DAILY, ONCE }

data class AlarmRecord(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 7,
    val minute: Int = 0,
    val repeat: AlarmRepeat = AlarmRepeat.DAILY,
    val enabled: Boolean = true,
    val songUri: String? = null,
    val songName: String? = null,
    val volumePercent: Int = 80,
    val nextAtMs: Long? = null,
    val generation: String? = null
) {
    fun timeLabel() = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
}

/** UUID belongs to one sounding occurrence, not to the persistent alarm: helper tombstones are per occurrence. */
data class ActiveAlarm(
    val id: String,
    val alarmId: String?,
    val songUri: String,
    val barcode: String,
    val volumePercent: Int,
    val startedAtMs: Long
)
