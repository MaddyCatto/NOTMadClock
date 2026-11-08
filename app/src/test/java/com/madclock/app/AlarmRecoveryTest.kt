package com.madclock.app

import android.app.AlarmManager
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.Build
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.util.DataSource
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmRecoveryTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    private var controller: ServiceController<AlarmService>? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        store = AlarmStore(context)
        store.songUri = "content://test/song"
        store.barcode = "registered-code"
        store.volumePercent = 80
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, Uri.parse(store.songUri)),
            ShadowMediaPlayer.MediaInfo(60_000, 0)
        )
        if (Build.VERSION.SDK_INT >= 31) {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }

    @After fun tearDown() {
        controller?.destroy()
    }

    private fun service(): AlarmService = Robolectric.buildService(AlarmService::class.java)
        .also { controller = it }.create().get()

    private fun command(action: String, id: String, code: String? = null) =
        Intent(context, AlarmService::class.java).setAction(action)
            .putExtra(AlarmService.EXTRA_SESSION, id).putExtra(AlarmService.EXTRA_BARCODE, code)

    @Test fun mergedManifestDoesNotInheritNetworkPermissions() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test fun activeSessionSurvivesNewStoreAndSettingsChanges() {
        val active = store.beginAlarm(3)!!
        store.songUri = "content://test/other"
        store.barcode = "replacement-code"
        store.volumePercent = 10
        store.enabled = false
        assertEquals(active, AlarmStore(context).activeAlarm)
        assertEquals(active, store.beginAlarm(9))
        assertEquals("registered-code", active.barcode)
        assertEquals(3, active.originalVolume)
    }

    @Test fun finishRequiresMatchingSessionAndBarcode() {
        val active = store.beginAlarm(3)!!
        assertFalse(store.finishAlarm(active.id, "wrong"))
        assertFalse(store.finishAlarm("stale-session", active.barcode))
        assertEquals(active, AlarmStore(context).activeAlarm)
        assertTrue(store.finishAlarm(active.id, active.barcode))
        assertNull(AlarmStore(context).activeAlarm)
    }

    @Test fun newAlarmDoesNotReuseOldRecoveryTicketOrSession() {
        val old = store.beginAlarm(3)!!
        val oldTicket = store.claimRecovery(old.id)!!
        assertNull(AlarmStore(context).claimRecovery(old.id))
        store.finishAlarm(old.id, old.barcode)
        val next = store.beginAlarm(4)!!
        assertNotEquals(old.id, next.id)
        assertNull(store.claimRecovery(old.id))
        assertNotEquals(oldTicket.recoveryToken, store.claimRecovery(next.id)!!.recoveryToken)
    }

    @Test fun stickyRestartRestoresForegroundPlaybackWithoutCreatingSession() {
        val active = store.beginAlarm(3)!!
        val service = service()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(AlarmService.isRinging)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(active, store.activeAlarm)
    }

    @Test fun repeatedStartIsIdempotentAndWrongScanKeepsAlarmSticky() {
        val active = store.beginAlarm(3)!!
        val service = service()
        val start = command(AlarmService.ACTION_START, active.id)
        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 2))
        assertEquals(Service.START_STICKY, service.onStartCommand(
            command(AlarmService.ACTION_STOP, active.id, "wrong"), 0, 3))
        assertTrue(AlarmService.isRinging)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(active, store.activeAlarm)
    }

    @Test fun taskRemovalKeepsServicePlayingAndSchedulesOnlyOneContinuation() {
        val active = store.beginAlarm(3)!!
        val service = service()
        service.onStartCommand(null, 0, 1)
        service.onTaskRemoved(Intent())
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        // Robolectric exposes the PendingIntent through this deprecated field; no getter exists.
        @Suppress("DEPRECATION")
        val ticket = alarms.scheduledAlarms.single().operation
        assertEquals(active.id, shadowOf(ticket).savedIntent.getStringExtra(AlarmService.EXTRA_SESSION))
        assertTrue(AlarmService.isRinging)
        assertFalse(shadowOf(service).isStoppedBySelf)
        val deadline = alarms.scheduledAlarms.single().triggerAtMs
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        service.onTaskRemoved(Intent())
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(deadline, alarms.scheduledAlarms.single().triggerAtMs)
        assertNotNull(store.activeAlarm!!.recoveryToken)
    }

    @Test @Config(sdk = [36]) fun missingExactAlarmAccessLeavesSessionForVisibleRecovery() {
        val active = store.beginAlarm(3)!!
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(active, store.activeAlarm)
    }

    @Test fun scheduledAlarmPersistsSessionBeforeStartingService() {
        AlarmReceiver().onReceive(context,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST))
        val saved = AlarmStore(context).activeAlarm!!
        val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(saved.id, start.getStringExtra(AlarmService.EXTRA_SESSION))
        assertEquals("content://test/song", saved.songUri)
        assertEquals("registered-code", saved.barcode)
    }

    @Test fun serviceDestructionRetainsActiveStateAndOriginalVolume() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val active = store.beginAlarm(2)!!
        val first = service()
        first.onStartCommand(null, 0, 1)
        controller!!.destroy()
        controller = null
        assertFalse(AlarmService.isRinging)
        assertEquals(active, AlarmStore(context).activeAlarm)
        val second = service()
        assertEquals(Service.START_STICKY, second.onStartCommand(null, 0, 1))
        assertEquals(2, store.activeAlarm!!.originalVolume)
        second.onStartCommand(command(AlarmService.ACTION_STOP, active.id, active.barcode), 0, 2)
        controller!!.destroy()
        controller = null
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test fun verifiedScanClearsStateCancelsContinuationAndRejectsLateRestart() {
        val active = store.beginAlarm(3)!!
        val service = service()
        service.onStartCommand(null, 0, 1)
        service.onTaskRemoved(Intent())
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            command(AlarmService.ACTION_STOP, active.id, active.barcode), 0, 2))
        assertNull(AlarmStore(context).activeAlarm)
        assertFalse(AlarmService.isRinging)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            command(AlarmService.ACTION_START, active.id), 0, 3))
        assertFalse(AlarmService.isRinging)
    }

    @Test fun stickyRestartWithoutActiveStateDoesNotRing() {
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(AlarmService.isRinging)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun recoveryReceiverIgnoresCompletedAndStaleSessions() {
        val active = store.beginAlarm(3)!!
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RECOVER)
            .putExtra(AlarmService.EXTRA_SESSION, "old-session")
        AlarmReceiver().onReceive(context, intent)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        store.finishAlarm(active.id, active.barcode)
        intent.putExtra(AlarmService.EXTRA_SESSION, active.id)
        AlarmReceiver().onReceive(context, intent)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun recoveryReceiverResumesExistingSessionWithoutSchedulingDailyAlarm() {
        val active = store.beginAlarm(3)!!
        val pending = store.claimRecovery(active.id)!!
        AlarmReceiver().onReceive(context,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RECOVER)
                .putExtra(AlarmService.EXTRA_SESSION, active.id)
                .putExtra(AlarmService.EXTRA_RECOVERY_TOKEN, pending.recoveryToken))
        val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService.ACTION_START, start.action)
        assertEquals(active.id, start.getStringExtra(AlarmService.EXTRA_SESSION))
        assertEquals(pending.copy(recoveryDelivered = true), store.activeAlarm)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    private fun player(service: AlarmService): MediaPlayer = ReflectionHelpers.getField(service, "player")

    private fun awaitPlayback(service: AlarmService) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(player(service).isPlaying)
        assertTrue(player(service).isLooping)
    }

    @Suppress("DEPRECATION")
    private fun pendingRecovery(): Intent {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(0L, alarm.intervalMs)
        assertFalse(alarm.isAllowWhileIdle)
        return Intent(shadowOf(alarm.operation).savedIntent)
    }

    @Test fun fiveTaskRemovalsAndColdRestartsCanRecoverWithinOneAlarm() {
        val initial = store.beginAlarm(3)!!
        var running = service()
        running.onStartCommand(null, 0, 1)
        awaitPlayback(running)
        val seenTickets = mutableSetOf<String>()
        repeat(5) {
            running.onTaskRemoved(Intent())
            val pending = pendingRecovery()
            assertTrue(seenTickets.add(pending.getStringExtra(AlarmService.EXTRA_RECOVERY_TOKEN)!!))
            controller!!.destroy()
            controller = null
            assertFalse(AlarmService.isRinging)
            ShadowSystemClock.advanceBy(Duration.ofMillis(AlarmScheduler.RECOVERY_DELAY_MS))
            AlarmReceiver().onReceive(context, pending)
            val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertNotNull(start)
            running = service()
            assertEquals(Service.START_STICKY, running.onStartCommand(start, 0, 1))
            // A service command is not yet successful playback, and cannot arm a second ticket.
            assertNotNull(store.activeAlarm!!.recoveryToken)
            assertNull(store.claimRecovery(initial.id))
            awaitPlayback(running)
            assertEquals(initial, AlarmStore(context).activeAlarm)
            assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
            AlarmReceiver().onReceive(context, pending)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
        running.onStartCommand(command(AlarmService.ACTION_STOP, initial.id, initial.barcode), 0, 2)
        assertNull(store.activeAlarm)
        assertFalse(AlarmService.isRinging)
    }

    @Test fun survivingPlayerAcknowledgesRecoveryWithoutCreatingAnotherPlayer() {
        val active = store.beginAlarm(3)!!
        val service = service()
        service.onStartCommand(null, 0, 1)
        awaitPlayback(service)
        val originalPlayer = player(service)
        repeat(5) {
            service.onTaskRemoved(Intent())
            AlarmReceiver().onReceive(context, pendingRecovery())
            val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            service.onStartCommand(start, 0, it + 2)
            assertSame(originalPlayer, player(service))
            assertTrue(originalPlayer.isPlaying)
            assertEquals(active, store.activeAlarm)
            assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        }
    }

    @Test fun stickyPlaybackCancelsPendingTicketBeforeItsDelivery() {
        val active = store.beginAlarm(3)!!
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        val late = pendingRecovery()
        val service = service()
        service.onStartCommand(null, 0, 1)
        awaitPlayback(service)
        assertEquals(active, store.activeAlarm)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        AlarmReceiver().onReceive(context, late)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun consumedTicketDoesNotRearmOnCommandBeforePlaybackOrDuplicateDelivery() {
        val active = store.beginAlarm(3)!!
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        val ticket = pendingRecovery()
        AlarmReceiver().onReceive(context, ticket)
        val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        AlarmReceiver().onReceive(context, ticket)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNull(store.claimRecovery(active.id))
        val service = service()
        service.onStartCommand(start, 0, 1)
        assertTrue(store.activeAlarm!!.recoveryDelivered)
        service.onTaskRemoved(Intent())
        assertEquals(ticket.data, pendingRecovery().data)
        awaitPlayback(service)
        assertNull(store.activeAlarm!!.recoveryToken)
    }

    @Test fun oldRecoveryTicketCannotInterruptNewAttemptWithinSameSession() {
        val active = store.beginAlarm(3)!!
        val service = service()
        service.onStartCommand(null, 0, 1)
        awaitPlayback(service)
        service.onTaskRemoved(Intent())
        val old = pendingRecovery()
        AlarmReceiver().onReceive(context, old)
        val oldStart = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        service.onStartCommand(oldStart, 0, 2)
        service.onTaskRemoved(Intent())
        val current = pendingRecovery()
        assertNotEquals(old.data, current.data)
        AlarmReceiver().onReceive(context, old)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        service.onStartCommand(oldStart, 0, 3)
        assertEquals(current.getStringExtra(AlarmService.EXTRA_RECOVERY_TOKEN), store.activeAlarm!!.recoveryToken)
        service.onStartCommand(command(AlarmService.ACTION_STOP, active.id, active.barcode), 0, 4)
        AlarmReceiver().onReceive(context, old)
        AlarmReceiver().onReceive(context, current)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNull(store.activeAlarm)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun oldOneShotStateMigratesWithoutLosingActiveAlarm() {
        val active = store.beginAlarm(3)!!
        val prefs = context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE)
        val json = org.json.JSONObject(prefs.getString("active_alarm", null)!!)
            .put("recoveryAttempted", true)
        json.remove("recoveryToken")
        prefs.edit().putString("active_alarm", json.toString()).commit()
        assertEquals(active, AlarmStore(context).activeAlarm)
        assertNotNull(store.claimRecovery(active.id))
    }

    @Test @Config(sdk = [36]) fun systemUserStopSuppressesPendingRecoveryButVisibleLaunchCanResume() {
        val active = store.beginAlarm(3)!!
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        val intent = pendingRecovery()
        val exit = Shadow.newInstanceOf(ApplicationExitInfo::class.java)
        ReflectionHelpers.setField(exit, "mReason", ApplicationExitInfo.REASON_USER_REQUESTED)
        ReflectionHelpers.setField(exit, "mTimestamp", System.currentTimeMillis())
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(exit)
        AlarmReceiver().onReceive(context, intent)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNotNull(store.activeAlarm)
        assertTrue(AlarmService.resume(context, active.id))
        val service = service()
        service.onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
        awaitPlayback(service)
        assertEquals(active, store.activeAlarm)
    }

    @Test fun deniedForegroundStartDoesNotCreateAnotherAttempt() {
        val active = store.beginAlarm(3)!!
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        val ticket = pendingRecovery()
        val denied = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("background start denied")
            }
        }
        AlarmReceiver().onReceive(denied, ticket)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(store.activeAlarm!!.recoveryDelivered)
        assertNull(store.claimRecovery(active.id))
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        assertEquals(ticket.data, pendingRecovery().data)
    }

    @Test @Config(sdk = [36]) fun oldUserStopDoesNotBlockANewTaskRemoval() {
        val active = store.beginAlarm(3)!!
        val exit = Shadow.newInstanceOf(ApplicationExitInfo::class.java)
        ReflectionHelpers.setField(exit, "mReason", ApplicationExitInfo.REASON_USER_REQUESTED)
        ReflectionHelpers.setField(exit, "mTimestamp", System.currentTimeMillis() - 1_000)
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(exit)
        AlarmScheduler.recoverAfterTaskRemoval(context, active.id)
        AlarmReceiver().onReceive(context, pendingRecovery())
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun reopeningActivityResumesPersistedSession() {
        val active = store.beginAlarm(3)!!
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(active.id, start.getStringExtra(AlarmService.EXTRA_SESSION))
        assertEquals(active, store.activeAlarm)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        activity.pause().stop().destroy()
    }
}
