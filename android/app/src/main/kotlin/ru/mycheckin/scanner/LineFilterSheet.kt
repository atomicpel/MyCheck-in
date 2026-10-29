package ru.mycheckin.scanner

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import ru.mycheckin.passport.mts.BalanceRule
import ru.mycheckin.passport.mts.BlockRule
import ru.mycheckin.passport.mts.LineApi
import ru.mycheckin.passport.mts.LineFilter
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.LineSort
import ru.mycheckin.passport.mts.Tri

/**
 * Настраиваемый фильтр: все условия сразу, внизу — сколько номеров подойдёт.
 * Набор условий можно сохранить под своим названием — он появится кнопкой над списком.
 */
object LineFilterSheet {

    fun show(a: Activity, initial: LineFilter, rows: List<LineRow>, onApply: (LineFilter) -> Unit, onSave: (LineFilter) -> Unit) {
        val ui = Ui(a)
        val v = LineViews(a)
        var f = initial
        val dialog = BottomSheetDialog(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(20), v.px(8), v.px(20), v.px(16)) }
        val showBtn = MaterialButton(a).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

        fun count() {
            val n = rows.count { f.matches(it, System.currentTimeMillis()) }
            showBtn.text = "Показать $n"
        }

        fun singleChips(title: String, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): ChipGroup {
            col.addView(v.label(title))
            val g = v.chipGroup(single = true, required = true)
            items.forEachIndexed { i, (t, checked) ->
                g.addView(v.chip(t, checked = checked).apply { setOnClickListener { onPick(i); count() } })
            }
            col.addView(g)
            return g
        }

        fun multiChips(title: String, items: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
            if (items.isEmpty()) return
            col.addView(v.label(title))
            val g = v.chipGroup(single = false)
            val current = selected.toMutableSet()
            items.forEach { (key, t) ->
                g.addView(v.chip(t, checked = key in current).apply {
                    setOnCheckedChangeListener { _, on -> if (on) current += key else current -= key; onChange(current.toSet()); count() }
                })
            }
            col.addView(g)
        }

        fun triRow(title: String, value: Tri, yes: String = "Да", no: String = "Нет", onChange: (Tri) -> Unit) {
            col.addView(v.label(title))
            val g = v.chipGroup(single = true, required = true)
            v.tri(g, value, yes, no) { onChange(it); count() }
            col.addView(g)
        }

        fun numberField(hint: String, value: Double?, onChange: (Double?) -> Unit): Ui.Field {
            val field = ui.field(hint, Ui.Kind.TEXT)
            field.edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            field.value = value?.let { LineFormat.number(it).replace(" ", "") }.orEmpty()
            field.edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString()?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()); count()
                }
            })
            col.addView(field.layout)
            return field
        }

        col.addView(ui.text("Фильтры", 20f, bold = true).apply { setPadding(0, v.px(4), 0, v.px(4)) })
        col.addView(v.hint("Условия складываются: показываются номера, подходящие под все сразу."))

        singleChips("Сортировка", LineSort.entries.map { it.title to (it == f.sort) }) { f = f.copy(sort = LineSort.entries[it]) }

        // Контракты и лицевые счета — из того, что есть в списке.
        multiChips("Контракты", rows.filter { it.contractId.isNotEmpty() }.associate { it.contractId to it.contractTitle.ifBlank { it.contractId } }
            .toList().sortedBy { it.second }, f.contracts) { f = f.copy(contracts = it) }
        val accounts = rows.map { it.accountNo }.filter { it.isNotEmpty() }.distinct().sorted()
        if (accounts.size in 2..40) multiChips("Лицевые счета", accounts.map { it to it }, f.accounts) { f = f.copy(accounts = it) }

        // Баланс
        lateinit var below: Ui.Field
        lateinit var margin: Ui.Field
        fun balanceFields() {
            below.layout.visibility = if (f.balance == BalanceRule.BELOW) View.VISIBLE else View.GONE
            margin.layout.visibility = if (f.balance == BalanceRule.LOW) View.VISIBLE else View.GONE
        }
        singleChips("Баланс лицевого счёта", BalanceRule.entries.map { it.title to (it == f.balance) }) {
            f = f.copy(balance = BalanceRule.entries[it]); balanceFields()
        }
        below = numberField("Меньше, ₽", f.balanceBelow) { f = f.copy(balanceBelow = it) }
        margin = numberField("Запас до порога блокировки меньше, ₽", f.lowMargin) { f = f.copy(lowMargin = it ?: 300.0) }
        balanceFields()

        // Блокировки
        singleChips("Блокировки", BlockRule.entries.map { it.title to (it == f.blocks) }) { f = f.copy(blocks = BlockRule.entries[it]) }
        multiChips("Вид блокировки", LineApi.BLOCK_NAMES.toList(), f.blockCodes) { f = f.copy(blockCodes = it) }

        // Услуги и тариф
        col.addView(v.label("Услуга"))
        val service = ui.field("Название или код услуги", Ui.Kind.TEXT, helper = "Например: «интернет», «БИТ», PE0526")
        service.value = f.serviceText
        service.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { f = f.copy(serviceText = s?.toString().orEmpty()); count() }
        })
        col.addView(service.layout)
        val modeGroup = v.chipGroup(single = true, required = true)
        listOf(Tri.YES to "Подключена", Tri.NO to "Не подключена").forEach { (t, title) ->
            modeGroup.addView(v.chip(title, checked = f.serviceMode == t).apply { setOnClickListener { f = f.copy(serviceMode = t); count() } })
        }
        col.addView(modeGroup)

        val tariffs = rows.mapNotNull { it.snap.tariff?.name }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        multiChips("Тариф", tariffs.map { (name, n) -> name to "$name · $n" }, f.tariffs) { f = f.copy(tariffs = it) }

        // Переключатели
        triRow("Роуминг", f.roaming, "За границей", "В России") { f = f.copy(roaming = it) }
        triRow("Переадресация", f.forwarding, "Есть", "Нет") { f = f.copy(forwarding = it) }
        triRow("Владелец в учёте", f.owner, "С ФИО", "Без ФИО") { f = f.copy(owner = it) }
        triRow("Тип SIM", f.esim, "eSIM", "Пластиковая") { f = f.copy(esim = it) }
        triRow("Комментарий в МТС", f.withComment, "Есть", "Нет") { f = f.copy(withComment = it) }
        triRow("Номер в МТС", f.inMts, "Есть", "Пропал из МТС") { f = f.copy(inMts = it) }

        // Суммы и пакеты
        col.addView(v.label("Суммы"))
        numberField("Начислено за месяц от, ₽", f.chargesAbove) { f = f.copy(chargesAbove = it) }
        numberField("Абонплата в месяц от, ₽", f.feeAbove) { f = f.copy(feeAbove = it) }

        val shares = listOf(null, 10, 20, 30, 50)
        singleChips("Остаток пакета меньше", shares.map { (it?.let { p -> "$p %" } ?: "Не важно") to (it == f.packageBelowPercent) }) {
            f = f.copy(packageBelowPercent = shares[it])
        }

        // Свежесть
        val ages = listOf(null, 1, 6, 24, 72)
        singleChips("Сведения не обновлялись", ages.map { (it?.let { h -> if (h < 24) "$h ч" else "${h / 24} дн." } ?: "Не важно") to (it == f.staleHours) }) {
            f = f.copy(staleHours = ages[it])
        }
        val errors = MaterialSwitch(a).apply {
            text = "Только с ошибками загрузки"
            isChecked = f.withErrors
            setOnCheckedChangeListener { _, on -> f = f.copy(withErrors = on); count() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = v.px(10) }
        }
        col.addView(errors)

        // Кнопки
        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(v.px(16), v.px(8), v.px(16), v.px(12))
            setBackgroundColor(v.attr(com.google.android.material.R.attr.colorSurface))
        }
        val reset = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Сбросить"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = v.px(8) }
            setOnClickListener { dialog.dismiss(); onApply(LineFilter(sort = f.sort)) }
        }
        val save = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Сохранить"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = v.px(8) }
            setOnClickListener {
                if (f.activeCount == 0) { text = "Нет условий"; return@setOnClickListener }
                LineDialogs.text(a, "Название набора", "Например: «Склад — без денег»", f.name, 40, allowEmpty = false) { name ->
                    dialog.dismiss(); onSave(f.copy(name = name, query = ""))
                }
            }
        }
        showBtn.setOnClickListener { dialog.dismiss(); onApply(f) }
        buttons.addView(reset); buttons.addView(save); buttons.addView(showBtn)

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(NestedScrollView(a).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buttons)
        }
        // Высота задана явно: прокручиваются условия, а кнопки всегда видны внизу.
        dialog.setContentView(root, android.view.ViewGroup.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.88).toInt()))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        count()
        dialog.show()
    }
}
