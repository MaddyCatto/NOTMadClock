package com.madclock.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private lateinit var timePicker: TimePicker
    private lateinit var volume: SeekBar
    private lateinit var status: TextView
    private lateinit var signalMode: Spinner

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
        signalMode = findViewById(R.id.signalMode)
        signalMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.mode_a), getString(R.string.mode_b)))
        signalMode.setSelection(if (store.mode == SignalMode.NOTIFICATIONS) 0 else 1)
        signalMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                findViewById<TextView>(R.id.modeDescription).setText(
                    if (position == 0) R.string.mode_a_description else R.string.mode_b_description)
            }
        }

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
        val desiredMode = if (signalMode.selectedItemPosition == 0) SignalMode.NOTIFICATIONS else SignalMode.AUDIO_APP
        if (store.barcode.isNullOrBlank() || (desiredMode == SignalMode.AUDIO_APP && store.songUri.isNullOrBlank())) {
            toast("Зарегистрируй штрихкод; для режима B также выбери песню")
            return false
        }
        if (desiredMode == SignalMode.AUDIO_APP) {
            if (!AudioBridge.trusted(this)) {
                toast("Установи MadClock Audio из того же комплекта APK (одинаковая подпись)")
                return false
            }
            if (Build.VERSION.SDK_INT >= 33 && packageManager.checkPermission(
                    Manifest.permission.POST_NOTIFICATIONS, com.madclock.ipc.AudioContract.PACKAGE)
                != PackageManager.PERMISSION_GRANTED) {
                if (AudioBridge.openSetup(this)) toast("Разреши уведомления MadClock Audio и повтори запуск")
                else toast("Не удалось открыть помощник. Установи Audio APK, затем основной APK одной подписи")
                return false
            }
        } else {
            NotificationSignals.channel(this)
            val manager = getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(NotificationSignals.CHANNEL)
            if (!manager.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE ||
                channel?.sound == null) {
                toast("Для режима A разреши уведомления и звук канала MadClock A")
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationSignals.CHANNEL))
                return false
            }
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
        val nextMode = if (signalMode.selectedItemPosition == 0) SignalMode.NOTIFICATIONS else SignalMode.AUDIO_APP
        if (nextMode != store.mode) {
            AlarmScheduler.cancelTest(this)
            store.mode = nextMode
        }
    }

    private fun enableAlarm() {
        if (!canEnable()) return
        saveSettings()
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
        if (!canEnable()) return
        saveSettings()
        if (store.enabled) AlarmScheduler.scheduleNext(this)
        if (AlarmScheduler.scheduleTest(this)) {
            toast("Тестовый будильник прозвучит примерно через минуту")
        } else {
            toast("Не удалось запланировать тест")
        }
    }

    private fun refresh() {
        if (!::store.isInitialized || !::status.isInitialized) return
        val session = store.activeAlarm
        val active = session != null
        findViewById<LinearLayout>(R.id.configPanel).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.activePanel).visibility = if (active) View.VISIBLE else View.GONE
        status.text = when {
            active -> if (session?.mode == SignalMode.NOTIFICATIONS)
                "Активен A — системные уведомления. Выключение — штрихкодом."
                else "Активен B — MadClock Audio. Выключение — штрихкодом."
            store.pendingAudioStop != null -> "Ожидается подтверждение остановки MadClock Audio"
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
        SignalController.onVisibleLaunch(this)
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshTask)
        super.onPause()
    }
}
