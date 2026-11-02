package ru.mycheckin.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import ru.mycheckin.passport.mts.LineCategory
import ru.mycheckin.passport.mts.LinePlanner
import ru.mycheckin.passport.mts.LineSchedule
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.LineSection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки загрузки сведений: первичная загрузка, период обновления каждой категории,
 * сеть и темп. Фон всегда оставляет часть лимита экранам приложения.
 */
object LineSettingsSheet {

    fun show(a: Activity, onSaved: () -> Unit) {
        val ui = Ui(a)
        val v = LineViews(a)
        val dialog = BottomSheetDialog(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(20), v.px(8), v.px(20), v.px(16)) }

        var schedule = LinePrefs.schedule(a)
        var wifi = LinePrefs.wifiOnly(a)
        var perMinute = LinePrefs.perMinute(a)
        var initialPerMinute = LinePrefs.initialPerMinute(a)

        val sims = SimDb.get(a).all().filter { it.inMts && it.msisdn.length == 11 }
        val accounts = sims.map { it.accountNo }.filter { it.isNotEmpty() }.distinct().size
        val estimate = v.hint("")
        val runEvery = v.hint("")
        fun update() {
            val sections = schedule.sections
            val cost = LinePlanner.fullCost(sims.size, accounts, sections)
            estimate.text = "Полная загрузка ${sims.size} номеров: около $cost вызовов, ≈ ${cost / initialPerMinute + 1} мин " +
                "в первичной загрузке. Дальше запрашивается только то, что устарело по своему периоду."
            val every = schedule.runEveryMinutes
            runEvery.text = if (every == 0) "Фоновое обновление выключено: все категории — «Выкл.»"
            else "Фон запускается раз в ${LineCategory.periodTitle(every).lowercase().removePrefix("раз в ")} — по самой частой категории " +
                "(не чаще 15 мин). Каждый раз запрашивается только устаревшее."
        }

        col.addView(ui.text("Загрузка сведений МТС", 20f, bold = true).apply { setPadding(0, v.px(4), 0, v.px(4)) })

        // ---- Первичная загрузка ----
        col.addView(v.label("Первичная загрузка"))
        val st = LinePrefs.initial(a)
        col.addView(v.hint(when (st) {
            LinePrefs.Initial.NONE -> "Ещё не выполнялась. Загрузит всё по всем номерам, затем включится обычное обновление."
            LinePrefs.Initial.RUNNING -> (if (LinePrefs.initialPaused(a)) "На паузе. " else "Идёт. ") + LinePrefs.initialMessage(a)
            LinePrefs.Initial.DONE -> "Завершена ${SimFormat.time(LinePrefs.initialFinished(a))}. ${LinePrefs.initialMessage(a)}"
            LinePrefs.Initial.FAILED -> "Остановлена: ${LinePrefs.initialMessage(a)}"
        }))
        col.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = when (st) {
                LinePrefs.Initial.NONE -> "Начать первичную загрузку"
                LinePrefs.Initial.RUNNING -> "Продолжить сейчас"
                else -> "Загрузить недостающее заново"
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = v.px(6) }
            setOnClickListener { LineSync.startInitial(a, reset = st != LinePrefs.Initial.RUNNING); dialog.dismiss(); onSaved() }
        })

        // ---- Периоды по категориям ----
        col.addView(v.label("Как часто обновлять"))
        col.addView(v.hint("Свой период для каждой категории сведений. «Выкл.» — не загружать в фоне (в карточке номера по кнопке «Обновить» загрузится всё)."))
        for (c in LineCategory.entries) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, v.px(8), 0, v.px(4))
            }
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ui.text(c.title, 15f))
            texts.addView(v.hint(c.hint))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val btn = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = LineCategory.periodTitle(schedule.of(c))
                minWidth = v.px(112)
            }
            btn.setOnClickListener {
                val options = LineCategory.PERIODS
                val current = options.indexOf(schedule.of(c)).coerceAtLeast(0)
                MaterialAlertDialogBuilder(a).setTitle(c.title)
                    .setSingleChoiceItems(options.map { LineCategory.periodTitle(it) }.toTypedArray(), current) { d, i ->
                        schedule = schedule.with(c, options[i])
                        btn.text = LineCategory.periodTitle(options[i])
                        update(); d.dismiss()
                    }.setNegativeButton("Отмена", null).show()
            }
            row.addView(btn)
            col.addView(row)
        }
        col.addView(runEvery)
        col.addView(MaterialButton(a, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Вернуть периоды по умолчанию"
            setOnClickListener { dialog.dismiss(); LinePrefs.save(a, LineSchedule(), wifi, perMinute, initialPerMinute); LineSync.schedule(a); show(a, onSaved) }
        })

        // ---- Сеть и темп ----
        col.addView(v.label("Сеть"))
        col.addView(MaterialSwitch(a).apply {
            text = "Обновлять в фоне только по Wi-Fi"
            isChecked = wifi
            setOnCheckedChangeListener { _, on -> wifi = on }
        })
        col.addView(v.hint("Первичная загрузка идёт через любую сеть: объём небольшой (килобайты на номер)."))

        val tempo = v.label("Темп обновления: $perMinute вызовов в минуту")
        col.addView(tempo)
        col.addView(Slider(a).apply {
            valueFrom = 30f; valueTo = 250f; stepSize = 10f
            value = ((perMinute / 10) * 10).toFloat().coerceIn(30f, 250f)
            addOnChangeListener { _, x, _ -> perMinute = x.toInt(); tempo.text = "Темп обновления: $perMinute вызовов в минуту" }
        })
        val tempoInitial = v.label("Темп первичной загрузки: $initialPerMinute вызовов в минуту")
        col.addView(tempoInitial)
        col.addView(Slider(a).apply {
            valueFrom = 30f; valueTo = 260f; stepSize = 10f
            value = ((initialPerMinute / 10) * 10).toFloat().coerceIn(30f, 260f)
            addOnChangeListener { _, x, _ -> initialPerMinute = x.toInt(); tempoInitial.text = "Темп первичной загрузки: $initialPerMinute вызовов в минуту"; update() }
        })
        col.addView(v.hint("Лимит тарифа — 300 в минуту на доступ, приложение держит не больше 280. Разница остаётся экранам: работа в приложении не ждёт фон."))
        col.addView(v.label("Оценка"))
        col.addView(estimate)

        // ---- Расход и последнее обновление ----
        val usage = MtsService.accounts(a).filter { it.isConfigured }.joinToString("\n") { acc ->
            val l = MtsService.limiter(a, acc.id)
            "${acc.title.ifBlank { "Доступ" }}: ${LineFormat.number(l.usedThisMonth().toDouble())} из ${LineFormat.number(l.monthlyLimit.toDouble())} вызовов за месяц"
        }
        if (usage.isNotBlank()) { col.addView(v.label("Расход лимита")); col.addView(v.hint(usage)) }
        val last = LinePrefs.lastRun(a)
        if (last > 0) { col.addView(v.label("Последнее обновление")); col.addView(v.hint("${SimFormat.time(last)}\n${LinePrefs.lastRunText(a)}")) }

        val save = MaterialButton(a).apply {
            text = "Сохранить"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(v.px(16), v.px(8), v.px(16), v.px(12)) }
            setOnClickListener {
                LinePrefs.save(a, schedule, wifi, perMinute, initialPerMinute)
                Journal.event(a, LogLevel.INFO, LogCategory.SETTINGS, "Загрузка сведений МТС: настройки",
                    LineCategory.entries.joinToString("\n") { "${it.title}: ${LineCategory.periodTitle(schedule.of(it))}" } +
                        "\nWi-Fi: ${if (wifi) "только" else "любая сеть"}, темп: $perMinute/мин, первичная загрузка: $initialPerMinute/мин")
                LineSync.schedule(a)
                dialog.dismiss()
                onSaved()
            }
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(NestedScrollView(a).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(save)
        }
        dialog.setContentView(root, ViewGroup.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.9).toInt()))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        update()
        dialog.show()
    }
}

/** Выгрузка сведений о номерах в CSV для Excel. */
object LineExport {
    fun csv(context: Context, rows: List<LineRow>): Intent {
        val header = listOf(
            "Телефон", "Владелец", "Контракт", "Лицевой счёт", "ICCID", "Баланс", "Порог блокировки", "Неоплачено", "Начислено за месяц",
            "Тариф", "Абонплата в месяц", "Блокировки", "Роуминг", "Переадресация", "Тип SIM", "Пакеты", "Услуги", "Комментарий", "Обновлено",
        )
        fun q(v: String) = "\"" + (if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v).replace("\"", "\"\"") + "\""
        fun n(d: Double?) = d?.let { String.format(Locale("ru"), "%.2f", it) }.orEmpty()
        val data = rows.map { r ->
            val s = r.snap
            listOf(
                r.msisdn, r.owner, r.contractTitle, r.accountNo, r.iccid, n(s.balance?.amount), n(s.blockThreshold), n(s.unpaid), n(s.charges),
                s.tariff?.name.orEmpty(), n(s.monthlyFee), s.blocks.orEmpty().joinToString(", ") { it.name },
                if (s.abroad) s.location?.countryName ?: "за границей" else if (s.location != null) "нет" else "",
                s.forwarding.orEmpty().joinToString(", ") { "${it.title} → ${it.address}" }, s.simType.orEmpty(),
                s.packages.joinToString(", ") { "${LineFormat.shortKind(it.kind)}: ${LineFormat.counter(it)}" },
                s.services.orEmpty().joinToString(", ") { it.name + (it.feeText?.let { f -> " ($f)" } ?: "") },
                s.comment.orEmpty(), s.updatedAt?.let { SimFormat.time(it) }.orEmpty(),
            )
        }
        val dir = File(context.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "Номера-МТС-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        file.writeText("﻿" + (listOf(header) + data).joinToString("\r\n") { r -> r.joinToString(";") { q(it) } }, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Выгрузить номера МТС")
    }
}
