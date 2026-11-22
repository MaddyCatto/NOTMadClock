package com.madclock.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.madclock.ipc.AudioContract as C

class PermissionsActivity : ComponentActivity() {
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions); UiSupport.decorate(this)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.exactPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName")))
            }.onFailure { settings(packageName) } else settings(packageName)
        }
        findViewById<Button>(R.id.audioPermission).setOnClickListener {
            if (!AudioBridge.openSetup(this)) UiSupport.toast(this, "Установите MadClock Audio из того же комплекта APK")
        }
        findViewById<Button>(R.id.cameraPermission).setOnClickListener { camera.launch(Manifest.permission.CAMERA) }
        findViewById<Button>(R.id.audioSettings).setOnClickListener {
            if (AudioBridge.installed(this)) settings(C.PACKAGE) else UiSupport.toast(this, "MadClock Audio не установлен")
        }
        findViewById<Button>(R.id.mainSettings).setOnClickListener { settings(packageName) }
    }
    private fun settings(pkg: String) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg"))) }
    private fun refresh() {
        if (findViewById<TextView>(R.id.permissionStatus) == null) return
        fun state(yes: Boolean) = if (yes) "Разрешено" else "Требует настройки"
        val notification = Build.VERSION.SDK_INT < 33 || packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, C.PACKAGE) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.permissionStatus).text = listOf(
            "MadClock Audio: ${if (AudioBridge.trusted(this)) "установлен, подпись верна" else "не установлен или другая подпись"}",
            "Точные будильники: ${state(AlarmScheduler.hasExactAlarmAccess(this))}",
            "Уведомления Audio: ${state(notification)}",
            "Камера: ${state(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)}"
        ).joinToString("\n\n")
    }
    override fun onResume() { super.onResume(); refresh() }
}
