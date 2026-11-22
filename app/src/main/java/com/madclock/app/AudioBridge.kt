package com.madclock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import com.madclock.ipc.AudioContract as C
import com.madclock.ipc.InputChecks
import com.madclock.ipc.SleepSettings
import com.madclock.ipc.SleepStatus
import java.util.concurrent.atomic.AtomicBoolean

object AudioBridge {
    fun installed(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(C.PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
    fun trusted(context: Context) = installed(context) &&
        context.packageManager.checkSignatures(context.packageName, C.PACKAGE) == PackageManager.SIGNATURE_MATCH
    fun openSetup(context: Context): Boolean {
        if (!trusted(context)) return false
        return runCatching {
            context.startActivity(Intent().setComponent(ComponentName(C.PACKAGE, C.SETUP))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            true
        }.getOrElse { Log.w("MadClock", "Audio setup unavailable", it); false }
    }
    fun start(context: Context, active: ActiveAlarm): Boolean {
        if (!trusted(context) || !InputChecks.session(active.id) ||
            (active.songUri != null && !InputChecks.song(active.songUri)) ||
            active.volumePercent !in 1..100) return false
        return try {
            val command = serviceCommand(C.START).putExtra(C.SESSION, active.id)
                .putExtra(C.VOLUME, active.volumePercent)
            if (active.songUri == null) command.putExtra(C.DEFAULT_ALARM, true)
            else {
                val uri = Uri.parse(active.songUri)
                context.grantUriPermission(C.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                command.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(command)
            Log.i("MadClock", "Audio command sent: session=${active.id}")
            true
        } catch (e: RuntimeException) {
            Log.e("MadClock", "Audio APK start rejected: ${e.javaClass.simpleName}"); false
        }
    }
    private fun stop(context: Context, id: String, done: (Boolean) -> Unit) {
        if (!installed(context)) { done(true); return }
        if (!trusted(context)) { done(false); return }
        exchange(
            send = { context.startForegroundService(serviceCommand(C.STOP).putExtra(C.SESSION, id).putExtra(C.ACK, it)) },
            done = { code, _ -> done(code == C.OK) }
        )
    }

    fun sleepStatus(context: Context, done: (SleepStatus?) -> Unit) {
        if (!trusted(context)) { done(null); return }
        exchange(
            send = {
                val intent = Intent(C.SLEEP_STATUS).setComponent(ComponentName(C.PACKAGE, C.SLEEP_STATUS_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra(C.ACK, it)
                context.sendBroadcast(intent, C.PERMISSION)
            },
            done = { code, data -> done(if (code == C.OK) SleepStatus.fromBundle(data) else null) }
        )
    }

    fun toggleSleep(context: Context, status: SleepStatus, settings: SleepSettings, done: (Boolean, SleepStatus?) -> Unit) {
        if (!trusted(context)) { done(false, null); return }
        val action = if (status.running) C.SLEEP_STOP else C.SLEEP_PLAY
        val intent = sleepCommand(action, status, settings)
        exchange(
            send = { context.startForegroundService(intent.putExtra(C.ACK, it)) },
            done = { code, data -> done(code == C.OK, SleepStatus.fromBundle(data)) }
        )
    }

    fun updateSleep(context: Context, status: SleepStatus, settings: SleepSettings) {
        if (status.running && trusted(context)) runCatching {
            context.startForegroundService(sleepCommand(C.SLEEP_UPDATE, status, settings))
        }.onFailure { Log.w("MadClock", "White noise update unavailable") }
    }

    private fun sleepCommand(action: String, status: SleepStatus, settings: SleepSettings) =
        serviceCommand(action).putExtra(C.SLEEP_TICKET, status.ticket)
            .putExtra(C.SLEEP_SENT_AT, SystemClock.elapsedRealtimeNanos())
            .putExtra(C.SLEEP_SOUND, settings.sound.id).putExtra(C.SLEEP_TIMER, settings.timerMinutes)
            .putExtra(C.VOLUME, settings.volume)

    private fun serviceCommand(action: String) = Intent(action).setComponent(ComponentName(C.PACKAGE, C.SERVICE))

    private fun exchange(send: (ResultReceiver) -> Unit, done: (Int, Bundle?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        fun finish(code: Int, data: Bundle?) {
            if (completed.compareAndSet(false, true)) done(code, data)
        }
        val timeout = Runnable { finish(C.FAILED, null) }
        handler.postDelayed(timeout, 8_000)
        val ack = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                handler.removeCallbacks(timeout)
                finish(resultCode, data)
            }
        }
        // Send the framework class carrying the same Binder endpoint, not an APK-local subclass.
        val parcel = Parcel.obtain()
        val remoteAck = try {
            ack.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        try { send(remoteAck) }
        catch (e: RuntimeException) {
            handler.removeCallbacks(timeout)
            Log.w("MadClock", "Audio IPC unavailable: ${e.javaClass.simpleName}")
            finish(C.FAILED, null)
        }
    }
    fun flushPendingStop(context: Context, done: (Boolean) -> Unit = {}) {
        val store = AlarmStore(context)
        val id = store.pendingAudioStop
        if (id == null) { done(true); return }
        val source = store.pendingAudioStopUri
        stop(context, id) { ok ->
            if (ok && store.pendingAudioStop == id) {
                // Wait for durable STOP acknowledgement before revoking the helper's read grant.
                // A duplicate late ACK must not revoke a later session's grant to the same song.
                source?.let { uri -> runCatching {
                    context.revokeUriPermission(C.PACKAGE, Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { Log.w("MadClock", "Audio URI grant cleanup unavailable") } }
                store.acknowledgeStop(id)
            }
            done(ok)
        }
    }
}
