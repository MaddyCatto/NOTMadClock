package com.madclock.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.os.SystemClock
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.SleepSettings
import com.madclock.ipc.SleepSound
import com.madclock.ipc.SleepStatus
import com.madclock.ipc.SleepStatus.Phase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], shadows = [SleepPlaybackTest.LoopTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SleepPlaybackTest {
    private lateinit var context: Context
    private var controller: ServiceController<AudioService>? = null
    private var startId = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("madclock_audio", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun close() { controller?.destroy() }

    private fun service() = Robolectric.buildService(AudioService::class.java)
        .also { controller = it }.create().get()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun status() = SleepPlayback.status(context)
    private fun playback(s: AudioService): SleepPlayback = ReflectionHelpers.getField(s, "sleep")
    private fun track(s: AudioService): AudioTrack? = ReflectionHelpers.getField(playback(s), "track")
    private fun lock(s: AudioService): PowerManager.WakeLock? = ReflectionHelpers.getField(playback(s), "wakeLock")
    private fun alarmPlayer(s: AudioService): MediaPlayer? = ReflectionHelpers.getField(s, "player")

    private fun command(action: String, current: SleepStatus = status(), settings: SleepSettings = SleepSettings()): Intent {
        advance(1)
        return Intent(action).putExtra(C.SLEEP_TICKET, current.ticket)
            .putExtra(C.SLEEP_SENT_AT, SystemClock.elapsedRealtimeNanos())
            .putExtra(C.SLEEP_SOUND, settings.sound.id).putExtra(C.VOLUME, settings.volume)
            .putExtra(C.SLEEP_TIMER, settings.timerMinutes)
    }

    private fun send(service: AudioService, command: Intent) = service.onStartCommand(command, 0, ++startId)

    private fun loader(s: AudioService): ExecutorService = ReflectionHelpers.getField(playback(s), "loader")
    private fun load(s: AudioService) {
        loader(s).submit { }.get(5, TimeUnit.SECONDS)
        advance(1)
    }

    @Test fun catalogContainsAllTenDistinctSampleExactLoops() {
        assertEquals(10, SleepSound.entries.size)
        assertEquals(10, SleepSound.entries.map(SleepPcm::resource).toSet().size)
        for (sound in SleepSound.entries) assertEquals(SleepPcm.FRAMES * 4, SleepPcm.read(context.resources, sound).size)
    }

    @Test fun authorizedStatusSnapshotDoesNotCreateAnIdleService() {
        var reply: SleepStatus? = null
        val request = Intent(C.SLEEP_STATUS).putExtra(C.ACK, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                assertEquals(C.OK, resultCode)
                reply = SleepStatus.fromBundle(data)
            }
        })
        SleepStatusReceiver().onReceive(context, request)
        assertEquals(Phase.IDLE, reply!!.phase)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun loopsSelectedAssetAndStopReleasesTrackWakeLockAndForegroundService() {
        val s = service()
        val idle = status()
        val play = command(C.SLEEP_PLAY, idle, SleepSettings(SleepSound.BLUE, volume = 40))
        assertEquals(Service.START_NOT_STICKY, send(s, play))
        load(s)
        val active = status()
        assertEquals(Phase.PLAYING, active.phase)
        assertEquals(SleepSound.BLUE, active.sound)
        val track = track(s)!!
        val native = Shadow.extract<LoopTrackShadow>(track)
        assertEquals(listOf(0, SleepPcm.FRAMES, -1), native.loop)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertEquals(0.40f, native.gain, 0.001f)
        val held = lock(s)!!
        assertTrue(held.isHeld)
        send(s, play)
        assertSame(track, track(s))
        assertNotEquals(idle.ticket, active.ticket)
        send(s, command(C.SLEEP_STOP, active))
        assertEquals(Phase.IDLE, status().phase)
        assertNull(track(s))
        assertFalse(held.isHeld)
        assertEquals(startId, shadowOf(s).stopSelfResultId)
        assertTrue(shadowOf(s).isForegroundStopped)
        send(s, play)
        assertEquals(Phase.IDLE, status().phase)
        assertNull(lock(s))
    }

    @Test fun timerFadesAndEndsEvenAfterSoundAndVolumeChange() {
        val s = service()
        send(s, command(C.SLEEP_PLAY, settings = SleepSettings(timerMinutes = 15)))
        load(s)
        advance(600_000)
        val first = track(s)!!
        send(s, command(C.SLEEP_UPDATE, settings = SleepSettings(SleepSound.WARM_PINK, 60, 15)))
        load(s)
        val second = track(s)!!
        assertNotSame(first, second)
        assertEquals(SleepSound.WARM_PINK, status().sound)
        val native = Shadow.extract<LoopTrackShadow>(second)
        assertEquals(0.60f, native.gain, 0.001f)
        advance(290_000)
        assertTrue(native.gain in 0.28f..0.32f)
        val held = lock(s)!!
        advance(11_000)
        assertEquals(Phase.IDLE, status().phase)
        assertFalse(held.isHeld)
        assertNull(track(s))
        assertTrue(shadowOf(s).isForegroundStopped)
    }

    @Test fun choosingNoTimerCancelsExpiryAndOutOfOrderSettingsAreIgnored() {
        val s = service()
        send(s, command(C.SLEEP_PLAY, settings = SleepSettings(timerMinutes = 15)))
        load(s)
        val old = command(C.SLEEP_UPDATE, settings = SleepSettings(SleepSound.PINK, 15, 15))
        val updated = command(C.SLEEP_UPDATE, settings = SleepSettings(SleepSound.VIOLET, 55, 0))
        send(s, updated)
        send(s, old)
        load(s)
        advance(901_000)
        assertEquals(SleepSound.VIOLET, status().sound)
        assertEquals(Phase.PLAYING, status().phase)
        assertEquals(0.55f, Shadow.extract<LoopTrackShadow>(track(s)!!).gain, 0.001f)
    }

    @Test fun malformedAndExpiredStartsCannotStartWhiteNoise() {
        val s = service()
        val bad = listOf(
            command(C.SLEEP_PLAY).putExtra(C.SLEEP_SOUND, "../custom"),
            command(C.SLEEP_PLAY).putExtra(C.VOLUME, 101),
            command(C.SLEEP_PLAY).putExtra(C.SLEEP_TIMER, 1),
            command(C.SLEEP_PLAY).setData(Uri.parse("content://songs/test")),
            command(C.SLEEP_PLAY).putExtra(C.SLEEP_TICKET, "stale")
        )
        bad.forEach { send(s, it) }
        val expired = command(C.SLEEP_PLAY)
        advance(10_001)
        send(s, expired)
        assertEquals(Phase.IDLE, status().phase)
        assertNull(track(s))
        assertNull(lock(s))
    }

    @Test fun alarmImmediatelyPreemptsNoiseAndNoDelayedCommandCanStopOrResumeIt() {
        val s = service()
        val priorPlay = command(C.SLEEP_PLAY)
        send(s, priorPlay)
        load(s)
        val priorSleep = status()
        val noiseLock = lock(s)!!
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val default = Uri.parse("android.resource://${context.packageName}/${R.raw.default_alarm}")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo(120_000, 0))
        val alarm = Intent(C.START).putExtra(C.DEFAULT_ALARM, true).putExtra(C.SESSION, "an-alarm").putExtra(C.VOLUME, 90)
        assertEquals(Service.START_STICKY, send(s, alarm))
        assertNull(track(s))
        assertFalse(noiseLock.isHeld)
        assertEquals(Phase.ALARM, status().phase)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 1)
        advance(1)
        val ringing = alarmPlayer(s)!!
        assertTrue(ringing.isPlaying && ringing.isLooping)

        val delayedStop = command(C.SLEEP_STOP, priorSleep)
        val blockedPlay = command(C.SLEEP_PLAY)
        send(s, delayedStop)
        send(s, command(C.SLEEP_UPDATE, priorSleep))
        send(s, blockedPlay)
        assertSame(ringing, alarmPlayer(s))
        assertTrue(ringing.isPlaying)
        send(s, Intent(C.STOP).putExtra(C.SESSION, "an-alarm"))
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(Phase.IDLE, status().phase)
        assertNull(alarmPlayer(s))
        assertNull(lock(s))
        send(s, priorPlay)
        send(s, blockedPlay)
        assertEquals(Phase.IDLE, status().phase)
        assertNull(track(s))
    }

    @Test fun customAlarmPreemptsPreparationAndKeepsItsOwnSong() {
        val s = service()
        val gate = CountDownLatch(1)
        loader(s).submit { gate.await(5, TimeUnit.SECONDS) }
        val song = File(context.filesDir, "song-custom.audio").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = Uri.fromFile(song)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(40_000, 0))
        try {
            send(s, command(C.SLEEP_PLAY))
            assertEquals(Phase.PREPARING, status().phase)
            val start = Intent(C.START).setData(Uri.parse("content://songs/custom"))
                .putExtra(C.SESSION, "custom").putExtra(C.VOLUME, 51)
            assertEquals(Service.START_STICKY, send(s, start))
            assertEquals(51, AudioStore(context).active!!.volume)
        } finally { gate.countDown() }
        load(s)
        assertEquals(Phase.ALARM, status().phase)
        assertNull(track(s))
        assertNull(lock(s))
        assertTrue(alarmPlayer(s)!!.isPlaying)
        assertTrue(song.exists())
        send(s, Intent(C.STOP).putExtra(C.SESSION, "custom"))
        assertFalse(song.exists())
        assertEquals(Phase.IDLE, status().phase)
    }

    @Test fun stoppedLoadingCallbackCannotBringBackWhiteNoise() {
        val s = service()
        val gate = CountDownLatch(1)
        loader(s).submit { gate.await(5, TimeUnit.SECONDS) }
        try {
            send(s, command(C.SLEEP_PLAY))
            assertEquals(Phase.PREPARING, status().phase)
            send(s, command(C.SLEEP_STOP))
        } finally { gate.countDown() }
        load(s)
        assertEquals(Phase.IDLE, status().phase)
        assertNull(track(s))
        assertNull(lock(s))
    }

    @Implements(AudioTrack::class)
    class LoopTrackShadow : ShadowAudioTrack() {
        var loop: List<Int>? = null
        var gain = 0f

        @Implementation
        protected fun native_set_loop(start: Int, end: Int, count: Int): Int {
            loop = listOf(start, end, count)
            return AudioTrack.SUCCESS
        }

        @Implementation
        protected fun native_setVolume(left: Float, right: Float) { gain = minOf(left, right) }
    }
}
