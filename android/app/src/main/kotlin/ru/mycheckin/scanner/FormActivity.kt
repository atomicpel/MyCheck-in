package ru.mycheckin.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mycheckin.passport.Level
import ru.mycheckin.passport.PassportData
import ru.mycheckin.passport.Validation
import ru.mycheckin.passport.mts.MtsApiException
import ru.mycheckin.passport.mts.RateLimiter
import ru.mycheckin.passport.mts.Template
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.concurrent.Executors

/**
 * Форма с распознанными данными: всё можно поправить вручную, затем отправить
 * в МТС Бизнес API (внесение персональных данных пользователя номера).
 */
class FormActivity : AppCompatActivity() {

    companion object {
        private val RU = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    }

    private lateinit var ui: Ui
    private lateinit var original: PassportData
    private lateinit var bannerBox: LinearLayout
    private lateinit var sexGroup: MaterialButtonToggleGroup
    private lateinit var sendButton: MaterialButton
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var fPhone: Ui.Field
    private lateinit var fSurname: Ui.Field
    private lateinit var fName: Ui.Field
    private lateinit var fPatronymic: Ui.Field
    private lateinit var fBirthDate: Ui.Field
    private lateinit var fBirthPlace: Ui.Field
    private lateinit var fSeries: Ui.Field
    private lateinit var fNumber: Ui.Field
    private lateinit var fIssueDate: Ui.Field
    private lateinit var fDivisionCode: Ui.Field
    private lateinit var fIssuedBy: Ui.Field

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        original = ScanSession.passport ?: PassportData().apply { source = "manual" }
        val root = ui.column()

        root.addView(ui.text("Данные паспорта", 24f, bold = true))
        bannerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bannerBox)
        showRecognitionBanner()

        ScanSession.photo?.let { bmp ->
            root.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = ui.px(220)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(10) }
                contentDescription = "Фото владельца"
            })
        }

        root.addView(ui.section("Номер МТС"))
        fPhone = ui.field("Номер телефона пользователя", Ui.Kind.PHONE, "Номер, к которому привязываются данные").also { root.addView(it.layout) }

        root.addView(ui.section("Владелец"))
        fSurname = ui.field("Фамилия", Ui.Kind.NAME).also { root.addView(it.layout) }
        fName = ui.field("Имя", Ui.Kind.NAME).also { root.addView(it.layout) }
        fPatronymic = ui.field("Отчество", Ui.Kind.NAME, "Если нет — оставьте пустым").also { root.addView(it.layout) }
        sexGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Мужской"; tag = 'M' })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Женский"; tag = 'F' })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) }
        }
        root.addView(sexGroup)
        fBirthDate = ui.field("Дата рождения", Ui.Kind.DATE, "ДД.ММ.ГГГГ").also { root.addView(it.layout) }
        fBirthPlace = ui.field("Место рождения", Ui.Kind.MULTILINE).also { root.addView(it.layout) }

        root.addView(ui.section("Паспорт"))
        fSeries = ui.field("Серия", Ui.Kind.DIGITS, "4 цифры").also { root.addView(it.layout) }
        fNumber = ui.field("Номер", Ui.Kind.DIGITS, "6 цифр").also { root.addView(it.layout) }
        fIssueDate = ui.field("Дата выдачи", Ui.Kind.DATE, "ДД.ММ.ГГГГ").also { root.addView(it.layout) }
        fDivisionCode = ui.field("Код подразделения", Ui.Kind.DATE, "000-000").also { root.addView(it.layout) }
        fIssuedBy = ui.field("Кем выдан", Ui.Kind.MULTILINE).also { root.addView(it.layout) }

        fill(original)

        sendButton = ui.button("Отправить в МТС", filled = true) { submit() }
        root.addView(sendButton.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(24) })
        root.addView(ui.button("Копировать") { copy() })
        root.addView(ui.button("Поделиться") { share() })
        root.addView(ui.button("Настройки МТС API") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(ui.button("Сканировать следующий") { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(MaterialColors.getColor(this@FormActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
            addView(root)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun fill(p: PassportData) {
        fSurname.value = p.surname.orEmpty()
        fName.value = p.name.orEmpty()
        fPatronymic.value = p.patronymic.orEmpty()
        when (p.sex) {
            'M' -> sexGroup.check(sexGroup.getChildAt(0).id)
            'F' -> sexGroup.check(sexGroup.getChildAt(1).id)
        }
        fBirthDate.value = p.birthDate?.format(RU).orEmpty()
        fBirthPlace.value = p.birthPlace.orEmpty()
        fSeries.value = p.series.orEmpty()
        fNumber.value = p.number.orEmpty()
        fIssueDate.value = p.issueDate?.format(RU).orEmpty()
        fDivisionCode.value = p.divisionCode.orEmpty()
        fIssuedBy.value = p.issuedBy.orEmpty()
    }

    /** Как распознано: контрольные суммы, источник, замечания. */
    private fun showRecognitionBanner() {
        bannerBox.removeAllViews()
        val p = original
        val notes = p.issues.filter { it.level != Level.INFO }.map { (if (it.level == Level.ERROR) "⛔ " else "⚠️ ") + it.message }
        val (color, title) = when {
            p.source == "manual" -> R.color.warn to "Ручной ввод"
            p.issues.any { it.level == Level.ERROR } -> R.color.bad to "Проверьте данные"
            notes.isNotEmpty() -> R.color.warn to "Распознано, есть замечания"
            else -> R.color.ok to "Распознано, контрольные суммы сошлись"
        }
        val details = buildList {
            if (p.checks.isNotEmpty()) add("Контрольные суммы MRZ: ${p.checks.values.count { it }} из 4")
            if (p.source == "mrz+viz" || p.source == "viz") add("«Кем выдан» и место рождения прочитаны с фото — сверьте с паспортом")
            addAll(notes)
        }
        bannerBox.addView(ui.banner(color, title, details))
    }

    private fun parseDate(f: Ui.Field): LocalDate? = try {
        LocalDate.parse(f.value, RU)
    } catch (e: DateTimeParseException) {
        null
    }

    /** Собрать данные из формы и проверить. Возвращает null, если есть ошибки в полях. */
    private fun collect(): PassportData? {
        val all = listOf(fPhone, fSurname, fName, fPatronymic, fBirthDate, fBirthPlace, fSeries, fNumber, fIssueDate, fDivisionCode, fIssuedBy)
        all.forEach { it.error = null }
        fun required(f: Ui.Field) { if (f.value.isEmpty()) f.error = "Обязательное поле" }
        listOf(fPhone, fSurname, fName, fBirthDate, fBirthPlace, fSeries, fNumber, fIssueDate, fDivisionCode, fIssuedBy).forEach(::required)

        val digits = fPhone.value.filter(Char::isDigit)
        if (fPhone.value.isNotEmpty() && !(digits.length == 10 || (digits.length == 11 && digits[0] in "78"))) fPhone.error = "Формат: +7 900 000-00-00"
        val bd = parseDate(fBirthDate)
        if (fBirthDate.value.isNotEmpty() && bd == null) fBirthDate.error = "Формат: ДД.ММ.ГГГГ"
        val id = parseDate(fIssueDate)
        if (fIssueDate.value.isNotEmpty() && id == null) fIssueDate.error = "Формат: ДД.ММ.ГГГГ"
        if (fSeries.value.isNotEmpty() && !Regex("\\d{4}").matches(fSeries.value)) fSeries.error = "4 цифры"
        if (fNumber.value.isNotEmpty() && !Regex("\\d{6}").matches(fNumber.value)) fNumber.error = "6 цифр"
        val code = fDivisionCode.value.replace(Regex("\\s"), "").let { if (Regex("\\d{6}").matches(it)) "${it.take(3)}-${it.drop(3)}" else it }
        if (code.isNotEmpty() && !Regex("\\d{3}-\\d{3}").matches(code)) fDivisionCode.error = "Формат: 000-000"
        val sex = sexGroup.checkedButtonId.takeIf { it != View.NO_ID }?.let { findViewById<MaterialButton>(it).tag as Char }
        if (sex == null) Toast.makeText(this, "Укажите пол", Toast.LENGTH_SHORT).show()

        if (all.any { it.error != null } || sex == null) return null

        val p = PassportData().apply {
            this.surname = fSurname.value.trim()
            this.name = fName.value.trim()
            this.patronymic = fPatronymic.value.trim().ifEmpty { null }
            this.sex = sex
            this.birthDate = bd
            this.birthPlace = fBirthPlace.value.replace(Regex("\\s+"), " ").trim()
            this.series = fSeries.value
            this.number = fNumber.value
            this.issueDate = id
            this.divisionCode = code
            this.issuedBy = fIssuedBy.value.replace(Regex("\\s+"), " ").trim()
            this.citizenship = "RUS"
            this.source = original.source
            this.mrzLines = original.mrzLines
        }
        fDivisionCode.value = code
        return Validation.validate(p)
    }

    private fun submit() {
        val p = collect() ?: return
        val config = MtsService.load(this)
        if (!MtsService.isConfigured(config)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("МТС API не настроен")
                .setMessage("Укажите Consumer Key, Consumer Secret и адрес метода внесения персональных данных.")
                .setPositiveButton("Открыть настройки") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val errors = p.issues.filter { it.level == Level.ERROR }
        if (errors.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Есть ошибки в данных")
                .setMessage(errors.joinToString("\n") { "• " + it.message } + "\n\nВсё равно отправить?")
                .setPositiveButton("Отправить") { _, _ -> send(p) }
                .setNegativeButton("Исправить", null)
                .show()
        } else {
            send(p)
        }
    }

    private fun send(p: PassportData) {
        sendButton.isEnabled = false
        sendButton.text = "Отправка…"
        val values = Template.values(p, fPhone.value)
        executor.execute {
            val message: Pair<String, String> = try {
                val r = MtsService.client(this).submit(values)
                val body = r.body.take(1500)
                if (r.code in 200..299) "Данные отправлены" to "HTTP ${r.code}\n\n$body"
                else "Ошибка HTTP ${r.code}" to body.ifEmpty { "Пустой ответ" }
            } catch (e: RateLimiter.MonthlyLimitExceeded) {
                "Лимит тарифа" to (e.message ?: "")
            } catch (e: MtsApiException) {
                "Ошибка МТС API" to listOfNotNull(e.message, e.response?.body?.take(1500)).joinToString("\n\n")
            } catch (e: Exception) {
                "Не удалось отправить" to (e.message ?: e.javaClass.simpleName)
            }
            val limiter = MtsService.limiter(this)
            runOnUiThread {
                sendButton.isEnabled = true
                sendButton.text = "Отправить в МТС"
                MaterialAlertDialogBuilder(this)
                    .setTitle(message.first)
                    .setMessage(message.second + "\n\nВызовов API в этом месяце: ${limiter.usedThisMonth()} из ${limiter.monthlyLimit}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun summary(p: PassportData): String = buildString {
        appendLine(p.fullName)
        p.sex?.let { appendLine("Пол: " + if (it == 'M') "мужской" else "женский") }
        p.birthDate?.let { appendLine("Дата рождения: ${it.format(RU)}") }
        p.birthPlace?.let { appendLine("Место рождения: $it") }
        p.seriesNumber?.let { appendLine("Паспорт: $it") }
        p.issueDate?.let { appendLine("Выдан: ${it.format(RU)}, код подразделения ${p.divisionCode ?: "—"}") }
        p.issuedBy?.let { appendLine("Кем выдан: $it") }
    }.trim()

    private fun copy() {
        val p = collect() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("passport", summary(p)))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val p = collect() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, summary(p) + "\n\n" + p.toJson())
        }
        startActivity(Intent.createChooser(send, "Поделиться"))
    }
}
