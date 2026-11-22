package com.madclock.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import com.madclock.ipc.AudioContract as C

/** A read-only snapshot in the Audio process. Querying idle state never creates a service. */
class SleepStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == C.SLEEP_STATUS) reply(intent, true, SleepPlayback.status(context).toBundle())
    }
}

@Suppress("DEPRECATION")
internal fun reply(intent: Intent, ok: Boolean, data: Bundle? = null) {
    try { intent.getParcelableExtra<ResultReceiver>(C.ACK)?.send(if (ok) C.OK else C.FAILED, data) }
    catch (_: RuntimeException) { Log.w("MadClockAudio", "Invalid or unavailable acknowledgement") }
}
