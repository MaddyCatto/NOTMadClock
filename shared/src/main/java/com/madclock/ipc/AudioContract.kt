package com.madclock.ipc

/** Identical wire contract compiled into both APKs; no preferences or process state is shared. */
object AudioContract {
    const val PACKAGE = "com.madclock.audio"
    const val SERVICE = "com.madclock.audio.AudioService"
    const val SETUP = "com.madclock.audio.SetupActivity"
    const val PERMISSION = "com.madclock.audio.permission.CONTROL_AUDIO"
    const val START = "com.madclock.audio.START"
    const val STOP = "com.madclock.audio.STOP"
    const val SESSION = "session"
    const val VOLUME = "volume"
    const val ACK = "ack"
    const val OK = 1
    const val FAILED = 0
}
