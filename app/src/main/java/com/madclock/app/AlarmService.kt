package com.madclock.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlin.math.roundToInt

/** One continuously looping alarm sound. Only the configured barcode stops it inside the app. */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalVolume: Int? = null
    private var fallbackActive = false
    private var finished = false

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val enforceVolume = object : Runnable {
        override fun run() {
            if (!isRinging || finished) return
            val store = AlarmStore(this@AlarmService)
            try {
                if (!audioManager.isVolumeFixed) {
                    val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    val target = (maximum * store.volumePercent / 100.0)
                        .roundToInt().coerceIn(1, maximum)
                    val actual = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (actual < target) {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                    }
                }
            } catch (error: SecurityException) {
                Log.w("MadClock", "Volume control blocked by device policy", error)
            } catch (error: RuntimeException) {
                Log.w("MadClock", "Volume check failed", error)
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val scannedValue = intent.getStringExtra(EXTRA_BARCODE)
                val expected = AlarmStore(this).barcode
                if (!expected.isNullOrEmpty() && scannedValue == expected) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRinging) return START_NOT_STICKY
                startRinging()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startRinging() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            isRinging = true
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MadClock:AlarmPlayback")
                .apply { acquire() }
            requestAudioFocus()
            enforceVolume.run()
            val selected = AlarmStore(this).getSongUri()
            if (selected != null) {
                playUri(selected)
            } else {
                playFallback()
            }
        } catch (error: Exception) {
            Log.e("MadClock", "Cannot start alarm", error)
            stopSelf()
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(alarmAttributes)
                .setOnAudioFocusChangeListener { /* alarm playback continues until scan */ }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    private fun playUri(uri: Uri) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(alarmAttributes)
            isLooping = true
            setOnPreparedListener { ready ->
                if (isRinging && !finished) ready.start()
            }
            setOnErrorListener { _, what, extra ->
                Log.w("MadClock", "Audio playback error: $what/$extra")
                if (!fallbackActive) handler.post { playFallback() } else handler.post { stopSelf() }
                true
            }
            try {
                setDataSource(this@AlarmService, uri)
                prepareAsync()
            } catch (error: Exception) {
                Log.w("MadClock", "Selected file unavailable, using fallback", error)
                handler.post { playFallback() }
            }
        }
    }

    private fun playFallback() {
        if (finished || !isRinging) return
        fallbackActive = true
        playUri(Uri.parse("android.resource://$packageName/${R.raw.fallback_alarm}"))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Активный будильник MadClock",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Экран активного будильника. Само уведомление не издаёт звук."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val showAlarm = PendingIntent.getActivity(
            this, 1104,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("MadClock — будильник звучит")
            .setContentText("Чтобы остановить звук, открой сканер штрихкода")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(showAlarm)
            .setFullScreenIntent(showAlarm, true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        finished = true
        isRinging = false
        handler.removeCallbacks(enforceVolume)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        wakeLock?.let { if (it.isHeld) it.release() }
        // Restore the previous system alarm volume after a normal stop.
        originalVolume?.let { before ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.madclock.app.START"
        const val ACTION_STOP = "com.madclock.app.STOP_AFTER_SCAN"
        const val EXTRA_BARCODE = "barcode"
        private const val CHANNEL_ID = "madclock_alarm_v1"
        private const val NOTIFICATION_ID = 711
        @Volatile var isRinging: Boolean = false
            private set
    }
}
