package com.madclock.app

import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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

    @Test fun newAlarmDoesNotReuseOldRecoveryBudgetOrSession() {
        val old = store.beginAlarm(3)!!
        assertTrue(store.claimRecovery(old.id))
        assertFalse(AlarmStore(context).claimRecovery(old.id))
        store.finishAlarm(old.id, old.barcode)
        val next = store.beginAlarm(4)!!
        assertNotEquals(old.id, next.id)
        assertFalse(store.claimRecovery(old.id))
        assertTrue(store.claimRecovery(next.id))
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
        service.onTaskRemoved(Intent())
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(store.activeAlarm!!.recoveryAttempted)
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
        AlarmReceiver().onReceive(context,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RECOVER)
                .putExtra(AlarmService.EXTRA_SESSION, active.id))
        val start = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService.ACTION_START, start.action)
        assertEquals(active.id, start.getStringExtra(AlarmService.EXTRA_SESSION))
        assertEquals(active, store.activeAlarm)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
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
