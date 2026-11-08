package com.madclock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log

/** Requests system notification sound only. This package never opens an audio player. */
object NotificationSignals {
    const val CHANNEL = "madclock_system_alarm_v1"
    const val ID = 711
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MadClock A — системный сигнал", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(Uri.parse("android.resource://${context.packageName}/raw/glass_signal_alarm_60s"),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(false)
                description = "Эксперимент A: 20 событий с шагом 30 секунд; длительность звука определяет Android."
            })
    }
    fun post(context: Context, slot: Int) {
        channel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL)
        if (!manager.areNotificationsEnabled() || channel.importance == NotificationManager.IMPORTANCE_NONE || channel.sound == null) {
            Log.w("MadClock", "Mode A slot=$slot blocked: notifications/channel sound disabled")
            return
        }
        val show = PendingIntent.getActivity(context, 1104, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID,
            Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("MadClock A — сигнал ${slot + 1}/20")
                .setContentText("Выключение — сканированием зарегистрированного штрихкода")
                .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(false)
                .setContentIntent(show).setFullScreenIntent(show, true).build())
        Log.i("MadClock", "Mode A system sound requested: slot=$slot")
    }
    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancelAll()
}
