package com.madclock.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.InputChecks
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class SecurityInputsTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AlarmStore.PREFS, 0).edit().clear().commit()
        store = AlarmStore(context)
        AlarmSchedulesTest.installHelper(context)
    }
    private fun active() = ActiveAlarm("valid-session", null, "content://songs/private", "private-code", 80, 1)
    @Test fun malformedRecordsCannotReplaceValidSettings() {
        val a = AlarmRecord(songUri = "content://songs/test")
        assertTrue(store.put(a))
        listOf(a.copy(hour = 24), a.copy(minute = -1), a.copy(volumePercent = 0), a.copy(volumePercent = 101),
            a.copy(id = "../escape"), a.copy(songUri = "file:///data/private"), a.copy(songUri = "https://example.test/song"),
            a.copy(songUri = "content:///missing-authority"), a.copy(generation = "x".repeat(81))).forEach {
            assertFalse(store.put(it))
        }
        assertEquals(listOf(a), AlarmStore(context).alarms)
    }
    @Test fun corruptJsonAndInvalidRecordDoNotCrashOrHideOtherValidRecords() {
        val a = AlarmRecord(songUri = "content://songs/1"); val b = AlarmRecord(songUri = "content://songs/2")
        val raw = JSONArray().put(AlarmStore.encodeRecord(a)).put(AlarmStore.encodeRecord(a).put("hour", 99))
            .put("invalid").put(AlarmStore.encodeRecord(b)).toString()
        val prefs = context.getSharedPreferences(AlarmStore.PREFS, 0)
        prefs.edit().putString("alarms_v3", raw).putString("active_session_v3", "{broken").commit()
        assertEquals(listOf(a, b), store.alarms)
        assertNull(store.activeAlarm)
        assertEquals(raw, prefs.getString("alarms_v3", null)) // Reading does not erase evidence/settings.
        prefs.edit().putString("alarms_v3", "not-json").commit()
        assertTrue(store.alarms.isEmpty())
    }
    @Test fun malformedLegacySnapshotDoesNotPreventOpeningAnUpdatedApp() {
        val prefs = context.getSharedPreferences(AlarmStore.PREFS, 0)
        prefs.edit().clear().putString("active_alarm", "{broken").putString("barcode", "retained").commit()
        val migrated = AlarmStore(context)
        assertNull(migrated.activeAlarm); assertEquals("retained", migrated.barcode)
    }
    @Test fun barcodeIsOpaqueDataAndNeverAnIntentOrShellCommand() {
        val code = "https://example.test/; $(touch /tmp/madclock-should-never-run)"
        store.barcode = code
        assertTrue(store.activate(active().copy(barcode = code)))
        assertFalse(SignalController.stopAfterScan(context, active().id, "wrong"))
        assertTrue(SignalController.stopAfterScan(context, active().id, code))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(C.STOP, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService.action)
        assertFalse(ShadowLog.getLogs().any { it.msg.contains(code) })
        assertThrows(IllegalArgumentException::class.java) { store.barcode = "x".repeat(InputChecks.MAX_BARCODE_CHARS + 1) }
        assertThrows(IllegalArgumentException::class.java) { store.barcode = "  " }
    }
    @Suppress("DEPRECATION")
    @Test fun helperWithWrongOrMissingSignatureReceivesNoCommandOrUriGrant() {
        val pm = shadowOf(context.packageManager)
        var grants = 0
        val tracking = object : ContextWrapper(context) {
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) { grants++ }
        }
        val info = context.packageManager.getPackageInfo(C.PACKAGE, PackageManager.GET_SIGNATURES)
        for (signatures in listOf(arrayOf(Signature("5678")), emptyArray<Signature>())) {
            info.signatures = signatures; pm.installPackage(info)
            assertFalse(AudioBridge.trusted(context))
            assertFalse(AudioBridge.start(tracking, active()))
            assertFalse(AudioBridge.openSetup(context))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(0, grants)
        }
        store.activate(active()); store.finishAlarm(active().id, active().barcode)
        AudioBridge.flushPendingStop(context)
        assertEquals(active().id, store.pendingAudioStop)
    }
    @Test fun deniedUriGrantDoesNotLeakSensitiveProviderDetailsInLogs() {
        val secret = "content://songs/sensitive-filename"
        val denied = object : ContextWrapper(context) {
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                throw SecurityException("No access to $secret")
            }
        }
        assertFalse(AudioBridge.start(denied, active().copy(songUri = secret)))
        assertFalse(ShadowLog.getLogs().any { it.msg.contains(secret) || it.throwable?.message?.contains(secret) == true })
    }
    @Test fun mainInternalComponentsAreNotExported() {
        val pm = context.packageManager
        for (type in listOf(AlarmEditorActivity::class.java, BarcodeActivity::class.java, PermissionsActivity::class.java))
            assertFalse(pm.getActivityInfo(android.content.ComponentName(context, type), 0).exported)
        for (type in listOf(AlarmReceiver::class.java, RestoreReceiver::class.java))
            assertFalse(pm.getReceiverInfo(android.content.ComponentName(context, type), 0).exported)
    }
    @Test fun helperUriGrantIsRevokedOnlyAfterStopAckAndLateDuplicateCannotRevokeNextSession() {
        val revoked = mutableListOf<String>()
        val tracking = object : ContextWrapper(context) {
            override fun revokeUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(C.PACKAGE, toPackage); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
                revoked.add(uri.toString())
            }
        }
        store.activate(active()); store.finishAlarm(active().id, active().barcode)
        AudioBridge.flushPendingStop(tracking)
        val stop = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertTrue(revoked.isEmpty()); assertEquals(active().songUri, store.pendingAudioStopUri)
        AlarmSchedulesTest.acknowledge(stop)
        assertEquals(listOf(active().songUri), revoked); assertNull(store.pendingAudioStopUri)
        assertTrue(store.activate(active().copy(id = "next-session")))
        AlarmSchedulesTest.acknowledge(stop)
        assertEquals(1, revoked.size); assertEquals("next-session", store.activeAlarm!!.id)
    }
}
