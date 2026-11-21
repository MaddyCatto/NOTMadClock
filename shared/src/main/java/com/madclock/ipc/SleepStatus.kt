package com.madclock.ipc

import android.os.Bundle
import com.madclock.ipc.AudioContract as C

data class SleepStatus(val ticket: String, val phase: Phase, val sound: SleepSound?) {
    enum class Phase { IDLE, PREPARING, PLAYING, ALARM }

    val running: Boolean get() = phase == Phase.PREPARING || phase == Phase.PLAYING

    fun toBundle() = Bundle().apply {
        putString(C.SLEEP_TICKET, ticket)
        putString(C.SLEEP_PHASE, phase.name)
        putString(C.SLEEP_SOUND, sound?.id)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): SleepStatus? = runCatching {
            val ticket = requireNotNull(bundle?.getString(C.SLEEP_TICKET))
            require(InputChecks.session(ticket))
            val phase = Phase.valueOf(requireNotNull(bundle.getString(C.SLEEP_PHASE)))
            val sound = SleepSound.fromId(bundle.getString(C.SLEEP_SOUND))
            SleepStatus(ticket, phase, sound).also { require(it.running == (sound != null)) }
        }.getOrNull()
    }
}
