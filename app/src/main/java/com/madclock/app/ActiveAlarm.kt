package com.madclock.app

enum class SignalMode { NOTIFICATIONS, AUDIO_APP }
enum class AlarmKind { DAILY, TEST }

/** Immutable selection for one scheduled run. The active run does not follow future settings. */
data class ActiveAlarm(
    val id: String,
    val songUri: String?,
    val barcode: String,
    val volumePercent: Int,
    val mode: SignalMode,
    val kind: AlarmKind,
    val startAtMs: Long,
    val originalVolume: Int? = null,
    val audioQuietConfirmed: Boolean = false,
    val lastNotificationSlot: Int = -1
)
