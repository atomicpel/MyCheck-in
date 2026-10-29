package ru.mycheckin.scanner

import android.app.Activity
import android.text.InputFilter
import android.util.Patterns
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate

/** Диалоги экранов «Номера МТС»: ввод текста, подтверждение, заказ документов на email. */
object LineDialogs {

    fun confirm(a: Activity, title: String, message: String, ok: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(a).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("Отмена", null).show()
    }

    fun message(a: Activity, title: String, text: String) {
        MaterialAlertDialogBuilder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    /** Текст с ограничением длины; пустой ввод разрешён, если [allowEmpty] (например, удалить комментарий). */
    fun text(a: Activity, title: String, hint: String, initial: String, maxLength: Int, allowEmpty: Boolean, ok: String = "Сохранить", onOk: (String) -> Unit) {
        val ui = Ui(a)
        val f = ui.field(hint, Ui.Kind.TEXT, helper = "До $maxLength знаков" + if (allowEmpty) " · пусто — удалить" else "")
        f.edit.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        f.value = initial
        f.layout.isCounterEnabled = true
        f.layout.counterMaxLength = maxLength
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(24), ui.px(8), ui.px(24), 0); addView(f.layout) }
        val d = MaterialAlertDialogBuilder(a).setTitle(title).setView(box)
            .setPositiveButton(ok, null).setNegativeButton("Отмена", null).create()
        d.setOnShowListener {
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val v = f.value
                if (v.isEmpty() && !allowEmpty) { f.error = "Введите значение"; return@setOnClickListener }
                d.dismiss(); onOk(v)
            }
        }
        d.show()
    }

    enum class Period(val title: String) { CURRENT_MONTH("Текущий месяц"), LAST_MONTH("Прошлый месяц"), LAST_7("Последние 7 дней"), LAST_30("Последние 30 дней") }

    fun range(p: Period, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (p) {
        Period.CURRENT_MONTH -> today.withDayOfMonth(1) to today
        Period.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        Period.LAST_7 -> today.minusDays(6) to today
        Period.LAST_30 -> today.minusDays(29) to today
    }

    /**
     * Документ на email: адрес (запоминается), период (если [withPeriod]) и формат из [formats].
     * [onOk] получает email, период и формат.
     */
    fun document(
        a: Activity, title: String, withPeriod: Boolean, formats: List<String>,
        onOk: (email: String, from: LocalDate, to: LocalDate, format: String) -> Unit,
    ) {
        val ui = Ui(a)
        val v = LineViews(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(24), ui.px(4), ui.px(24), 0) }
        val email = ui.field("Email для документа", Ui.Kind.URL)
        email.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        email.value = LinePrefs.email(a)
        col.addView(email.layout)

        var period = Period.LAST_MONTH
        if (withPeriod) {
            col.addView(v.label("Период", 12))
            val g = v.chipGroup(single = true, required = true)
            Period.entries.forEach { p -> g.addView(v.chip(p.title, checked = p == period).apply { setOnClickListener { period = p } }) }
            col.addView(g)
        }
        var format = formats.first()
        if (formats.size > 1) {
            col.addView(v.label("Формат", 12))
            val g = v.chipGroup(single = true, required = true)
            formats.forEach { f -> g.addView(v.chip(f, checked = f == format).apply { setOnClickListener { format = f } }) }
            col.addView(g)
        }
        col.addView(v.hint("МТС сформирует документ и пришлёт его на этот адрес. Номер заявки появится в карточке номера."))
        val d = MaterialAlertDialogBuilder(a).setTitle(title).setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Заказать", null).setNegativeButton("Отмена", null).create()
        d.setOnShowListener {
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val e = email.value
                if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { email.error = "Проверьте адрес"; return@setOnClickListener }
                LinePrefs.setEmail(a, e)
                val (from, to) = range(period)
                d.dismiss()
                onOk(e, from, to, format)
            }
        }
        d.show()
    }
}
