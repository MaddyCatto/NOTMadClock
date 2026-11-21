package com.madclock.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.SleepSettings
import com.madclock.ipc.SleepSound
import com.madclock.ipc.SleepStatus
import com.madclock.ipc.SleepStatus.Phase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** All commands and state transitions run on Audio's main looper. Only local asset reads run off it. */
internal class SleepPlayback(private val context: Context, private val onFinished: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var load: Future<*>? = null
    private var generation = 0
    private var track: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var settings: SleepSettings? = null
    private var deadline: Long? = null
    private var lastUpdate = 0L
    private val timer = Runnable { updateTimer() }

    val running: Boolean get() = settings != null

    fun command(intent: Intent): Boolean {
        val token = runCatching { intent.getStringExtra(C.SLEEP_TICKET) }.getOrNull()
        if (token != state.ticket || intent.data != null) return false
        if (intent.action == C.SLEEP_STOP) return stop()

        val sentAt = runCatching { intent.getLongExtra(C.SLEEP_SENT_AT, -1) }.getOrDefault(-1)
        val now = SystemClock.elapsedRealtimeNanos()
        if (sentAt <= lastUpdate || sentAt > now || now - sentAt > MAX_COMMAND_AGE_NANOS) return false
        val value = readSettings(intent) ?: return false
        val previous = settings
        when (intent.action) {
            C.SLEEP_PLAY -> if (previous != null) return false
            C.SLEEP_UPDATE -> if (previous == null) return false
            else -> return false
        }
        if (deadline?.let { it <= SystemClock.elapsedRealtime() } == true) {
            finish()
            return false
        }
        lastUpdate = sentAt
        settings = value
        if (value.timerMinutes != previous?.timerMinutes)
            deadline = value.timerMinutes.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        if (value.sound != previous?.sound) {
            if (previous == null) rotateTicket()
            loadSound(value.sound)
        }
        updateTimer()
        return true
    }

    private fun readSettings(intent: Intent): SleepSettings? = runCatching {
        val sound = requireNotNull(SleepSound.fromId(intent.getStringExtra(C.SLEEP_SOUND)))
        val volume = intent.getIntExtra(C.VOLUME, -1)
        val minutes = intent.getIntExtra(C.SLEEP_TIMER, -1)
        require(volume in 0..100 && minutes in SleepSettings.timerOptions)
        SleepSettings(sound, volume, minutes)
    }.getOrNull()

    private fun loadSound(sound: SleepSound) {
        val current = ++generation
        load?.cancel(true)
        releaseTrack()
        publish(Phase.PREPARING, sound)
        load = loader.submit {
            val samples = runCatching { SleepPcm.read(context.resources, sound) }
            handler.post {
                if (generation == current && running) {
                    load = null
                    runCatching { startTrack(samples.getOrThrow()) }.onFailure {
                        Log.w("MadClockAudio", "White noise playback failed: ${it.javaClass.simpleName}")
                        finish()
                    }
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startTrack(samples: ByteArray) {
        if (deadline?.let { it <= SystemClock.elapsedRealtime() } == true) {
            finish()
            return
        }
        val current = SleepPcm.track(samples)
        track = current
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MadClockAudio:Sleep").apply { acquire() }
        current.setVolume(gain())
        current.play()
        publish(Phase.PLAYING, settings!!.sound)
    }

    private fun gain(remaining: Long? = deadline?.minus(SystemClock.elapsedRealtime())): Float {
        val fade = if (remaining == null) 1f else (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
        return (settings?.volume ?: 0) / 100f * fade
    }

    private fun updateTimer() {
        handler.removeCallbacks(timer)
        val remaining = deadline?.minus(SystemClock.elapsedRealtime())
        if (remaining != null && remaining <= 0) {
            finish()
            return
        }
        track?.setVolume(gain(remaining))
        if (remaining != null) {
            val delay = if (remaining > FADE_MS) remaining - FADE_MS else minOf(remaining, 100L)
            handler.postDelayed(timer, delay)
        }
    }

    private fun finish() {
        if (stop()) onFinished()
    }

    fun stop(): Boolean {
        if (!running) return false
        ++generation
        load?.cancel(true)
        load = null
        handler.removeCallbacks(timer)
        releaseTrack()
        settings = null
        deadline = null
        lastUpdate = 0L
        rotateTicket()
        publish(Phase.IDLE)
        return true
    }

    fun alarmChanged() {
        // Invalidate noise commands issued before the alarm or while it was sounding.
        if (!stop()) {
            rotateTicket()
            publish(Phase.IDLE)
        }
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.setVolume(0f); it.stop() }
            runCatching { it.release() }
        }
        track = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun close() {
        stop()
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
    }

    private fun rotateTicket() {
        state = state.copy(ticket = UUID.randomUUID().toString())
    }

    private fun publish(phase: Phase, sound: SleepSound? = null) {
        state = state.copy(phase = phase, sound = sound)
        context.sendBroadcast(Intent(C.SLEEP_CHANGED).setPackage(C.MAIN_PACKAGE), C.PERMISSION)
    }

    companion object {
        internal const val FADE_MS = 20_000L
        private const val MAX_COMMAND_AGE_NANOS = 10_000_000_000L
        // This token survives an idle service stopping, but cannot be replayed after Audio's process dies.
        private var state = SleepStatus(UUID.randomUUID().toString(), Phase.IDLE, null)

        fun status(context: Context): SleepStatus =
            if (AudioStore(context).active != null) state.copy(phase = Phase.ALARM, sound = null) else state
    }
}
