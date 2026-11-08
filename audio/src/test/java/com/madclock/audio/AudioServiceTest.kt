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
        registerCache()
        val service = service()
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
        registerCache()
        val service = service()
        service.onStartCommand(command(C.START), 0, 1); idle()
        val initial = store.active!!
        controller!!.destroy(); controller = null
        val restored = service()
        restored.onStartCommand(null, 0, 1); idle()
        assertTrue(player(restored)!!.isPlaying)
        assertEquals(initial, AudioStore(context).active)
    }
    @Test fun stopAcknowledgesOnlyAfterStateAndPlaybackAreStopped() {
        registerCache()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val service = service()
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
    @Test fun newSessionReleasesOldPlayerAndStaleStopCannotStopNewSession() {
        registerCache("one"); registerCache("two")
        val service = service()
        service.onStartCommand(command(C.START, "one"), 0, 1); idle()
        val first = player(service)!!
        service.onStartCommand(command(C.START, "two"), 0, 2); idle()
        assertNotSame(first, player(service))
        assertEquals("two", store.active!!.id)
        service.onStartCommand(command(C.STOP, "one"), 0, 3)
        assertTrue(player(service)!!.isPlaying)
        assertEquals("two", store.active!!.id)
    }
    @Test fun stopAllFencesTheActiveSessionBeforeNotificationModeCanStart() {
        registerCache()
        val service = service()
        service.onStartCommand(command(C.START), 0, 1); idle()
        service.onStartCommand(command(C.STOP_ALL), 0, 2)
        assertNull(store.active); assertNull(player(service))
        service.onStartCommand(command(C.START), 0, 3); idle()
        assertNull(store.active); assertNull(player(service))
    }
    @Test fun volumeEnforcementLivesOnlyInHelperAndRestoresAfterStop() {
        registerCache()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val service = service()
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
}
