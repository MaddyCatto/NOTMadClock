package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EVENT) return // Legacy playback/recovery commands are inert.
        val id = intent.getStringExtra("session") ?: return
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra("kind") ?: "") }.getOrNull() ?: return
        val pending = goAsync()
        SignalController.onEvent(context, id, kind, intent.getIntExtra("slot", -1)) { pending.finish() }
    }
    companion object { const val ACTION_EVENT = "com.madclock.app.SIGNAL_EVENT_V2" }
}
