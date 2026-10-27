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
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import ru.mycheckin.passport.MrzAccumulator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экран камеры. По видеопотоку быстро читается MRZ (голосование по кадрам);
 * как только она надёжно прочитана, делается полноразмерный снимок и
 * распознаются остальные поля разворота.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: MrzOverlayView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var torchButton: MaterialButton

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var workExecutor: ExecutorService
    private val mrzReader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by lazy { Recognizer(applicationContext) }
    private val accumulator = MrzAccumulator(window = 8)

    @Volatile
    private var paused = false

    /** Запасной путь, если ImageCapture недоступен: берём кадр анализа. */
    @Volatile
    private var grabFrame: ((Bitmap) -> Unit)? = null

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
        progress = findViewById(R.id.progress)
        torchButton = findViewById(R.id.btn_torch)
        analysisExecutor = Executors.newSingleThreadExecutor()
        workExecutor = Executors.newSingleThreadExecutor()

        findViewById<MaterialButton>(R.id.btn_gallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<MaterialButton>(R.id.btn_manual).setOnClickListener {
            ScanSession.clear()
            startActivity(Intent(this, FormActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        torchButton.setOnClickListener { camera?.cameraControl?.enableTorch(torchButton.isChecked) }

        // Тяжёлую инициализацию (словарь Tesseract) делаем заранее, пока человек наводит камеру.
        workExecutor.execute { runCatching { recognizer } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        paused = false
        grabFrame = null
        accumulator.clear()
        overlay.progress = 0
        setBusy(false)
        status.setText(R.string.status_searching)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        workExecutor.execute { recognizer.close() }
        workExecutor.shutdown()
        mrzReader.close()
    }

    private fun setBusy(busy: Boolean, text: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        text?.let { status.text = it }
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
            analysis.setAnalyzer(analysisExecutor, ::analyze)
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            provider.unbindAll()
            camera = try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                    .also { imageCapture = capture }
            } catch (e: IllegalArgumentException) {
                // Камера не тянет три потока одновременно — обойдёмся кадром анализа.
                imageCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
            torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        grabFrame?.let { consumer ->
            grabFrame = null
            val bmp = Recognizer.rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            proxy.close()
            consumer(bmp)
            return
        }
        val media = proxy.image
        if (media == null || paused) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        mrzReader.process(input)
            .addOnSuccessListener { text -> onFrameText(OcrText.rows(text)) }
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * Кадры копятся для голосования. Когда сошлись все контрольные цифры и минимум
     * два кадра независимо дали ту же запись — снимаем фото для остальных полей.
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
            capture(result.mrzLines)
        }
    }

    private fun capture(mrzHint: Pair<String, String>?) {
        setBusy(true, getString(R.string.status_reading_rest))
        val ic = imageCapture
        if (ic == null) {
            grabFrame = { bmp -> process(bmp, mrzHint) }
            return
        }
        ic.takePicture(workExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try {
                    Recognizer.rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                } finally {
                    image.close()
                }
                process(bmp, mrzHint)
            }

            override fun onError(exception: ImageCaptureException) {
                grabFrame = { bmp -> process(bmp, mrzHint) }
            }
        })
    }

    /** Полное распознавание (в фоне) и переход к форме. */
    private fun process(bitmap: Bitmap, mrzHint: Pair<String, String>?) {
        workExecutor.execute {
            val result = try {
                recognizer.recognize(Recognizer.limit(bitmap), mrzHint)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null || (result.passport.mrzLines == null && result.passport.series == null)) {
                    paused = false
                    setBusy(false, getString(R.string.status_not_found))
                    Toast.makeText(this, R.string.status_not_found, Toast.LENGTH_LONG).show()
                } else {
                    ScanSession.passport = result.passport
                    ScanSession.photo = result.photo
                    startActivity(Intent(this, FormActivity::class.java))
                }
            }
        }
    }

    private fun scanImage(uri: Uri) {
        paused = true
        setBusy(true, getString(R.string.status_processing))
        workExecutor.execute {
            val bmp = try {
                decodeBitmap(uri)
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                runOnUiThread {
                    paused = false
                    setBusy(false, getString(R.string.status_not_found))
                }
            } else {
                process(bmp, null)
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
