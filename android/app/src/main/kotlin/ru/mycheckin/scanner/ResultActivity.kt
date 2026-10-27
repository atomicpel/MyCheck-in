package ru.mycheckin.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import ru.mycheckin.passport.Level
import ru.mycheckin.passport.Mrz
import ru.mycheckin.passport.PassportData
import ru.mycheckin.passport.Validation
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LINE1 = "line1"
        const val EXTRA_LINE2 = "line2"
        private val RU = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    private lateinit var data: PassportData
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(intent.getStringExtra(EXTRA_LINE1).orEmpty(), intent.getStringExtra(EXTRA_LINE2).orEmpty())
    }

    private fun show(line1: String, line2: String) {
        data = Validation.validate(Mrz.parse(line1, line2))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(40), px(20), px(28))
        }
        root.addView(banner())
        field(root, "Фамилия", data.surname)
        field(root, "Имя", data.name)
        field(root, "Отчество", data.patronymic)
        field(root, "Пол", when (data.sex) { 'M' -> "мужской"; 'F' -> "женский"; else -> null })
        field(root, "Дата рождения", data.birthDate?.let {
            "${it.format(RU)} (${Validation.ageOn(it, LocalDate.now())} лет)"
        })
        field(root, "Серия и номер", data.seriesNumber)
        field(root, "Дата выдачи", data.issueDate?.format(RU))
        field(root, "Код подразделения", data.divisionCode)
        field(root, "Регион выдачи (по серии)", data.region)
        field(root, "Действителен до", when {
            data.status == null -> null
            data.validUntil == null -> "бессрочно"
            else -> data.validUntil!!.format(RU)
        })

        val problems = data.issues.filter { it.level != Level.INFO }
        if (problems.isNotEmpty()) {
            section(root, "Замечания")
            problems.forEach { issue ->
                root.addView(text((if (issue.level == Level.ERROR) "⛔ " else "⚠️ ") + issue.message, 15f).apply {
                    setPadding(0, px(4), 0, px(4))
                })
            }
        }
        if (data.corrections.isNotEmpty()) {
            section(root, "Исправлено автоматически")
            data.corrections.forEach { root.addView(text("• $it", 13f, secondary = true)) }
        }
        data.mrzLines?.let { (l1, l2) ->
            section(root, "Машиночитаемая запись")
            root.addView(text("$l1\n$l2", 11f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(24), 0, 0)
        }
        buttons.addView(button(R.string.btn_copy, filled = true) { copy() })
        buttons.addView(button(R.string.btn_share) { share() })
        buttons.addView(button(R.string.btn_fix) {
            val (l1, l2) = data.mrzLines ?: ("" to "")
            MrzDialog.show(this, l1, l2) { a, b -> show(a, b) }
        })
        buttons.addView(button(R.string.btn_again) { finish() })
        root.addView(buttons)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(MaterialColors.getColor(this@ResultActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
            addView(root)
        })
    }

    private fun banner(): View {
        val (color, title) = when {
            data.status == PassportData.Status.EXPIRED -> R.color.bad to "Паспорт недействителен"
            !data.isValid -> R.color.bad to "Данные не подтверждены"
            data.status == PassportData.Status.REPLACEMENT_PERIOD -> R.color.warn to "Паспорт подлежит замене"
            data.issues.any { it.level == Level.WARNING } -> R.color.warn to "Проверьте замечания"
            else -> R.color.ok to "Паспорт действителен"
        }
        val checks = data.checks.values.count { it }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(ContextCompat.getColor(this@ResultActivity, color))
            }
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = "Контрольные суммы: $checks из 4 · уверенность ${(data.confidence * 100).toInt()}%"
                textSize = 14f
                setTextColor(Color.argb(230, 255, 255, 255))
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) }
        }
    }

    private fun field(root: LinearLayout, label: String, value: String?) {
        root.addView(text(label, 13f, secondary = true).apply { setPadding(0, px(12), 0, 0) })
        root.addView(text(value ?: "—", 19f).apply { setTextIsSelectable(true) })
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(text(title, 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(22), 0, px(6))
        })
    }

    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        val attr = if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant
        else com.google.android.material.R.attr.colorOnSurface
        setTextColor(MaterialColors.getColor(this@ResultActivity, attr, Color.GRAY))
    }

    private fun button(textRes: Int, filled: Boolean = false, onClick: () -> Unit): MaterialButton {
        val style = if (filled) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle
        return MaterialButton(this, null, style).apply {
            setText(textRes)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) }
        }
    }

    private fun summary(): String = buildString {
        appendLine(data.fullName)
        data.sex?.let { appendLine("Пол: " + if (it == 'M') "мужской" else "женский") }
        data.birthDate?.let { appendLine("Дата рождения: ${it.format(RU)}") }
        data.seriesNumber?.let { appendLine("Паспорт: $it") }
        data.issueDate?.let { appendLine("Выдан: ${it.format(RU)}, код подразделения ${data.divisionCode ?: "—"}") }
    }.trim()

    private fun copy() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("passport", summary()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, summary() + "\n\n" + data.toJson())
        }
        startActivity(Intent.createChooser(send, getString(R.string.btn_share)))
    }

    private fun px(v: Int) = (v * dp).toInt()
}
