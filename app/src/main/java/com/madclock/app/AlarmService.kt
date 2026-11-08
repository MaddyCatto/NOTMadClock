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
import android.os.Process
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
    private var session: ActiveAlarm? = null
    private var stoppedByScan = false

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val enforceVolume = object : Runnable {
        override fun run() {
            if (!isRinging || finished) return
            val active = session ?: return
            try {
                if (!audioManager.isVolumeFixed) {
                    val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    val target = (maximum * active.volumePercent / 100.0)
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
        Log.i("MadClock", "Service created: pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = AlarmStore(this)
        val active = store.activeAlarm
        Log.i("MadClock", "Service command ${intent?.action ?: "sticky restart"}; active=${active != null}")
        if (intent?.action == ACTION_STOP) {
            if (active != null && intent.getStringExtra(EXTRA_SESSION) == active.id &&
                store.finishAlarm(active.id, intent.getStringExtra(EXTRA_BARCODE))) {
                // Persist completion before cancelling the ticket or releasing resources.
                stoppedByScan = true
                originalVolume = active.originalVolume
                runCatching { AlarmScheduler.cancelRecovery(this, active) }
                    .onFailure { Log.w("MadClock", "Cannot cancel continuation; saved session is cleared", it) }
                handler.removeCallbacksAndMessages(null)
                isRinging = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            if (!isRinging) stopSelf(startId)
            return if (isRinging) START_STICKY else START_NOT_STICKY
        }
        // Null is the framework's sticky restart. Explicit starts must match the saved session;
        // a late recovery command must never recreate an alarm already completed by a scan.
        val allowed = intent == null ||
            (intent.action == ACTION_START && intent.getStringExtra(EXTRA_SESSION) == active?.id &&
                (!intent.hasExtra(EXTRA_RECOVERY_TOKEN) ||
                    (intent.getStringExtra(EXTRA_RECOVERY_TOKEN) == active?.recoveryToken &&
                        active?.recoveryDelivered == true)))
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!allowed) {
            Log.i("MadClock", "Service command ignored: stale session or recovery token")
            if (!isRinging) stopSelf(startId)
            return if (isRinging) START_STICKY else START_NOT_STICKY
        }
        if (!isRinging) startRinging(active)
        else if (active.recoveryToken != null &&
            runCatching { player?.isPlaying == true }.getOrDefault(false)) {
            // The task was removed but Android kept this player alive. No second player is needed.
            AlarmScheduler.playbackStarted(this, active.id)
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun startRinging(active: ActiveAlarm) {
        session = active
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
            originalVolume = active.originalVolume
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MadClock:AlarmPlayback")
                .apply { acquire() }
            requestAudioFocus()
            enforceVolume.run()
            val selected = active.songUri?.let(Uri::parse)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("MadClock", "Task removed; ringing=$isRinging pid=${Process.myPid()}")
        // stopWithTask=false leaves this FGS playing. Some OEMs kill it anyway; this single
        // exact-alarm continuation may help only if the OS delivers this callback and alarm.
        AlarmStore(this).activeAlarm?.let {
            AlarmScheduler.recoverAfterTaskRemoval(this, it.id)
        }
        super.onTaskRemoved(rootIntent)
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
                if (isRinging && !finished && ready === player) {
                    try {
                        ready.start()
                        session?.let { AlarmScheduler.playbackStarted(this@AlarmService, it.id) }
                    } catch (error: RuntimeException) {
                        Log.w("MadClock", "Player start failed; recovery remains in flight", error)
                        if (!fallbackActive) handler.post { playFallback() }
                        else handler.post { stopSelf() }
                    }
                }
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
                if (!fallbackActive) handler.post { playFallback() }
                else {
                    Log.e("MadClock", "Fallback preparation failed; session retained for visible resume")
                    handler.post { stopSelf() }
                }
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
        Log.i("MadClock", "Service destroyed; completedByScan=$stoppedByScan")
        finished = true
        isRinging = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        wakeLock?.let { if (it.isHeld) it.release() }
        // A destroyed service is not a completed alarm. Retain the durable session and volume
        // baseline across process death; only a verified scan restores system volume.
        originalVolume?.takeIf { stoppedByScan }?.let { before ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.madclock.app.START"
        const val ACTION_STOP = "com.madclock.app.STOP_AFTER_SCAN"
        const val EXTRA_BARCODE = "barcode"
        const val EXTRA_SESSION = "session"
        const val EXTRA_RECOVERY_TOKEN = "recovery_token"
        private const val CHANNEL_ID = "madclock_alarm_v1"
        private const val NOTIFICATION_ID = 711
        @Volatile var isRinging: Boolean = false
            private set

        /** Only call from a visible Activity, an exact-alarm receiver, or let Android restart us. */
        fun resume(context: Context, sessionId: String, recoveryToken: String? = null): Boolean = try {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_SESSION, sessionId)
                    .apply { if (recoveryToken != null) putExtra(EXTRA_RECOVERY_TOKEN, recoveryToken) }
            )
            Log.i("MadClock", "Service start requested: session=$sessionId recovery=${recoveryToken != null}")
            true
        } catch (error: RuntimeException) {
            // A rejected start leaves state intact for the next user-visible launch. No retry loop.
            Log.w("MadClock", "Alarm resume rejected; open the app to retry", error)
            false
        }
    }
}
