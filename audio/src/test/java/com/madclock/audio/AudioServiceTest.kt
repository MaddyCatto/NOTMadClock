package com.madclock.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.ResultReceiver
import com.madclock.ipc.AudioContract as C
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioServiceTest {
    private lateinit var context: Context
    private lateinit var store: AudioStore
    private var controller: ServiceController<AudioService>? = null
    private val source = Uri.parse("content://songs/test")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = AudioStore(context)
        context.getSharedPreferences("madclock_audio", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.listFiles()?.forEach { it.delete() }
    }
    @After fun cleanup() { controller?.destroy() }
    private fun service() = Robolectric.buildService(AudioService::class.java)
        .also { controller = it }.create().get()
    private fun command(action: String, id: String = "one") = Intent(context, AudioService::class.java)
        .setAction(action).putExtra(C.SESSION, id).putExtra(C.VOLUME, 80).apply {
            if (action == C.START) data = source
        }
    private fun file(id: String) = File(context.filesDir, "song-$id.audio")
    private fun registerCache(id: String = "one") {
        file(id).writeBytes(byteArrayOf(1, 2, 3))
        registerMedia(id)
    }
    private fun registerMedia(id: String = "one") {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, Uri.fromFile(file(id))),
            ShadowMediaPlayer.MediaInfo(60_000, 0))
    }
    private fun player(service: AudioService): MediaPlayer? = ReflectionHelpers.getField(service, "player")
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    private fun drainImports(service: AudioService) {
        val executor = ReflectionHelpers.getField<ExecutorService>(service, "imports")
        executor.submit { }.get(5, TimeUnit.SECONDS)
        idle()
    }
    @Test fun cachedSongLoopsInsideHelperAndDuplicateStartDoesNotCreateSecondPlayer() {
        val service = service()
        registerCache()
        assertEquals(Service.START_STICKY, service.onStartCommand(command(C.START), 0, 1))
        idle()
        val initial = player(service)!!
        assertTrue(initial.isPlaying)
        assertTrue(initial.isLooping)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        val openMain = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent).savedIntent
        assertEquals("com.madclock.app", openMain.component!!.packageName)
        assertTrue(openMain.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        repeat(5) { service.onStartCommand(command(C.START), 0, it + 2) }
        assertSame(initial, player(service))
        assertEquals("com.madclock.audio", context.packageName)
    }
    @Test fun grantedContentUriIsCopiedIntoHelpersPrivateStorageBeforePlayback() {
        val bytes = byteArrayOf(11, 22, 33, 44)
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(bytes))
        registerMedia()
        val service = service()
        service.onStartCommand(command(C.START), 0, 1)
        drainImports(service)
        assertArrayEquals(bytes, file("one").readBytes())
        assertTrue(player(service)!!.isPlaying)
        assertEquals(source.toString(), AudioStore(context).active!!.source)
    }
    @Test fun stickyRestartUsesPrivateCacheWithoutMainAppUriGrant() {
        val service = service()
        registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        val initial = store.active!!
        controller!!.destroy(); controller = null
        val restored = service()
        restored.onStartCommand(null, 0, 1); idle()
        assertTrue(player(restored)!!.isPlaying)
        assertEquals(initial, AudioStore(context).active)
    }
    @Test fun stopAcknowledgesOnlyAfterStateAndPlaybackAreStopped() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val service = service()
        registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        var ok = false
        val stop = command(C.STOP).putExtra(C.ACK, object : ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: android.os.Bundle?) {
                assertNull(store.active); assertNull(player(service)); ok = code == C.OK
            }
        })
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))
        assertTrue(ok)
        assertTrue(store.completed("one"))
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
    @Test fun lateStartAfterColdStopCannotResurrectCompletedSession() {
        val service = service()
        service.onStartCommand(command(C.STOP), 0, 1)
        registerCache()
        service.onStartCommand(command(C.START), 0, 2); idle()
        assertNull(store.active)
        assertNull(player(service))
        assertTrue(AudioStore(context).completed("one"))
    }
    @Test fun stopWhileImportIsQueuedPreventsLaterPlayback() {
        val service = service()
        val gate = CountDownLatch(1)
        val executor = ReflectionHelpers.getField<ExecutorService>(service, "imports")
        executor.submit { gate.await(5, TimeUnit.SECONDS) }
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        registerMedia()
        try {
            service.onStartCommand(command(C.START), 0, 1)
            service.onStartCommand(command(C.STOP), 0, 2)
        } finally { gate.countDown() }
        drainImports(service)
        assertNull(player(service)); assertNull(store.active)
        assertFalse(file("one").exists())
    }
    @Test fun conflictingStartCannotInterruptActivePlayerAndNextSessionStartsOnlyAfterStop() {
        val service = service()
        registerCache("one"); registerCache("two")
        service.onStartCommand(command(C.START, "one"), 0, 1); idle()
        val first = player(service)!!
        service.onStartCommand(command(C.START, "two"), 0, 2); idle()
        assertSame(first, player(service)); assertEquals("one", store.active!!.id)
        service.onStartCommand(command(C.STOP, "one"), 0, 3)
        service.onStartCommand(command(C.START, "two"), 0, 4); idle()
        assertNotSame(first, player(service)); assertEquals("two", store.active!!.id)
        service.onStartCommand(command(C.STOP, "one"), 0, 5)
        service.onStartCommand(command(C.START, "one"), 0, 6)
        assertTrue(player(service)!!.isPlaying); assertEquals("two", store.active!!.id)
    }
    @Test fun stopWithoutSessionCannotImplicitlyStopPlayback() {
        val service = service()
        registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        val stop = command(C.STOP).apply { removeExtra(C.SESSION) }
        service.onStartCommand(stop, 0, 2)
        assertNotNull(store.active)
        assertTrue(player(service)!!.isPlaying)
    }
    @Test fun volumeEnforcementLivesOnlyInHelperAndRestoresAfterStop() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val service = service()
        registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_600))
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 1)
        service.onStartCommand(command(C.STOP), 0, 2)
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
    @Test fun helperManifestRequiresSignaturePermissionAndHasNoLauncher() {
        val permission = context.packageManager.getPermissionInfo(C.PERMISSION, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
        val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, AudioService::class.java), 0)
        assertTrue(service.exported)
        assertEquals(C.PERMISSION, service.permission)
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        val setup = context.packageManager.getActivityInfo(android.content.ComponentName(context, SetupActivity::class.java), 0)
        assertTrue(setup.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(C.PERMISSION, setup.permission)
        assertTrue(context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0).isEmpty())
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }
    private fun registerFallback() = ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context,
        Uri.parse("android.resource://${context.packageName}/${R.raw.fallback_alarm}")), ShadowMediaPlayer.MediaInfo(10_000, 0))
    @Test fun malformedCommandsNeverStartAPlayerOrCreateSessionState() {
        val service = service()
        val bad = listOf(command(C.START, "../path"), command(C.START, "x".repeat(81)),
            command(C.START).apply { removeExtra(C.SESSION) }, command("unknown-action"),
            command(C.START).setData(Uri.parse("file:///data/private")),
            command(C.START).setData(Uri.parse("https://example.test/song")),
            command(C.START).setData(Uri.parse("content:///no-authority")),
            command(C.START).putExtra(C.VOLUME, 0), command(C.START).putExtra(C.VOLUME, 101),
            command(C.START).putExtra(C.VOLUME, "not-an-int"))
        bad.forEachIndexed { i, intent ->
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, i + 1))
            assertNull(store.active); assertNull(player(service))
        }
    }
    @Test fun invalidStopCannotAddTombstonesOrSilenceAnActiveSession() {
        val service = service(); registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        val initial = player(service)
        service.onStartCommand(command(C.STOP, "../invalid"), 0, 2)
        assertSame(initial, player(service)); assertFalse(store.completed("../invalid"))
    }
    @Test fun unavailableSongFallsBackAndRemovesTemporaryFilesWithoutLeakingUri() {
        val service = service(); registerFallback()
        shadowOf(context.contentResolver).registerInputStream(source, object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("Secret provider path: $source")
        })
        service.onStartCommand(command(C.START), 0, 1)
        drainImports(service)
        assertTrue(player(service)!!.isPlaying)
        assertFalse(file("one").exists())
        assertTrue(context.filesDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertFalse(org.robolectric.shadows.ShadowLog.getLogs().any {
            it.msg.contains(source.toString()) || it.throwable?.message?.contains(source.toString()) == true
        })
    }
    @Test fun damagedMediaUsesFallbackWithoutCreatingConcurrentPlayback() {
        val service = service(); registerCache(); registerFallback()
        service.onStartCommand(command(C.START), 0, 1); idle()
        val damaged = player(service)!!
        shadowOf(damaged).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0); idle()
        assertNotSame(damaged, player(service)); assertTrue(player(service)!!.isPlaying)
        assertEquals("one", store.active!!.id)
    }
    @Test fun restartCleansAbandonedImportsAndSongsButPreservesActiveCache() {
        store.begin(AudioSession("one", source.toString(), 80, 2))
        registerCache(); file("abandoned").writeText("old")
        val temp = File(context.filesDir, "import-one-1.tmp").apply { writeText("partial") }
        val unrelated = File(context.filesDir, "unrelated").apply { writeText("keep") }
        val service = service()
        assertTrue(file("one").exists()); assertFalse(file("abandoned").exists()); assertFalse(temp.exists())
        assertTrue(unrelated.exists())
        service.onStartCommand(null, 0, 1); idle(); assertTrue(player(service)!!.isPlaying)
    }
    @Test fun malformedPersistedSessionCannotCreatePathOutsidePrivateSongCache() {
        context.getSharedPreferences("madclock_audio", 0).edit().putString("active", "{broken").commit()
        assertNull(store.active)
        context.getSharedPreferences("madclock_audio", 0).edit().putString("active",
            """{"id":"../escape","source":"content://songs/1","volume":80,"original":2}""").commit()
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1)); assertNull(player(service))
        assertFalse(store.begin(AudioSession("../escape", source.toString(), 80, 2)))
    }
    @Test fun invalidAcknowledgementDoesNotPreventAnOtherwiseValidStop() {
        val service = service(); registerCache()
        service.onStartCommand(command(C.START), 0, 1); idle()
        service.onStartCommand(command(C.STOP).putExtra(C.ACK, "wrong-parcel-type"), 0, 2)
        assertNull(store.active); assertNull(player(service)); assertTrue(store.completed("one"))
    }
}
