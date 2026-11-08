package com.madclock.app

/** Snapshot of the alarm which actually fired, independent of tomorrow's settings. */
data class ActiveAlarm(
    val id: String,
    val songUri: String?,
    val barcode: String,
    val volumePercent: Int,
    val originalVolume: Int,
    val recoveryAttempted: Boolean = false
)
