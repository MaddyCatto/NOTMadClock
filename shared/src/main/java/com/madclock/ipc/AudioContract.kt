package com.madclock.ipc

/** Identical wire contract compiled into both APKs; no preferences or process state is shared. */
object AudioContract {
    const val MAIN_PACKAGE = "com.madclock.app"
    const val PACKAGE = "com.madclock.audio"
    const val SERVICE = "com.madclock.audio.AudioService"
    const val SETUP = "com.madclock.audio.SetupActivity"
    const val PERMISSION = "com.madclock.audio.permission.CONTROL_AUDIO"
    const val START = "com.madclock.audio.START"
    const val STOP = "com.madclock.audio.STOP"
    const val DEFAULT_ALARM = "default_alarm"
    const val SLEEP_STATUS_RECEIVER = "com.madclock.audio.SleepStatusReceiver"
    const val SLEEP_STATUS = "com.madclock.audio.SLEEP_STATUS"
    const val SLEEP_CHANGED = "com.madclock.audio.SLEEP_CHANGED"
    const val SLEEP_PLAY = "com.madclock.audio.SLEEP_PLAY"
    const val SLEEP_UPDATE = "com.madclock.audio.SLEEP_UPDATE"
    const val SLEEP_STOP = "com.madclock.audio.SLEEP_STOP"
    const val SLEEP_TICKET = "sleep_ticket"
    const val SLEEP_PHASE = "sleep_phase"
    const val SLEEP_SENT_AT = "sleep_sent_at"
    const val SLEEP_SOUND = "sleep_sound"
    const val SLEEP_TIMER = "sleep_timer"
    const val SESSION = "session"
    const val VOLUME = "volume"
    const val ACK = "ack"
    const val OK = 1
    const val FAILED = 0
}
