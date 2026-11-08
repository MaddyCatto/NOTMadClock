package com.madclock.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import com.madclock.ipc.AudioContract as C
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class SignalModesTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        store = AlarmStore(context)
        store.barcode = "registered"
        store.songUri = "content://songs/one"
        if (android.os.Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private fun nextService() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    private fun fire(run: ActiveAlarm, slot: Int = 0): Boolean {
        var finished = false
        SignalController.onEvent(context, run.id, run.kind, slot) { finished = true }
        return finished
    }
    @Suppress("DEPRECATION")
    private fun helper(trusted: Boolean = true) {
        val pm = shadowOf(context.packageManager)
        val own = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        own.signatures = arrayOf(Signature("1234"))
        pm.installPackage(own)
        pm.installPackage(PackageInfo().apply {
            packageName = C.PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = C.PACKAGE; uid = 23456 }
            signatures = arrayOf(Signature(if (trusted) "1234" else "5678"))
        })
    }
    @Suppress("DEPRECATION")
    private fun ack(intent: Intent, ok: Boolean) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(C.ACK)!!
        assertEquals(ResultReceiver::class.java, receiver.javaClass)
        // Robolectric's ShadowResultReceiver invokes onReceiveResult on the parcelled base
        // object and loses its Binder endpoint. Exercise Android's real send implementation.
        Shadow.directlyOn<Any?, ResultReceiver>(receiver, ResultReceiver::class.java, "send",
            ClassParameter.from(Int::class.javaPrimitiveType!!, if (ok) C.OK else C.FAILED),
            ClassParameter.from(Bundle::class.java, Bundle()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    }

    @Test fun modeAPreplansExactlyTwentyIndependentEventsThirtySecondsApart() {
        assertTrue(AlarmScheduler.scheduleTest(context))
        val plan = store.plan(AlarmKind.TEST)!!
        val scheduled = alarms().scheduledAlarms.sortedBy { it.triggerAtMs }
        assertEquals(20, scheduled.size)
        scheduled.forEachIndexed { index, alarm ->
            assertEquals(plan.startAtMs + index * 30_000L, alarm.triggerAtMs)
            assertNotNull(alarm.alarmClockInfo)
            assertEquals(0L, alarm.intervalMs)
        }
        assertNull(store.activeAlarm)
        assertNull(nextService())
    }
    @Test fun modeAPostsEmbeddedSystemSoundWithoutStartingAnyService() {
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        assertTrue(fire(plan))
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationSignals.CHANNEL)
        assertEquals("android.resource://${context.packageName}/raw/glass_signal_alarm_60s", channel.sound.toString())
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertEquals(1, notifications().allNotifications.size)
        assertNull(nextService())
        assertEquals(plan.id, store.activeAlarm!!.id)
        assertEquals(20, alarms().scheduledAlarms.size) // no recursive slot scheduling
    }
    @Test fun actualBroadcastReceiverRoutesScheduledEventToNotification() {
        AlarmScheduler.scheduleTest(context)
        @Suppress("DEPRECATION")
        val event = Intent(shadowOf(alarms().scheduledAlarms.first().operation).savedIntent)
        // Robolectric does not dispatch manifest receivers with no intent-filter automatically.
        // Verify the declared explicit receiver, then exercise its real onReceive/goAsync path.
        val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, AlarmReceiver::class.java), 0)
        assertFalse(info.exported)
        val receiver = AlarmReceiver()
        context.registerReceiver(receiver, IntentFilter(AlarmReceiver.ACTION_EVENT).apply {
            addDataScheme("madclock")
        }, Context.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(event.setComponent(null).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(1, notifications().allNotifications.size)
            assertNotNull(store.activeAlarm)
        } finally { context.unregisterReceiver(receiver) }
    }
    @Test fun modeACorrectScanCancelsAllEventsAndNotificationAndLateEventsAreInert() {
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        fire(plan)
        assertFalse(SignalController.stopAfterScan(context, plan.id, "wrong"))
        assertNotNull(store.activeAlarm)
        assertTrue(SignalController.stopAfterScan(context, plan.id, "registered"))
        assertNull(store.activeAlarm)
        assertTrue(alarms().scheduledAlarms.isEmpty())
        assertTrue(notifications().allNotifications.isEmpty())
        repeat(20) { fire(plan, it) }
        assertTrue(notifications().allNotifications.isEmpty())
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
    @Test fun duplicateSlotsDoNotAlertAgainOrExtendTheSequence() {
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        fire(plan, 0); fire(plan, 5); fire(plan, 0); fire(plan, 5)
        assertEquals(5, store.activeAlarm!!.lastNotificationSlot)
        assertEquals(1, notifications().allNotifications.size)
        assertEquals(20, alarms().scheduledAlarms.size)
    }
    @Test fun dailyActivationRetainsCurrentSlotsAndPlansTomorrowInAdvance() {
        store.enabled = true
        AlarmScheduler.scheduleNext(context)
        val today = store.plan(AlarmKind.DAILY)!!
        fire(today)
        val tomorrow = store.plan(AlarmKind.DAILY)!!
        assertNotEquals(today.id, tomorrow.id)
        assertEquals(today.id, store.activeAlarm!!.id)
        assertEquals(40, alarms().scheduledAlarms.size)
        SignalController.stopAfterScan(context, today.id, "registered")
        assertEquals(20, alarms().scheduledAlarms.size)
    }
    @Test fun modeBPlansOneEventAndSendsExplicitUriGrantToTrustedHelperOnly() {
        helper()
        store.mode = SignalMode.AUDIO_APP
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        assertEquals(1, alarms().scheduledAlarms.size)
        assertTrue(fire(plan))
        val start = nextService()!!
        assertEquals(C.SERVICE, start.component!!.className)
        assertEquals(C.PACKAGE, start.component!!.packageName)
        assertEquals(C.START, start.action)
        assertEquals(plan.id, start.getStringExtra(C.SESSION))
        assertEquals("content://songs/one", start.data.toString())
        assertTrue(start.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(store.volumePercent, start.getIntExtra(C.VOLUME, -1))
        assertTrue(notifications().allNotifications.isEmpty())
    }
    @Test fun wrongSignatureDoesNotReceiveSongOrStartCommand() {
        helper(trusted = false)
        store.mode = SignalMode.AUDIO_APP
        AlarmScheduler.scheduleTest(context)
        fire(store.plan(AlarmKind.TEST)!!)
        assertNull(nextService())
        assertTrue(notifications().allNotifications.isEmpty())
    }
    @Test fun modeADoesNotPostUntilHelperConfirmsStopAll() {
        helper()
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        assertFalse(fire(plan))
        val stop = nextService()!!
        assertEquals(C.STOP_ALL, stop.action)
        @Suppress("DEPRECATION")
        val wireAck = stop.getParcelableExtra<ResultReceiver>(C.ACK)!!
        assertEquals(ResultReceiver::class.java, wireAck.javaClass)
        assertTrue(notifications().allNotifications.isEmpty())
        ack(stop, true)
        assertEquals(1, notifications().allNotifications.size)
        assertTrue(store.activeAlarm!!.audioQuietConfirmed)
        fire(plan, 1)
        assertNull(nextService())
    }
    @Test fun helperSilenceTimeoutFailsClosedAndLateAckDoesNotStartSound() {
        helper()
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        fire(plan)
        val stop = nextService()!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        ack(stop, true)
        assertTrue(notifications().allNotifications.isEmpty())
        assertFalse(store.activeAlarm!!.audioQuietConfirmed)
    }
    @Test fun modeBStopIsDurableUntilAcknowledgedAndStaleSlotCannotRestartIt() {
        helper()
        store.mode = SignalMode.AUDIO_APP
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        fire(plan); nextService()
        assertTrue(SignalController.stopAfterScan(context, plan.id, plan.barcode))
        val stop = nextService()!!
        assertEquals(C.STOP, stop.action)
        assertEquals(plan.id, store.pendingAudioStop)
        assertNull(store.activeAlarm)
        fire(plan)
        assertNull(nextService())
        ack(stop, true)
        assertNull(store.pendingAudioStop)
    }
    @Test fun oldCallbackAfterCorrectScanCannotPublishANotification() {
        helper()
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        fire(plan)
        val stop = nextService()!!
        SignalController.stopAfterScan(context, plan.id, plan.barcode)
        ack(stop, true)
        assertTrue(notifications().allNotifications.isEmpty())
        assertNull(store.activeAlarm)
    }
    @Test fun modeACannotActivateWhileBStopIsUnacknowledged() {
        helper()
        store.mode = SignalMode.AUDIO_APP
        AlarmScheduler.scheduleTest(context)
        val b = store.plan(AlarmKind.TEST)!!
        fire(b); nextService()
        SignalController.stopAfterScan(context, b.id, b.barcode)
        val stopB = nextService()!!
        store.mode = SignalMode.NOTIFICATIONS
        AlarmScheduler.scheduleTest(context)
        val a = store.plan(AlarmKind.TEST)!!
        assertFalse(fire(a))
        assertNull(store.activeAlarm)
        assertTrue(notifications().allNotifications.isEmpty())
        val retry = nextService()!!
        assertEquals(C.STOP, retry.action)
        ack(stopB, true)
        fire(a, 1)
        val silence = nextService()!!
        assertEquals(C.STOP_ALL, silence.action)
        ack(silence, true)
        assertEquals(SignalMode.NOTIFICATIONS, store.activeAlarm!!.mode)
        assertEquals(1, notifications().allNotifications.size)
    }
    @Test fun differentModeRunCannotStartWhileAnotherModeIsActive() {
        AlarmScheduler.scheduleTest(context)
        val a = store.plan(AlarmKind.TEST)!!
        fire(a)
        helper()
        store.mode = SignalMode.AUDIO_APP
        store.enabled = true
        AlarmScheduler.scheduleNext(context)
        val b = store.plan(AlarmKind.DAILY)!!
        fire(b)
        assertNull(nextService())
        assertEquals(a.id, store.activeAlarm!!.id)
    }
    @Test fun activeModeAndBarcodeDoNotChangeWithFutureSettings() {
        AlarmScheduler.scheduleTest(context)
        val plan = store.plan(AlarmKind.TEST)!!
        fire(plan)
        store.mode = SignalMode.AUDIO_APP; store.barcode = "new-code"
        assertEquals(SignalMode.NOTIFICATIONS, store.activeAlarm!!.mode)
        assertFalse(SignalController.stopAfterScan(context, plan.id, "new-code"))
        assertTrue(SignalController.stopAfterScan(context, plan.id, "registered"))
    }
    @Test fun migrationPreservesOldActiveSessionButRetiresRecoveryAndSeedEvents() {
        val prefs = context.getSharedPreferences("madclock_preferences", Context.MODE_PRIVATE)
        prefs.edit().remove("architecture_v2").putString("active_alarm", JSONObject().apply {
            put("id", "legacy-session"); put("song", "content://songs/old"); put("barcode", "legacy-code")
            put("volume", 70); put("originalVolume", 3); put("recoveryAttempted", true)
        }.toString()).commit()
        val migrated = AlarmStore(context).activeAlarm!!
        assertEquals("legacy-session", migrated.id)
        assertEquals("legacy-code", migrated.barcode)
        assertEquals(SignalMode.NOTIFICATIONS, migrated.mode)
        assertEquals(3, migrated.originalVolume)
        SignalController.onVisibleLaunch(context)
        assertEquals(20, alarms().scheduledAlarms.size)
        assertNull(nextService())
    }
    @Test fun mainManifestHasNoPlaybackServiceOrPlaybackPermissions() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        assertFalse(info.services.orEmpty().any { it.name.endsWith("AlarmService") })
        if (android.os.Build.VERSION.SDK_INT >= 29)
            assertFalse(info.services.orEmpty().any { it.foregroundServiceType != 0 })
        val permissions = info.requestedPermissions.orEmpty()
        listOf("android.permission.INTERNET", "android.permission.WAKE_LOCK",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.FOREGROUND_SERVICE")
            .forEach { assertFalse(permissions.contains(it)) }
    }
    @Test fun reopeningMainActivityDoesNotCreateAnyPlaybackService() {
        AlarmScheduler.scheduleTest(context)
        fire(store.plan(AlarmKind.TEST)!!)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(nextService())
        assertEquals(20, alarms().scheduledAlarms.size)
        activity.pause().stop().destroy()
    }
    @Test fun selectingUnavailableBDoesNotReplaceWorkingDailyAPlan() {
        store.enabled = true
        AlarmScheduler.scheduleNext(context)
        val old = store.plan(AlarmKind.DAILY)!!
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().findViewById<android.widget.Spinner>(R.id.signalMode).setSelection(1)
        controller.get().findViewById<android.widget.Button>(R.id.testAlarm).performClick()
        assertEquals(SignalMode.NOTIFICATIONS, store.mode)
        assertEquals(old, store.plan(AlarmKind.DAILY))
        assertEquals(20, alarms().scheduledAlarms.size)
        controller.pause().stop().destroy()
    }
    @Test fun blockedDailyRunStillSchedulesNextDayWithoutInterruptingActiveSignal() {
        AlarmScheduler.scheduleTest(context)
        val active = store.plan(AlarmKind.TEST)!!
        fire(active)
        store.mode = SignalMode.AUDIO_APP
        store.enabled = true
        AlarmScheduler.scheduleNext(context)
        // Model an event due now. ShadowSystemClock controls Android uptime, not JVM wall time.
        val blocked = store.plan(AlarmKind.DAILY)!!.copy(startAtMs = System.currentTimeMillis() - 1_000)
        store.savePlan(AlarmKind.DAILY, blocked)
        fire(blocked)
        val next = store.plan(AlarmKind.DAILY)!!
        assertEquals(active.id, store.activeAlarm!!.id)
        assertTrue(next.startAtMs > blocked.startAtMs)
        assertNotEquals(blocked.id, next.id)
        assertNull(nextService())
    }
    @Test fun pendingStopFailureDoesNotPermanentlyLoseDailyBRecurrence() {
        helper()
        store.mode = SignalMode.AUDIO_APP
        AlarmScheduler.scheduleTest(context)
        val active = store.plan(AlarmKind.TEST)!!
        fire(active); nextService()
        SignalController.stopAfterScan(context, active.id, active.barcode); nextService()
        store.enabled = true
        AlarmScheduler.scheduleNext(context)
        val blocked = store.plan(AlarmKind.DAILY)!!.copy(startAtMs = System.currentTimeMillis() - 1_000)
        store.savePlan(AlarmKind.DAILY, blocked)
        fire(blocked)
        val stop = nextService()!!
        assertEquals(C.STOP, stop.action)
        ack(stop, false)
        assertNull(store.activeAlarm)
        assertNotNull(store.pendingAudioStop)
        assertTrue(store.plan(AlarmKind.DAILY)!!.startAtMs > blocked.startAtMs)
        assertNotEquals(blocked.id, store.plan(AlarmKind.DAILY)!!.id)
        assertNull(nextService())
    }
}
