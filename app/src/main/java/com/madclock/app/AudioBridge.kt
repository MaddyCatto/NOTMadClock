package com.madclock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import com.madclock.ipc.AudioContract as C
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
        if (!trusted(context) || active.songUri == null) return false
        return try {
            val uri = Uri.parse(active.songUri)
            context.grantUriPermission(C.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startForegroundService(Intent(C.START).setComponent(ComponentName(C.PACKAGE, C.SERVICE))
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(C.SESSION, active.id).putExtra(C.VOLUME, active.volumePercent))
            Log.i("MadClock", "Mode B command sent: session=${active.id}")
            true
        } catch (e: RuntimeException) {
            Log.e("MadClock", "Audio APK start rejected; no main-app audio fallback", e); false
        }
    }
    private fun stop(context: Context, id: String?, done: (Boolean) -> Unit) {
        if (!installed(context)) { done(true); return }
        if (!trusted(context)) { done(false); return }
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        fun finish(ok: Boolean) { if (completed.compareAndSet(false, true)) done(ok) }
        val timeout = Runnable { finish(false) }
        handler.postDelayed(timeout, 8_000)
        val ack = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                handler.removeCallbacks(timeout)
                finish(resultCode == C.OK)
            }
        }
        try {
            context.startForegroundService(Intent(if (id == null) C.STOP_ALL else C.STOP)
                .setComponent(ComponentName(C.PACKAGE, C.SERVICE)).putExtra(C.SESSION, id).putExtra(C.ACK, ack))
        } catch (e: RuntimeException) {
            handler.removeCallbacks(timeout)
            Log.e("MadClock", "Audio APK stop rejected; pending stop retained", e)
            finish(false)
        }
    }
    fun ensureQuiet(context: Context, done: (Boolean) -> Unit) = stop(context, null, done)
    fun flushPendingStop(context: Context, done: (Boolean) -> Unit = {}) {
        val store = AlarmStore(context)
        val id = store.pendingAudioStop
        if (id == null) { done(true); return }
        stop(context, id) { ok ->
            if (ok) store.acknowledgeStop(id)
            done(ok)
        }
    }
}
