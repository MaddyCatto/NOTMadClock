package com.madclock.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private lateinit var timePicker: TimePicker
    private lateinit var volume: SeekBar
    private lateinit var status: TextView

    private val chooseSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                store.songUri = uri.toString()
                store.songName = resolveName(uri)
                if (store.enabled) AlarmScheduler.scheduleNext(this)
                refresh()
            } catch (_: SecurityException) {
                toast("Не удалось получить постоянный доступ к аудиофайлу")
            }
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        setContentView(R.layout.activity_main)
        timePicker = findViewById(R.id.timePicker)
        timePicker.setIs24HourView(true)
        timePicker.hour = store.hour
        timePicker.minute = store.minute
        volume = findViewById(R.id.volume)
        volume.progress = store.volumePercent
        status = findViewById(R.id.status)

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.volumeValue).text = "${progress.coerceAtLeast(1)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        findViewById<Button>(R.id.selectSong).setOnClickListener {
            chooseSong.launch(arrayOf("audio/*"))
        }
        findViewById<Button>(R.id.selectBarcode).setOnClickListener {
            startActivity(Intent(this, BarcodeActivity::class.java).putExtra(
                BarcodeActivity.EXTRA_MODE, BarcodeActivity.MODE_REGISTER
            ))
        }
        findViewById<Button>(R.id.stopWithBarcode).setOnClickListener {
            startActivity(Intent(this, BarcodeActivity::class.java).putExtra(
                BarcodeActivity.EXTRA_MODE, BarcodeActivity.MODE_STOP
            ))
        }
        findViewById<Button>(R.id.saveAlarm).setOnClickListener { enableAlarm() }
        findViewById<Button>(R.id.disableAlarm).setOnClickListener {
            store.enabled = false
            AlarmScheduler.disableDaily(this)
            AlarmScheduler.cancelTest(this)
            toast("Будущие будильники отменены")
            refresh()
        }
        findViewById<Button>(R.id.testAlarm).setOnClickListener { testAlarm() }
        findViewById<Button>(R.id.notificationSettings).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppSettings()
            }
        }
        findViewById<Button>(R.id.fullscreenSettings).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }.onFailure { openAppSettings() }
            } else openAppSettings()
        }
        findViewById<Button>(R.id.batterySettings).setOnClickListener {
            openAppSettings()
        }
        refresh()
    }

    private fun canEnable(): Boolean {
        if (!store.isConfigured()) {
            toast("Сначала выбери песню и зарегистрируй штрихкод")
            return false
        }
        if (!AlarmScheduler.hasExactAlarmAccess(this)) {
            toast("Разреши точные будильники в настройках")
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
                    .onFailure { openAppSettings() }
            }
            return false
        }
        return true
    }

    private fun saveSettings() {
        store.hour = timePicker.hour
        store.minute = timePicker.minute
        store.volumePercent = volume.progress.coerceAtLeast(1)
    }

    private fun enableAlarm() {
        saveSettings()
        if (!canEnable()) return
        store.enabled = true
        if (AlarmScheduler.scheduleNext(this)) {
            toast("Ежедневный будильник включён")
        } else {
            store.enabled = false
            toast("Не удалось запланировать будильник")
        }
        refresh()
    }

    private fun testAlarm() {
        saveSettings()
        if (!canEnable()) return
        if (AlarmScheduler.scheduleTest(this)) {
            toast("Тестовый будильник прозвучит примерно через минуту")
        } else {
            toast("Не удалось запланировать тест")
        }
    }

    private fun refresh() {
        if (!::store.isInitialized || !::status.isInitialized) return
        val active = store.activeAlarm != null
        val ringing = AlarmService.isRinging
        findViewById<LinearLayout>(R.id.configPanel).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.activePanel).visibility = if (active) View.VISIBLE else View.GONE
        status.text = when {
            ringing -> "БУДИЛЬНИК ЗВУЧИТ. Выключение — только сканированием."
            active -> "Активный будильник сохранён. Восстановление звука — при открытии приложения."
            store.enabled -> "Включён ежедневно: %02d:%02d".format(store.hour, store.minute)
            else -> "Будильник выключен"
        }
        if (!active) {
            findViewById<TextView>(R.id.songValue).text = store.songName ?: "Песня не выбрана"
            findViewById<TextView>(R.id.barcodeValue).text =
                if (store.barcode.isNullOrBlank()) "Штрихкод не зарегистрирован" else "Штрихкод зарегистрирован"
            findViewById<TextView>(R.id.volumeValue).text = "${volume.progress.coerceAtLeast(1)}%"
        }
        if (active && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun resolveName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment ?: "Выбранная песня"
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        // Visible launch is a permitted FGS start; do not fire/schedule the daily alarm again.
        store.activeAlarm?.let { AlarmService.resume(this, it.id) }
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshTask)
        super.onPause()
    }
}
