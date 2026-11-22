package com.madclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val id = intent.getStringExtra("alarm_id") ?: return
        val generation = intent.getStringExtra("generation") ?: return
        SignalController.onAlarm(context, id, generation)
    }
    companion object { const val ACTION_ALARM = "com.madclock.app.ALARM_V3" }
}
