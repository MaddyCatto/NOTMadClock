package com.madclock.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.madclock.ipc.AudioContract as C

object UiSupport {
    fun decorate(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById<View>(R.id.appRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
    fun repeat(context: android.content.Context, value: AlarmRepeat) = context.getString(if (value == AlarmRepeat.DAILY) R.string.daily else R.string.once)
    fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    fun barcode(activity: Activity, register: Boolean) = activity.startActivity(Intent(activity, BarcodeActivity::class.java)
        .putExtra(BarcodeActivity.EXTRA_MODE, if (register) BarcodeActivity.MODE_REGISTER else BarcodeActivity.MODE_STOP))
    fun ready(activity: Activity, record: AlarmRecord, exact: Boolean = true): Boolean {
        if (record.songUri.isNullOrBlank()) { toast(activity, "Выберите локальную песню"); return false }
        if (AlarmStore(activity).barcode.isNullOrBlank()) {
            AlertDialog.Builder(activity).setTitle(R.string.register_barcode)
                .setMessage("Один зарегистрированный штрихкод нужен для выключения всех будильников.")
                .setPositiveButton(R.string.register_barcode) { _, _ -> barcode(activity, true) }
                .setNegativeButton(R.string.cancel, null).show()
            return false
        }
        if (!AudioBridge.trusted(activity)) { toast(activity, "Установите MadClock Audio из того же комплекта APK"); return false }
        if (Build.VERSION.SDK_INT >= 33 && activity.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, C.PACKAGE)
            != PackageManager.PERMISSION_GRANTED) {
            AudioBridge.openSetup(activity)
            toast(activity, "Разрешите уведомления MadClock Audio и повторите действие")
            return false
        }
        if (exact && !AlarmScheduler.hasExactAlarmAccess(activity)) {
            activity.startActivity(Intent(activity, PermissionsActivity::class.java))
            toast(activity, "Разрешите точные будильники"); return false
        }
        return true
    }
}
