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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import ru.mycheckin.passport.mts.LinePlanner
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.LineScope
import ru.mycheckin.passport.mts.LineSection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки фонового обновления: как часто, через какую сеть, с каким темпом и какие сведения. */
object LineSettingsSheet {

    fun show(a: Activity, onSaved: () -> Unit) {
        val ui = Ui(a)
        val v = LineViews(a)
        val dialog = BottomSheetDialog(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(20), v.px(8), v.px(20), v.px(16)) }

        var interval = LinePrefs.intervalHours(a)
        var wifi = LinePrefs.wifiOnly(a)
        var perMinute = LinePrefs.perMinute(a)
        var ttl = LinePrefs.ttlFactor(a)
        var autoNumbers = LinePrefs.autoNumbers(a)
        val sections = LinePrefs.sections(a).toMutableSet()

        val estimate = v.hint("")
        val sims = SimDb.get(a).all().filter { it.inMts && it.msisdn.length == 11 }
        val accounts = sims.map { it.accountNo }.filter { it.isNotEmpty() }.distinct().size
        fun updateEstimate() {
            val cost = LinePlanner.fullCost(sims.size, accounts, sections)
            val minutes = cost / perMinute + 1
            val perNumber = sections.count { it.scope == LineScope.NUMBER }
            estimate.text = "Полное обновление ${sims.size} номеров: около $cost вызовов ($perNumber на номер), ≈ $minutes мин в фоне. " +
                "Обычно запрашивается только устаревшее — намного меньше."
        }

        col.addView(ui.text("Фоновое обновление", 20f, bold = true).apply { setPadding(0, v.px(4), 0, v.px(4)) })
        col.addView(v.hint("Сведения обновляются сами, даже когда приложение закрыто. Экраны приложения при этом работают без задержек: " +
            "фон использует только часть лимита вызовов."))

        col.addView(v.label("Как часто"))
        val intervals = listOf(0 to "Вручную", 1 to "1 ч", 3 to "3 ч", 6 to "6 ч", 12 to "12 ч", 24 to "Сутки")
        val ig = v.chipGroup(single = true, required = true)
        intervals.forEach { (h, t) -> ig.addView(v.chip(t, checked = h == interval).apply { setOnClickListener { interval = h } }) }
        col.addView(ig)

        col.addView(MaterialSwitch(a).apply {
            text = "Только по Wi-Fi"
            isChecked = wifi
            setOnCheckedChangeListener { _, on -> wifi = on }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = v.px(8) }
        })
        col.addView(MaterialSwitch(a).apply {
            text = "Раз в сутки обновлять список номеров и SIM"
            isChecked = autoNumbers
            setOnCheckedChangeListener { _, on -> autoNumbers = on }
        })

        col.addView(v.label("Свежесть сведений"))
        val freshness = listOf(0.5 to "Чаще", 1.0 to "Обычно", 2.0 to "Экономно")
        val fg = v.chipGroup(single = true, required = true)
        freshness.forEach { (k, t) -> fg.addView(v.chip(t, checked = k == ttl).apply { setOnClickListener { ttl = k } }) }
        col.addView(fg)
        col.addView(v.hint("«Обычно»: баланс, блокировки и роуминг — раз в час, пакеты — раз в 3 ч, услуги — раз в 12 ч, тариф и бюджет — раз в сутки, тип SIM — раз в неделю."))

        val tempo = v.label("Темп фона: $perMinute вызовов в минуту")
        col.addView(tempo)
        col.addView(Slider(a).apply {
            valueFrom = 30f; valueTo = 250f; stepSize = 10f
            value = ((perMinute / 10) * 10).toFloat().coerceIn(30f, 250f)
            addOnChangeListener { _, x, _ -> perMinute = x.toInt(); tempo.text = "Темп фона: $perMinute вызовов в минуту"; updateEstimate() }
        })
        col.addView(v.hint("Лимит тарифа — 300 в минуту на доступ. Остаток всегда остаётся экранам приложения."))

        col.addView(v.label("Что обновлять"))
        LineSection.entries.forEach { s ->
            col.addView(MaterialCheckBox(a).apply {
                text = s.title + "  ·  " + when {
                    s.ttlMinutes < 60 * 24 -> "раз в ${s.ttlMinutes / 60} ч"
                    s.ttlMinutes < 60 * 24 * 7 -> "раз в сутки"
                    else -> "раз в неделю"
                }
                isChecked = s in sections
                setOnCheckedChangeListener { _, on -> if (on) sections += s else sections -= s; updateEstimate() }
            })
        }
        col.addView(estimate)

        // Расход лимита за месяц по доступам
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
                LinePrefs.save(a, interval, wifi, perMinute, ttl, autoNumbers, sections.ifEmpty { setOf(LineSection.BALANCE) })
                Journal.event(a, LogLevel.INFO, LogCategory.SETTINGS, "Фоновое обновление сведений",
                    "Интервал: ${if (interval == 0) "вручную" else "$interval ч"}, Wi-Fi: ${if (wifi) "только" else "любая сеть"}, " +
                        "темп: $perMinute/мин, разделов: ${sections.size}")
                dialog.dismiss()
                onSaved()
            }
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(NestedScrollView(a).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(save)
        }
        dialog.setContentView(root, ViewGroup.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.88).toInt()))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        updateEstimate()
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
