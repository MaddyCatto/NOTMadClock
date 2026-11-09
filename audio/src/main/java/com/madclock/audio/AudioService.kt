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
import android.os.ResultReceiver
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MadClock Audio — воспроизведение", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("MadClockAudio", "Service command received; sticky=${intent == null}")
        promote() // Including cold STOP commands sent with startForegroundService.
        val action = intent?.action
        // Android enforces the service's signature permission before delivery. Validate data too.
        val id = try { intent?.getStringExtra(C.SESSION) } catch (_: RuntimeException) { null }
        if (intent != null && !InputChecks.session(id)) return stopIdle(startId)
        if (intent != null && action == C.STOP) {
            val active = store.active
            val ok = id != null && store.finish(id)
            if (ok && active != null && active.id == id) {
                releasePlayback()
                restoreVolume(active)
                cache(active.id).delete()
            }
            ack(intent, ok)
            Log.i("MadClockAudio", "Stop acknowledged=$ok session=$id")
            if (store.active == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return if (store.active == null) START_NOT_STICKY else START_STICKY
        }
        if (intent != null && action != C.START) return stopIdle(startId)
        if (intent != null) {
            val volume = try { intent.getIntExtra(C.VOLUME, -1) } catch (_: RuntimeException) { -1 }
            if (id == null || !InputChecks.song(intent.dataString) || volume !in 1..100 ||
                store.completed(id)) return stopIdle(startId)
            val old = store.active
            // Main never starts a different occurrence until STOP was acknowledged. A delayed
            // or conflicting START must not interrupt the current alarm, even from a trusted peer.
            if (old != null && old.id != id) {
                Log.w("MadClockAudio", "Conflicting START ignored while a session is active")
                return START_STICKY
            }
            if (old?.id != id) {
                val new = AudioSession(id, intent.data.toString(), volume,
                    audio.getStreamVolume(AudioManager.STREAM_ALARM))
                if (!store.begin(new)) return stopIdle(startId)
            }
        }
        val active = store.active ?: return stopIdle(startId)
        if (player != null || loading == active.id) return START_STICKY
        val song = cache(active.id)
        if (song.exists()) {
            if (song.length() in 1..SongImport.MAX_BYTES) play(Uri.fromFile(song), active.id)
            else { song.delete(); playFallback(active.id) }
        } else importSong(active)
        return START_STICKY
    }
    private fun stopIdle(startId: Int): Int {
        if (store.active == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        return if (store.active == null) START_NOT_STICKY else START_STICKY
    }
    private fun cache(id: String) = File(filesDir, "song-$id.audio")
    private fun importSong(active: AudioSession) {
        loading = active.id
        val generation = ++epoch
        imports.execute {
            val temp = File(filesDir, "import-${active.id}-$generation.tmp")
            val copied = runCatching {
                contentResolver.openInputStream(Uri.parse(active.source))!!.use { input ->
                    temp.outputStream().use { out -> SongImport.copy(input, out,
                        { Thread.currentThread().isInterrupted || epoch != generation }) }
                }
                check(temp.renameTo(cache(active.id)))
            }
            temp.delete()
            handler.post {
                if (destroyed || epoch != generation || store.active?.id != active.id) {
                    if (store.active?.id != active.id) cache(active.id).delete()
                    return@post
                }
                loading = null
                if (copied.isSuccess) play(Uri.fromFile(cache(active.id)), active.id)
                else {
                    Log.w("MadClockAudio", "URI import failed; using bundled fallback")
                    playFallback(active.id)
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
            setOnErrorListener { _, what, extra ->
                playbackFailed(id, IllegalStateException("MediaPlayer $what/$extra")); true
            }
            try { setDataSource(this@AudioService, uri); prepareAsync() }
            catch (e: Exception) { handler.post { playbackFailed(id, e) } }
        }
    }
    private fun playbackFailed(id: String, error: Throwable) {
        Log.e("MadClockAudio", "Playback failed: ${error.javaClass.simpleName}")
        if (!fallback) handler.post { playFallback(id) } else stopSelf()
    }
    private fun playFallback(id: String) {
        if (destroyed || store.active?.id != id) return
        fallback = true
        play(Uri.parse("android.resource://$packageName/${R.raw.fallback_alarm}"), id)
    }
    private fun promote() {
        val show = PendingIntent.getActivity(this, 7,
            Intent().setComponent(android.content.ComponentName("com.madclock.app", "com.madclock.app.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("MadClock Audio").setContentText("Остановка — штрихкодом в MadClock")
            .setOngoing(true).setContentIntent(show).setCategory(Notification.CATEGORY_ALARM).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(711, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(711, notification)
    }
    @Suppress("DEPRECATION")
    private fun ack(intent: Intent, ok: Boolean) {
        try { intent.getParcelableExtra<ResultReceiver>(C.ACK)?.send(if (ok) C.OK else C.FAILED, null) }
        catch (_: RuntimeException) { Log.w("MadClockAudio", "Invalid or unavailable stop acknowledgement") }
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
        releasePlayback()
        imports.shutdownNow()
        // Keep the helper's own durable state for an Android-managed sticky restart, not a task watchdog.
        super.onDestroy()
    }
    companion object { const val CHANNEL = "madclock_audio_silent_v1" }
}
