package com.madclock.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()
    private val locked = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var status: TextView

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status.text = "Для сканирования необходим доступ к камере. Разрешение можно включить в настройках приложения."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barcode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.scanStatus)
        val isRegister = intent.getStringExtra(EXTRA_MODE) == MODE_REGISTER
        findViewById<TextView>(R.id.scanTitle).text =
            if (isRegister) "Регистрация штрихкода" else "Сканируй код, чтобы выключить звук"
        status.text = "Наведи камеру на штрихкод или QR-код"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(findViewById<PreviewView>(R.id.cameraPreview).surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(executor) { imageProxy ->
                    val image = imageProxy.image
                    if (image == null || locked.get()) {
                        imageProxy.close()
                    } else {
                        val input = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { found ->
                                val value = found.firstNotNullOfOrNull { barcode -> barcode.rawValue }
                                if (!value.isNullOrBlank() && locked.compareAndSet(false, true)) {
                                    handleScan(value)
                                }
                            }
                            .addOnFailureListener { error ->
                                status.text = "Ошибка распознавания: ${error.localizedMessage ?: "повтори попытку"}"
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (error: Exception) {
                status.text = "Не удалось открыть камеру: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScan(value: String) {
        if (isFinishing || isDestroyed) return
        if (intent.getStringExtra(EXTRA_MODE) == MODE_REGISTER && AlarmStore(this).activeAlarm == null) {
            AlertDialog.Builder(this)
                .setTitle("Сохранить этот код?")
                .setMessage(value)
                .setPositiveButton("Сохранить") { _, _ ->
                    val store = AlarmStore(this)
                    if (store.activeAlarm != null) {
                        locked.set(false)
                        return@setPositiveButton
                    }
                    store.barcode = value
                    if (store.enabled) AlarmScheduler.scheduleNext(this)
                    Toast.makeText(this, "Код сохранён", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Повторить") { _, _ -> locked.set(false) }
                .setOnCancelListener { locked.set(false) }
                .show()
        } else {
            val active = AlarmStore(this).activeAlarm
            if (active != null && value == active.barcode) {
                status.text = "Код совпал. Будильник выключается."
                if (SignalController.stopAfterScan(this, active.id, value)) {
                    handler.postDelayed({ finish() }, 350L)
                } else {
                    status.text = "Не удалось завершить сохранённый сигнал. Повтори сканирование."
                    locked.set(false)
                }
            } else {
                status.text = "Это не зарегистрированный штрихкод. Попробуй другой."
                handler.postDelayed({ locked.set(false) }, 1400L)
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        scanner.close()
        executor.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        SignalController.onVisibleLaunch(this)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_REGISTER = "register"
        const val MODE_STOP = "stop"
    }
}
