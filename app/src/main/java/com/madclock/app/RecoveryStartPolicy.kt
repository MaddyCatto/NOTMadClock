package com.madclock.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/** Do not use a pending continuation to undo a system-reported explicit user stop. */
object RecoveryStartPolicy {
    fun permitsBackgroundRecovery(context: Context, active: ActiveAlarm): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val stopped = exits.firstOrNull {
                it.timestamp >= active.recoveryRequestedAtMs &&
                    (it.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                        it.reason == ApplicationExitInfo.REASON_USER_STOPPED)
            }
            if (stopped != null) {
                // Android's public USER_REQUESTED reason also includes some Recents kills.
                // Do not guess from OEM description strings or hidden subreason APIs.
                Log.w("MadClock", "Recovery suppressed: system user-stop reason=${stopped.reason} " +
                    "exitPid=${stopped.pid}; open app to resume session=${active.id}")
                false
            } else true
        } catch (error: RuntimeException) {
            Log.w("MadClock", "Recovery suppressed: cannot inspect system exit history", error)
            false
        }
    }
}
