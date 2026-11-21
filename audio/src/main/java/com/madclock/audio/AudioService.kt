package com.madclock.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.InputChecks
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The only process in this project allowed to instantiate an audio player. */
class AudioService : Service() {
    private lateinit var store: AudioStore
    private lateinit var audio: AudioManager
    private lateinit var sleep: SleepPlayback
    private var latestStartId = 0
    private val handler = Handler(Looper.getMainLooper())
    private val imports = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var loading: String? = null
    @Volatile private var epoch = 0
    private var destroyed = false
    private var fallback = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val enforceVolume = object : Runnable {
        override fun run() {
            val active = store.active ?: return
            runCatching {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val target = (max * active.volume / 100.0).roundToInt().coerceIn(1, max)
                if (!audio.isVolumeFixed && audio.getStreamVolume(AudioManager.STREAM_ALARM) < target)
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }.onFailure { Log.w("MadClockAudio", "Alarm volume rejected", it) }
            handler.postDelayed(this, 1_500)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        store = AudioStore(this)
        SongImport.cleanOrphans(filesDir, store.active?.id)
        audio = getSystemService(AudioManager::class.java)
        sleep = SleepPlayback(this) { handler.post { stopIfIdle() } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MadClock Audio — воспроизведение", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // The signature permission is checked by Android before delivery. The two command
        // families then have independent identities, validation and stop paths.
        val action = intent?.action
        val sleepCommand = action == C.SLEEP_PLAY || action == C.SLEEP_UPDATE || action == C.SLEEP_STOP
        val acceptedSleep = sleepCommand && store.active == null && sleep.command(requireNotNull(intent))
        val startAlarm = intent == null || (action == C.START && acceptAlarmStart(intent))
        if (action == C.STOP) stopAlarm(intent)

        promote() // Even cold or rejected starts must satisfy startForegroundService.
        if (sleepCommand) reply(intent, acceptedSleep, SleepPlayback.status(this).toBundle())
        if (startAlarm) ensureAlarmPlaying()
        stopIfIdle()
        return if (store.active != null) START_STICKY else START_NOT_STICKY
    }

    private fun acceptAlarmStart(intent: Intent): Boolean {
        val id = sessionId(intent) ?: return false
        val volume = runCatching { intent.getIntExtra(C.VOLUME, -1) }.getOrDefault(-1)
        val useDefault = runCatching { intent.getBooleanExtra(C.DEFAULT_ALARM, false) }.getOrDefault(false)
        val source = intent.dataString
        if (volume !in 1..100 || store.completed(id) ||
            (if (useDefault) source != null else !InputChecks.song(source))) return false
        // A delayed/conflicting START cannot replace a sounding alarm. Same-session START
        // is idempotent and can also restore playback after a process restart.
        val active = store.active
        if (active != null) return active.id == id
        if (!store.begin(AudioSession(id, source, volume, audio.getStreamVolume(AudioManager.STREAM_ALARM)))) return false
        sleep.alarmChanged()
        return true
    }

    private fun stopAlarm(intent: Intent) {
        val id = sessionId(intent) ?: return
        val active = store.active
        val ok = store.finish(id)
        if (ok && active?.id == id) {
            releasePlayback()
            restoreVolume(active)
            cache(id).delete()
            sleep.alarmChanged()
        }
        reply(intent, ok)
        Log.i("MadClockAudio", "Stop acknowledged=$ok session=$id")
    }

    private fun sessionId(intent: Intent) =
        runCatching { intent.getStringExtra(C.SESSION) }.getOrNull()?.takeIf(InputChecks::session)

    private fun ensureAlarmPlaying() {
        val active = store.active ?: return
        if (player != null || loading == active.id) return
        val source = active.source
        if (source == null) {
            play(bundled(R.raw.default_alarm), active.id)
            return
        }
        val song = cache(active.id)
        if (song.exists()) {
            if (song.length() in 1..SongImport.MAX_BYTES) play(Uri.fromFile(song), active.id)
            else { song.delete(); playFallback(active.id) }
        } else importSong(active.id, source)
    }

    private fun stopIfIdle() {
        if (store.active == null && !sleep.running && stopSelfResult(latestStartId))
            stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun cache(id: String) = File(filesDir, "song-$id.audio")
    private fun importSong(id: String, source: String) {
        loading = id
        val generation = ++epoch
        imports.execute {
            val temp = File(filesDir, "import-${id}-$generation.tmp")
            val copied = runCatching {
                contentResolver.openInputStream(Uri.parse(source))!!.use { input ->
                    temp.outputStream().use { out -> SongImport.copy(input, out,
                        { Thread.currentThread().isInterrupted || epoch != generation }) }
                }
                check(temp.renameTo(cache(id)))
            }
            temp.delete()
            handler.post {
                if (destroyed || epoch != generation || store.active?.id != id) {
                    if (store.active?.id != id) cache(id).delete()
                    return@post
                }
                loading = null
                if (copied.isSuccess) play(Uri.fromFile(cache(id)), id)
                else {
                    Log.w("MadClockAudio", "URI import failed; using bundled fallback")
                    playFallback(id)
                }
            }
        }
    }
    @SuppressLint("WakelockTimeout")
    private fun play(uri: Uri, id: String) {
        if (store.active?.id != id || destroyed) return
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MadClockAudio:Playback").apply { acquire() }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
            handler.removeCallbacks(enforceVolume); enforceVolume.run()
        }
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(attributes); isLooping = true
            setOnPreparedListener { ready ->
                if (!destroyed && store.active?.id == id && ready === player) {
                    runCatching {
                        ready.start()
                        Log.i("MadClockAudio", "Playback started: session=$id fallback=$fallback")
                    }.onFailure { playbackFailed(id, it) }
                }
            }
            setOnErrorListener { failed, what, extra ->
                if (failed === player) playbackFailed(id, IllegalStateException("MediaPlayer $what/$extra"))
                true
            }
            try { setDataSource(this@AudioService, uri); prepareAsync() }
            catch (e: Exception) {
                val failed = this
                handler.post { if (failed === player) playbackFailed(id, e) }
            }
        }
    }
    private fun playbackFailed(id: String, error: Throwable) {
        Log.e("MadClockAudio", "Playback failed: ${error.javaClass.simpleName}")
        val failed = player
        if (!fallback) handler.post { if (player === failed) playFallback(id) } else stopSelf()
    }
    private fun playFallback(id: String) {
        if (destroyed || store.active?.id != id) return
        fallback = true
        play(bundled(R.raw.fallback_alarm), id)
    }
    private fun bundled(resource: Int) = Uri.parse("android.resource://$packageName/$resource")

    private fun promote() {
        val show = PendingIntent.getActivity(this, 7,
            Intent().setComponent(android.content.ComponentName("com.madclock.app", "com.madclock.app.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = store.active != null
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (alarm) R.string.notification_alarm else R.string.notification_sleep))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(show)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_TRANSPORT).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(711, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(711, notification)
    }
    private fun restoreVolume(active: AudioSession) {
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, active.originalVolume, 0) }
    }
    private fun releasePlayback() {
        ++epoch; loading = null; fallback = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() {
        destroyed = true
        sleep.close()
        releasePlayback()
        imports.shutdownNow()
        // Keep the helper's own durable state for an Android-managed sticky restart, not a task watchdog.
        super.onDestroy()
    }
    companion object { const val CHANNEL = "madclock_audio_silent_v1" }
}
