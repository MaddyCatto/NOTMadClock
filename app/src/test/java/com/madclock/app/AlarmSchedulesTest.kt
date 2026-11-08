package com.madclock.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import com.madclock.ipc.AudioContract as C
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmSchedulesTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AlarmStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        store = AlarmStore(context)
        store.barcode = "common-code"
        if (android.os.Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true)
        installHelper(context)
    }
    private fun record(index: Int = 0, repeat: AlarmRepeat = AlarmRepeat.DAILY) = AlarmRecord(
        hour = 7 + index, minute = index, repeat = repeat, songUri = "content://songs/$index",
        songName = "Song $index", volumePercent = 30 + index)
    private fun add(a: AlarmRecord = record()): AlarmRecord {
        assertTrue(AlarmActions.save(context, a)); return store.find(a.id)!!
    }
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private fun nextService() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    private fun fire(a: AlarmRecord) = SignalController.onAlarm(context, a.id, a.generation!!, a.nextAtMs!!)

    @Test fun tenAlarmsPersistWithIndependentEventsAndEleventhIsRejected() {
        val all = (0 until 10).map { add(record(it)) }
        assertEquals(10, all.map { it.id }.toSet().size)
        assertEquals(10, alarms().scheduledAlarms.size)
        assertEquals(all, AlarmStore(context).alarms)
        assertFalse(AlarmActions.save(context, record(10)))
        assertEquals(10, store.alarms.size)
        assertEquals(10, alarms().scheduledAlarms.size)
        assertTrue(AlarmActions.save(context, all[5].copy(volumePercent = 99)))
        assertEquals(99, store.find(all[5].id)!!.volumePercent)
    }
    @Test fun editDisableAndDeleteOnlyTouchTheirOwnPersistentId() {
        val one = add(record(0)); val two = add(record(1)); val three = add(record(2))
        assertTrue(AlarmActions.save(context, one.copy(hour = 22, volumePercent = 100)))
        assertEquals(two, store.find(two.id)); assertEquals(three, store.find(three.id))
        assertTrue(AlarmActions.save(context, two.copy(enabled = false)))
        assertEquals(2, alarms().scheduledAlarms.size)
        assertEquals(three, store.find(three.id))
        assertTrue(AlarmActions.delete(context, one.id))
        assertEquals(1, alarms().scheduledAlarms.size)
        assertEquals(three, store.find(three.id))
        assertTrue(AlarmActions.save(context, store.find(two.id)!!.copy(enabled = true)))
        assertEquals(2, alarms().scheduledAlarms.size)
        assertEquals(three, store.find(three.id))
    }
    @Test fun oneShotSwitchesOffBeforeAudioStartsAndKeepsSoundingOccurrence() {
        val once = add(record(repeat = AlarmRepeat.ONCE))
        fire(once)
        assertFalse(store.find(once.id)!!.enabled)
        assertNull(store.find(once.id)!!.nextAtMs)
        assertTrue(alarms().scheduledAlarms.isEmpty())
        val active = store.activeAlarm!!
        assertEquals(once.id, active.alarmId)
        assertNotEquals(once.id, active.id)
        val command = nextService()!!
        assertEquals(C.START, command.action)
        assertEquals(active.id, command.getStringExtra(C.SESSION))
        assertEquals(once.songUri, command.data.toString())
        assertEquals(once.volumePercent, command.getIntExtra(C.VOLUME, -1))
        assertTrue(command.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(C.PACKAGE, command.component!!.packageName)
    }
    @Test fun dailyOccurrencesHaveFreshSessionIdsAndFutureScheduleSurvivesBarcodeStop() {
        val first = add()
        fire(first)
        val active = store.activeAlarm!!
        nextService()
        val next = store.find(first.id)!!
        assertTrue(next.enabled); assertTrue(next.nextAtMs!! > first.nextAtMs!!)
        assertNotEquals(first.generation, next.generation)
        assertFalse(SignalController.stopAfterScan(context, active.id, "wrong"))
        assertTrue(SignalController.stopAfterScan(context, active.id, "common-code"))
        val stop = nextService()!!
        assertEquals(C.STOP, stop.action)
        acknowledge(stop)
        assertNull(store.pendingAudioStop)
        assertEquals(next, store.find(first.id))
        assertEquals(1, alarms().scheduledAlarms.size)
        fire(next)
        assertNotEquals(active.id, store.activeAlarm!!.id)
        assertEquals(first.id, store.activeAlarm!!.alarmId)
    }
    @Test fun tenSimultaneousAlarmsCoalesceWithoutInterruptingOrLosingRecurrences() {
        val all = (0 until 10).map { index -> add(record(index).copy(hour = 7, minute = 0,
            repeat = if (index % 2 == 0) AlarmRepeat.DAILY else AlarmRepeat.ONCE)) }
        all.forEach(::fire)
        val first = nextService()!!
        assertEquals(C.START, first.action)
        assertNull(nextService())
        assertEquals(all[0].id, store.activeAlarm!!.alarmId)
        assertEquals(5, alarms().scheduledAlarms.size)
        all.forEach { original ->
            val current = store.find(original.id)!!
            if (original.repeat == AlarmRepeat.ONCE) assertFalse(current.enabled)
            else assertTrue(current.nextAtMs!! > original.nextAtMs!!)
        }
    }
    @Test fun staleDeliveriesAfterEditDisableDeleteOrFirstDeliveryCannotStartAgain() {
        val one = add(record(0)); val two = add(record(1)); val three = add(record(2))
        AlarmActions.save(context, one.copy(hour = 23))
        AlarmActions.save(context, two.copy(enabled = false))
        AlarmActions.delete(context, three.id)
        listOf(one, two, three).forEach(::fire)
        assertNull(store.activeAlarm); assertNull(nextService())
        val now = store.find(one.id)!!
        fire(now); nextService()
        val active = store.activeAlarm!!
        SignalController.stopAfterScan(context, active.id, active.barcode)
        acknowledge(nextService()!!)
        fire(now)
        assertNull(store.activeAlarm); assertNull(nextService())
    }
    @Test fun editingAndDeletingSourceAlarmNeverChangesOrStopsActiveSong() {
        val original = add()
        fire(original); nextService()
        val sounding = store.activeAlarm!!
        AlarmActions.save(context, store.find(original.id)!!.copy(songUri = "content://songs/new", volumePercent = 1))
        assertEquals(sounding, store.activeAlarm)
        AlarmActions.delete(context, original.id)
        assertEquals(sounding, store.activeAlarm)
        assertNull(nextService())
        assertTrue(SignalController.stopAfterScan(context, sounding.id, "common-code"))
        assertEquals(C.STOP, nextService()!!.action)
    }
    @Test fun restoreAfterBootRearmsEnabledRecordsOnlyAndNeverStartsAudio() {
        val all = (0 until 10).map { add(record(it).copy(enabled = it % 3 != 0)) }
        val manager = context.getSystemService(AlarmManager::class.java)
        alarms().scheduledAlarms.toList().forEach {
            @Suppress("DEPRECATION") val operation = it.operation
            manager.cancel(requireNotNull(operation))
        }
        assertTrue(alarms().scheduledAlarms.isEmpty())
        RestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(all.count { it.enabled }, alarms().scheduledAlarms.size)
        assertEquals(all.map { it.id }, store.alarms.map { it.id })
        all.forEach { assertEquals(it.songUri, store.find(it.id)!!.songUri) }
        assertNull(nextService())
    }
    @Test fun missedOneShotAfterBootSchedulesCatchUpOnce() {
        val once = add(record(repeat = AlarmRepeat.ONCE))
        val now = System.currentTimeMillis()
        store.put(once.copy(nextAtMs = now - 10_000))
        AlarmScheduler.restoreAll(context, now = now)
        val restored = store.find(once.id)!!
        assertEquals(now + 1_000, restored.nextAtMs)
        fire(restored)
        assertFalse(store.find(once.id)!!.enabled)
        assertNotNull(store.activeAlarm)
    }
    @Test fun dailyTimeRemainsLocalAcrossDaylightSavingChange() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 7, 8, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val next = Calendar.getInstance().apply { timeInMillis = AlarmScheduler.nextTime(record().copy(hour = 7, minute = 15), now) }
            assertEquals(8, next.get(Calendar.DAY_OF_MONTH))
            assertEquals(7, next.get(Calendar.HOUR_OF_DAY)); assertEquals(15, next.get(Calendar.MINUTE))
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun pendingStopPersistsAcrossMainRestartAndBlocksAnOverlappingSignal() {
        val first = add(record(0)); val second = add(record(1, AlarmRepeat.ONCE))
        fire(first); nextService()
        val active = store.activeAlarm!!
        SignalController.stopAfterScan(context, active.id, active.barcode)
        val stop = nextService()!!
        assertEquals(active.id, AlarmStore(context).pendingAudioStop)
        fire(second)
        assertFalse(store.find(second.id)!!.enabled)
        assertNull(store.activeAlarm); assertNull(nextService())
        acknowledge(stop)
        assertNull(store.pendingAudioStop)
    }
    @Test fun soundTestUsesChosenSongWithoutChangingAnyScheduleOrSwitch() {
        val all = (0 until 3).map { add(record(it)) }
        assertTrue(SignalController.test(context, all[1]))
        val command = nextService()!!
        assertEquals(all[1].songUri, command.data.toString())
        assertEquals(all, store.alarms)
        assertEquals(3, alarms().scheduledAlarms.size)
        assertFalse(SignalController.test(context, all[2]))
        assertNull(nextService())
    }
    @Test fun migrationKeepsLegacyBSettingsAndTheCurrentlyPlayingHelperSession() {
        val prefs = context.getSharedPreferences(AlarmStore.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("hour", 9).putInt("minute", 30).putBoolean("enabled", true)
            .putString("song_uri", "content://songs/legacy").putString("song_name", "Legacy song")
            .putInt("volume_percent", 74).putString("barcode", "legacy-code").putString("signal_mode", "AUDIO_APP")
            .putString("active_alarm", JSONObject().apply {
                put("id", "helper-session"); put("song", "content://songs/current"); put("barcode", "legacy-code")
                put("volume", 66); put("mode", "AUDIO_APP"); put("kind", "TEST"); put("startAtMs", 100)
            }.toString()).commit()
        val migrated = AlarmStore(context)
        assertEquals(1, migrated.alarms.size)
        val a = migrated.alarms.single()
        assertEquals(9, a.hour); assertEquals(30, a.minute); assertTrue(a.enabled)
        assertEquals(74, a.volumePercent); assertEquals("Legacy song", a.songName)
        assertEquals("helper-session", migrated.activeAlarm!!.id)
        SignalController.onVisibleLaunch(context)
        assertEquals("helper-session", nextService()!!.getStringExtra(C.SESSION))
        assertEquals(1, alarms().scheduledAlarms.size)
        assertEquals(a.id, AlarmStore(context).alarms.single().id)
    }
    @Test fun migrationCancelsAllOldNotificationEvents() {
        val prefs = context.getSharedPreferences(AlarmStore.PREFS, Context.MODE_PRIVATE)
        val old = JSONObject().apply {
            put("id", "old-plan"); put("kind", "DAILY"); put("mode", "NOTIFICATIONS")
            put("song", ""); put("barcode", "common-code"); put("volume", 80)
        }
        prefs.edit().clear().putString("plan_DAILY", old.toString()).commit()
        repeat(20) { slot ->
            val pending = PendingIntent.getBroadcast(context, 2200 + slot,
                Intent(context, AlarmReceiver::class.java).setAction("com.madclock.app.SIGNAL_EVENT_V2")
                    .setData(Uri.parse("madclock://event/DAILY/old-plan/$slot")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 60_000, null), pending)
        }
        assertEquals(20, alarms().scheduledAlarms.size)
        AlarmStore(context)
        assertTrue(alarms().scheduledAlarms.isEmpty())
        assertNull(nextService())
    }
    @Test fun waitingForAlarmsDoesNotStartAnyMainOrHelperService() {
        repeat(10) { add(record(it)) }
        assertNull(nextService())
        assertNull(store.activeAlarm)
        assertEquals(10, alarms().scheduledAlarms.size)
    }
    companion object {
        @Suppress("DEPRECATION")
        fun installHelper(context: Context) {
            val pm = shadowOf(context.packageManager)
            val own = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            own.signatures = arrayOf(Signature("1234")); pm.installPackage(own)
            pm.installPackage(PackageInfo().apply {
                packageName = C.PACKAGE
                applicationInfo = ApplicationInfo().apply { packageName = C.PACKAGE; uid = 23456 }
                signatures = arrayOf(Signature("1234"))
                requestedPermissions = arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
                requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
            })
        }
        @Suppress("DEPRECATION")
        fun acknowledge(intent: Intent) {
            val receiver = intent.getParcelableExtra<ResultReceiver>(C.ACK)!!
            assertEquals(ResultReceiver::class.java, receiver.javaClass)
            Shadow.directlyOn<Any?, ResultReceiver>(receiver, ResultReceiver::class.java, "send",
                ClassParameter.from(Int::class.javaPrimitiveType!!, C.OK), ClassParameter.from(Bundle::class.java, null))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        }
    }
}
