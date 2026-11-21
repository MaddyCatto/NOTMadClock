package com.madclock.ipc

import java.util.Locale

enum class SleepSound {
    WHITE, PINK, BROWN, BLUE, VIOLET, GRAY, GREEN, SOFT_WHITE, WARM_PINK, DEEP_BROWN;

    val id: String get() = name.lowercase(Locale.ROOT)
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

data class SleepSettings(
    val sound: SleepSound = SleepSound.WHITE,
    val volume: Int = 70,
    val timerMinutes: Int = 0
) {
    companion object {
        val timerOptions = listOf(0, 15, 30, 60, 120)
    }
}
