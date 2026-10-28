package ru.mycheckin.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.View
import android.widget.TextView
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
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import ru.mycheckin.passport.sim.SimCode
import ru.mycheckin.passport.sim.SimCodes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Сканер SIM-карты: штрихкод (обычно ICCID) и печатный текст (номер телефона, ICCID).
 * Значение принимается, когда одинаково прочитано минимум в двух кадрах. В режиме поиска
 * сразу проверяется, есть ли такая SIM в списке, — тогда возвращается и её карточка.
 */
class CodeScanActivity : AppCompatActivity() {

    companion object {
        /** Что ищем: [TARGET_ANY] (поиск), [TARGET_PHONE] или [TARGET_ICCID] (поле карточки). */
        const val EXTRA_TARGET = "target"
        const val TARGET_ANY = "any"
        const val TARGET_PHONE = "phone"
        const val TARGET_ICCID = "iccid"

        const val RESULT_VALUE = "value"
        const val RESULT_KIND = "kind"
        const val RESULT_SIM_ID = "sim_id"

        private const val VOTES = 2
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: MrzOverlayView
    private lateinit var status: TextView
    private lateinit var foundCard: MaterialCardView
    private lateinit var torch: MaterialButton
    private var camera: Camera? = null
    private lateinit var analysis: ExecutorService

    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val target by lazy { intent.getStringExtra(EXTRA_TARGET) ?: TARGET_ANY }

    /** Сколько кадров подряд/всего видели каждое значение. */
    private val votes = HashMap<SimCode, Int>()
    @Volatile private var done = false

    /** Список SIM для мгновенной проверки совпадения (грузится в фоне). */
    @Volatile private var sims: List<SimRecord> = emptyList()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = getString(R.string.no_camera_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_code_scan)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        foundCard = findViewById(R.id.found_card)
        torch = findViewById(R.id.btn_torch)
        analysis = Executors.newSingleThreadExecutor()

        findViewById<TextView>(R.id.title).text = when (target) {
            TARGET_PHONE -> "Сканировать номер"
            TARGET_ICCID -> "Сканировать SIM (ICCID)"
            else -> "Найти SIM по коду"
        }
        findViewById<TextView>(R.id.hint).text = when (target) {
            TARGET_PHONE -> "Наведите рамку на номер телефона (на пластике SIM, в договоре, на экране)"
            TARGET_ICCID -> "Наведите рамку на штрихкод или 19–20-значный номер на пластике SIM-карты"
            else -> "Наведите рамку на штрихкод или номер на пластике SIM-карты — либо на номер телефона"
        }
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        torch.setOnClickListener { camera?.cameraControl?.enableTorch(torch.isChecked) }

        analysis.execute { sims = runCatching { SimDb.get(this).all() }.getOrDefault(emptyList()) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
        barcodes.close()
        text.close()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            a.setAnalyzer(analysis, ::analyze)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, a)
            torch.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val found = try {
            val codes = Tasks.await(barcodes.process(input)).mapNotNull { b -> b.rawValue?.let(SimCodes::fromBarcode) }
            val words = SimCodes.fromText(Tasks.await(text.process(input)).text)
            (codes + words).distinct()
        } catch (e: Exception) {
            emptyList()
        } finally {
            proxy.close()
        }
        val wanted = found.filter {
            when (target) {
                TARGET_PHONE -> it.kind == SimCode.Kind.PHONE
                TARGET_ICCID -> it.kind == SimCode.Kind.ICCID
                else -> true
            }
        }
        if (wanted.isEmpty()) return
        wanted.forEach { votes[it] = (votes[it] ?: 0) + 1 }
        val best = wanted.maxBy { votes[it] ?: 0 }
        val match = matchOf(best)
        runOnUiThread { show(best, match, votes[best] ?: 0) }

        // Для поиска лучше значение, которое есть в списке; иначе — любое уверенно прочитанное.
        val confirmed = wanted.filter { (votes[it] ?: 0) >= VOTES }
        val chosen = confirmed.firstOrNull { matchOf(it) != null } ?: confirmed.firstOrNull { it.kind == SimCode.Kind.ICCID }
            ?: confirmed.firstOrNull()
        if (chosen != null && !done) {
            done = true
            runOnUiThread { finishWith(chosen, matchOf(chosen)) }
        }
    }

    private fun matchOf(c: SimCode): SimRecord? = when (c.kind) {
        SimCode.Kind.PHONE -> sims.firstOrNull { it.msisdn == c.value }
        SimCode.Kind.ICCID -> sims.firstOrNull { SimCodes.sameIccid(it.iccid, c.value) }
    }

    private fun show(c: SimCode, match: SimRecord?, count: Int) {
        overlay.progress = if (count >= VOTES) 4 else 2
        findViewById<TextView>(R.id.found_kind).text = c.kind.label
        findViewById<TextView>(R.id.found_value).text = if (c.kind == SimCode.Kind.PHONE) SimFormat.phone(c.value) else c.value
        findViewById<TextView>(R.id.found_sim).text = when {
            target != TARGET_ANY -> "Держите ровно — подтверждаю…"
            match != null -> "В списке: " + match.fullName.ifEmpty { "Без ФИО" } + " · " + SimFormat.phone(match.msisdn)
            else -> "В списке пока не найдено"
        }
        findViewById<TextView>(R.id.found_sim).setTextColor(
            if (match != null || target != TARGET_ANY) getColor(R.color.ok) else Color.GRAY)
        if (foundCard.visibility != View.VISIBLE) {
            foundCard.alpha = 0f; foundCard.translationY = 40f; foundCard.visibility = View.VISIBLE
            foundCard.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        status.text = "Прочитано: ${count} из $VOTES"
    }

    private fun finishWith(c: SimCode, match: SimRecord?) {
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
        Journal.event(this, LogLevel.INFO, LogCategory.CARD,
            "Сканирование SIM: ${c.kind.label.lowercase()} ${if (c.kind == SimCode.Kind.PHONE) SimFormat.phone(c.value) else c.value}",
            if (match != null) "Найдена карточка: ${match.fullName.ifEmpty { "Без ФИО" }}" else "В списке не найдено",
            msisdn = match?.msisdn ?: if (c.kind == SimCode.Kind.PHONE) c.value else "")
        setResult(RESULT_OK, Intent()
            .putExtra(RESULT_VALUE, c.value)
            .putExtra(RESULT_KIND, c.kind.name)
            .apply { match?.let { putExtra(RESULT_SIM_ID, it.id) } })
        finish()
    }
}
