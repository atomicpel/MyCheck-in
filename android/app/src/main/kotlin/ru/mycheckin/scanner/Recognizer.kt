package ru.mycheckin.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import ru.mycheckin.passport.Mrz
import ru.mycheckin.passport.PassportData
import ru.mycheckin.passport.Validation
import ru.mycheckin.passport.Viz
import ru.mycheckin.passport.VizFields
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Полное распознавание разворота паспорта по одному снимку.
 *
 * 1. ML Kit (латиница) определяет ориентацию и читает MRZ, даты и код подразделения.
 * 2. По якорям (MRZ, дата выдачи, дата рождения) вычисляются области «Кем выдан»,
 *    ФИО и «Место рождения» — их читает Tesseract с русским словарём.
 * 3. Серия и номер — из MRZ, а если её начало закрыто — из вертикальной надписи у края.
 *    Старый паспорт без MRZ: поворот и якоря определяются по датам и коду подразделения,
 *    серия и номер — с вертикальной надписи, всё без контрольных сумм.
 * 4. ML Kit находит лицо и вырезает фото владельца.
 *
 * Все методы блокирующие — вызывать не из главного потока.
 */
class Recognizer(private val context: Context) : Closeable {

    class Result(val passport: PassportData, val photo: Bitmap?)

    private val textClient = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val faceClient = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
    )
    private var tess: TessBaseAPI? = null
    private var tessFailed = false

    override fun close() {
        textClient.close()
        faceClient.close()
        tess?.recycle()
        tess = null
    }

    // ------------------------------------------------------------------

    private fun tesseract(): TessBaseAPI? {
        tess?.let { return it }
        if (tessFailed) return null
        val dir = File(context.filesDir, "tesseract")
        val data = File(dir, "tessdata/rus.traineddata")
        try {
            if (!data.exists()) {
                data.parentFile?.mkdirs()
                val tmp = File(data.parentFile, "rus.traineddata.tmp")
                context.assets.open("tessdata/rus.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(data)) throw IOException("rename failed")
            }
        } catch (e: IOException) {
            tessFailed = true
            return null
        }
        val api = TessBaseAPI()
        if (!api.init(dir.absolutePath, "rus")) {
            api.recycle()
            tessFailed = true
            return null
        }
        tess = api
        return api
    }

    private fun latin(bitmap: Bitmap): Text = Tasks.await(textClient.process(InputImage.fromBitmap(bitmap, 0)))

    private fun score(d: PassportData) = d.checks.values.count { it } * 10 - d.corrections.size

    /** Распознать снимок. [mrzHint] — строки MRZ, уже надёжно прочитанные по видеопотоку. */
    fun recognize(source: Bitmap, mrzHint: Pair<String, String>? = null, today: LocalDate = LocalDate.now()): Result {
        // 1. Ориентация: поворот, при котором лучше всего читается MRZ.
        var bestRot = 0
        var bestMrz: PassportData? = null
        var bestText: Text? = null
        val texts = LinkedHashMap<Int, Text>()
        for (rot in intArrayOf(0, 90, 270, 180)) {
            val t = latin(rotate(source, rot))
            texts[rot] = t
            val d = Mrz.parseText(OcrText.rows(t), today)
            if (d != null && (bestMrz == null || score(d) > score(bestMrz))) {
                bestMrz = d
                bestRot = rot
                bestText = t
            }
            if (d != null && d.allChecksPassed) break
        }
        // Старый паспорт без MRZ: поворот выбираем по тому, где читаются даты и код подразделения.
        if (bestMrz == null && mrzHint == null && texts.isNotEmpty()) {
            bestRot = texts.maxByOrNull { (_, t) -> Viz.orientationScore(t.text) }!!.key
            bestText = texts[bestRot]
        }
        val page = rotate(source, bestRot)
        val text = bestText ?: latin(page)

        val hinted = mrzHint?.let { Mrz.parse(it.first, it.second, today) }
        val mrz = listOfNotNull(hinted, bestMrz).maxByOrNull(::score)

        // 2. Визуальная зона.
        val viz = readViz(page, text, mrz)

        // 3. Сведение и проверки.
        val merged = Validation.validate(Viz.merge(mrz, viz, today), today)
        return Result(merged, cropFace(page))
    }

    private class Anchors(
        val mrz: Rect?, val issue: Rect?, val birth: Rect?,
        val issueDate: LocalDate? = null, val birthDate: LocalDate? = null,
    )

    private fun anchors(text: Text, mrz: PassportData?, pageHeight: Int): Anchors {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val mrzRect = lines.filter { Viz.isMrzLine(it.text) || "PNRUS" in it.text }
            .map { Rect(it.boundingBox!!) }
            .reduceOrNull { a, b -> a.apply { union(b) } }
        fun lineWith(date: LocalDate?): Rect? =
            date?.let { d -> lines.firstOrNull { d in Viz.dates(it.text) }?.boundingBox }
        val issue = lineWith(mrz?.issueDate)
            ?: lines.firstOrNull { !Viz.isMrzLine(it.text) && Viz.divisionCode(it.text) != null }?.boundingBox
        val birth = lineWith(mrz?.birthDate)
        if (mrz == null) {
            // Без MRZ даты неизвестны заранее — ищем строки с датой выдачи и датой рождения по расположению.
            val found = Viz.locateNoMrz(lines.map { Viz.Line(it.text, it.boundingBox!!.top, it.boundingBox!!.bottom) }, pageHeight)
            fun rectOf(l: Viz.Line?) = l?.let { x -> lines.firstOrNull { it.text == x.text && it.boundingBox!!.top == x.top }?.boundingBox }
            return Anchors(mrzRect, rectOf(found.issue) ?: issue, rectOf(found.birth), found.issueDate, found.birthDate)
        }
        return Anchors(mrzRect, issue, birth)
    }

    private fun readViz(page: Bitmap, text: Text, mrz: PassportData?): VizFields {
        val a = anchors(text, mrz, page.height)
        val w = page.width
        val pageL = a.mrz?.left ?: (w * 0.05).toInt()
        val pageR = a.mrz?.right ?: (w * 0.95).toInt()
        val pw = pageR - pageL
        val right = pageR - (pw * 0.07).toInt()          // без вертикальной серии/номера
        val valueLeft = pageL + (pw * 0.33).toInt()       // правее фотографии

        val viz = VizFields()
        val latinText = text.textBlocks.flatMap { it.lines }.map { it.text }.filterNot(Viz::isMrzLine).joinToString("\n")
        viz.divisionCode = Viz.divisionCode(latinText)

        a.issue?.let { r ->
            val h = r.height().coerceAtLeast(10)
            viz.issueDate = mrz?.issueDate ?: a.issueDate ?: Viz.dates(latinText).firstOrNull()
            viz.issuedBy = Viz.joinLines(ocrRu(page, Rect(pageL, r.top - 6 * h, right, r.top), h))
        }
        a.birth?.let { r ->
            val h = r.height().coerceAtLeast(10)
            viz.birthDate = mrz?.birthDate ?: a.birthDate
            val names = ocrRu(page, Rect(valueLeft, r.top - 7 * h, right, r.bottom + h / 3), h)
            viz.sex = Viz.sex(names.joinToString(" "))
            val words = names.filter { Viz.dates(it).isEmpty() }.mapNotNull(Viz::nameWord).takeLast(3)
            if (words.size == 3) {
                viz.surname = words[0]; viz.name = words[1]; viz.patronymic = words[2]
            }
            val bottom = a.mrz?.top ?: (r.bottom + 4 * h)
            viz.birthPlace = Viz.joinLines(
                ocrRu(page, Rect(valueLeft - (pw * 0.05).toInt(), r.bottom, right, bottom), h).filterNot(Viz::isMrzLine)
            )
        }

        // Якоря не нашлись — читаем всю страницу и разбираем текст по порядку строк.
        if (viz.issuedBy == null || viz.birthPlace == null) {
            val full = ocrRu(page, Rect(0, 0, page.width, page.height), (page.height / 60).coerceAtLeast(10),
                TessBaseAPI.PageSegMode.PSM_AUTO)
            val f = Viz.parseFullText(full.joinToString("\n"), mrz)
            viz.issuedBy = viz.issuedBy ?: f.issuedBy
            viz.birthPlace = viz.birthPlace ?: f.birthPlace
            viz.issueDate = viz.issueDate ?: f.issueDate
            viz.birthDate = viz.birthDate ?: f.birthDate
            viz.sex = viz.sex ?: f.sex
            viz.divisionCode = viz.divisionCode ?: f.divisionCode
            if (viz.surname == null) {
                viz.surname = f.surname; viz.name = f.name; viz.patronymic = f.patronymic
            }
        }

        // Серия и номер напечатаны вертикально у правого края обеих страниц.
        if (mrz?.series == null || mrz.number == null) {
            val strip = Rect(pageR - (pw * 0.12).toInt(), 0, minOf(page.width, pageR + (pw * 0.06).toInt()), a.mrz?.top ?: page.height)
            if (strip.width() > 10 && strip.height() > 10) {
                val crop = Bitmap.createBitmap(page, strip.left, strip.top, strip.width(), strip.height())
                for (rot in intArrayOf(90, 270)) {
                    val sn = Viz.seriesNumber(latin(rotate(crop, rot)).text)
                    if (sn != null) {
                        viz.series = sn.first; viz.number = sn.second
                        break
                    }
                }
            }
        }
        return viz
    }

    /** Распознать область на русском. Масштабируем так, чтобы высота строки была ~40 px. */
    private fun ocrRu(page: Bitmap, region: Rect, lineHeight: Int, psm: Int = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK): List<String> {
        val api = tesseract() ?: return emptyList()
        val r = Rect(region)
        if (!r.intersect(0, 0, page.width, page.height) || r.width() < 20 || r.height() < 10) return emptyList()
        var crop = Bitmap.createBitmap(page, r.left, r.top, r.width(), r.height())
        val scale = (40f / lineHeight).coerceIn(1f, 3f).coerceAtMost(4000f / crop.width)
        if (scale > 1.05f) crop = Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
        api.setPageSegMode(psm)
        api.setImage(gray(crop))
        val out = api.getUTF8Text().orEmpty()
        api.clear()
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun cropFace(page: Bitmap): Bitmap? {
        val faces = try {
            Tasks.await(faceClient.process(InputImage.fromBitmap(page, 0)))
        } catch (e: Exception) {
            return null
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val b = face.boundingBox
        // Портрет как на фото в паспорте: с плечами и запасом над головой.
        val w = (b.width() * 1.8f).toInt()
        val h = (w * 1.3f).toInt()
        val cx = b.centerX()
        val top = b.centerY() - (h * 0.45f).toInt()
        val r = Rect(cx - w / 2, top, cx + w / 2, top + h)
        if (!r.intersect(0, 0, page.width, page.height)) return null
        val crop = Bitmap.createBitmap(page, r.left, r.top, r.width(), r.height())
        val k = 600f / maxOf(crop.width, crop.height)
        return if (k < 1f) Bitmap.createScaledBitmap(crop, (crop.width * k).toInt(), (crop.height * k).toInt(), true) else crop
    }

    companion object {
        fun rotate(src: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return src
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        /** Уменьшить до разумного размера: больше 3200 px распознавание не улучшает. */
        fun limit(src: Bitmap, maxSide: Int = 3200): Bitmap {
            val k = maxSide.toFloat() / maxOf(src.width, src.height)
            return if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        }

        private fun gray(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            return out
        }
    }
}
