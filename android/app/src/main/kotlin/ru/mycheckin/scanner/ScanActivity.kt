package ru.mycheckin.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import ru.mycheckin.passport.Mrz
import ru.mycheckin.passport.MrzAccumulator
import ru.mycheckin.passport.PassportData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: MrzOverlayView
    private lateinit var status: TextView
    private lateinit var torchButton: MaterialButton

    private var camera: Camera? = null
    private lateinit var executor: ExecutorService
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val accumulator = MrzAccumulator(window = 8)

    @Volatile
    private var paused = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.no_camera_permission)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        torchButton = findViewById(R.id.btn_torch)
        executor = Executors.newSingleThreadExecutor()

        findViewById<MaterialButton>(R.id.btn_gallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<MaterialButton>(R.id.btn_manual).setOnClickListener {
            paused = true
            var opened = false
            MrzDialog.show(this, "", "", onDismiss = { if (!opened) paused = false }) { l1, l2 ->
                opened = true
                openResult(l1, l2)
            }
        }
        torchButton.setOnClickListener { camera?.cameraControl?.enableTorch(torchButton.isChecked) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        paused = false
        accumulator.clear()
        overlay.progress = 0
        status.setText(R.string.status_searching)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Высокое разрешение: символы MRZ мелкие.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || paused) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { text -> onFrameText(OcrText.rows(text)) }
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * Каждый кадр добавляется в голосование. Результат принимается, когда сошлись
     * все контрольные цифры и минимум два кадра независимо дали ту же запись.
     */
    private fun onFrameText(rows: String) {
        if (paused || !accumulator.addText(rows)) return
        val result = accumulator.result() ?: return
        val ok = result.checks.values.count { it }
        overlay.progress = ok
        status.text = getString(R.string.status_progress, accumulator.size, ok)
        if (result.allChecksPassed && accumulator.agreeing(result) >= 2) {
            paused = true
            vibrate()
            val (l1, l2) = result.mrzLines!!
            openResult(l1, l2)
        }
    }

    private fun scanImage(uri: Uri) {
        paused = true
        status.setText(R.string.status_processing)
        executor.execute {
            val result = try {
                decodeBitmap(uri)?.let(::bestFromBitmap)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                val lines = result?.mrzLines
                if (lines != null) {
                    openResult(lines.first, lines.second)
                } else {
                    paused = false
                    status.setText(R.string.status_not_found)
                    Toast.makeText(this, R.string.status_not_found, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Фото может быть повёрнуто как угодно — пробуем все четыре ориентации. */
    private fun bestFromBitmap(bitmap: Bitmap): PassportData? {
        var best: PassportData? = null
        for (rotation in intArrayOf(0, 90, 270, 180)) {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)))
            val data = Mrz.parseText(OcrText.rows(text)) ?: continue
            if (best == null || score(data) > score(best)) best = data
            if (data.allChecksPassed) break
        }
        return best
    }

    private fun score(d: PassportData) = d.checks.values.count { it } * 10 - d.corrections.size

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun openResult(line1: String, line2: String) {
        startActivity(
            Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_LINE1, line1)
                .putExtra(ResultActivity.EXTRA_LINE2, line2)
        )
    }
}
