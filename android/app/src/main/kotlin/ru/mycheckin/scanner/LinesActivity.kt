package ru.mycheckin.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import ru.mycheckin.passport.mts.BalanceState
import ru.mycheckin.passport.mts.BlockRule
import ru.mycheckin.passport.mts.LineApi
import ru.mycheckin.passport.mts.LineFilter
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.Money
import ru.mycheckin.passport.mts.Tri
import java.util.concurrent.Executors

/**
 * «Номера МТС»: баланс, тариф, пакеты, блокировки, роуминг и всё остальное по каждому номеру компании.
 * Сведения обновляются в фоне ([LineSync]) и показываются из базы на телефоне — экран открывается сразу
 * и не ждёт МТС. Настраиваемые фильтры, сохранённые наборы, выбор номеров для действий сразу над многими.
 */
class LinesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var search: TextInputEditText
    private lateinit var presets: ChipGroup
    private lateinit var syncBar: View
    private lateinit var syncProgress: LinearProgressIndicator
    private lateinit var syncText: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var fab: ExtendedFloatingActionButton

    private val io = Executors.newSingleThreadExecutor()
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val v by lazy { LineViews(this) }

    private var rows: List<LineRow> = emptyList()
    private var shown: List<LineRow> = emptyList()
    private var filter = LineFilter()
    private var saved: List<LineFilter> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selecting = false
    private var wasRunning = false
    private var lastReload = 0L
    private var loading = false

    private val header = SummaryAdapter()
    private val adapter = LineAdapter()

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    private val syncListener: () -> Unit = { if (!isDestroyed) onSyncState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lines)
        toolbar = findViewById(R.id.toolbar)
        search = findViewById(R.id.search)
        presets = findViewById(R.id.presets)
        syncBar = findViewById(R.id.sync_bar)
        syncProgress = findViewById(R.id.sync_progress)
        syncText = findViewById(R.id.sync_text)
        refresh = findViewById(R.id.refresh)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fab)
        presets.isSingleSelection = false

        onBackPressedDispatcher.addCallback(this, back)
        toolbar.setNavigationOnClickListener { if (selecting) endSelection() else { finish(); closeScreenAnimation() } }
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        filter = LinePrefs.currentFilter(this)?.let { LineFilter.fromJson(it) } ?: LineFilter()
        saved = LineFilter.listFromJson(LinePrefs.filters(this))
        search.setText(filter.query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(applySearch)
                main.postDelayed(applySearch, 150)
            }
        })

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(header, adapter)
        list.itemAnimator?.apply { changeDuration = 160; moveDuration = 220 }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8 && fab.isExtended) fab.shrink() else if (dy < -8 && !fab.isExtended) fab.extend()
            }
        })

        refresh.setColorSchemeColors(MaterialColors.getColor(refresh, com.google.android.material.R.attr.colorPrimary))
        refresh.setOnRefreshListener { refresh.isRefreshing = false; refreshStale() }
        findViewById<MaterialButton>(R.id.sync_stop).setOnClickListener { LineSync.stop(this) }
        fab.setOnClickListener { openFilters() }

        renderPresets()
        askNotifications()
    }

    override fun onStart() {
        super.onStart()
        LineSync.addListener(syncListener)
        onSyncState()
    }

    override fun onStop() {
        super.onStop()
        LineSync.removeListener(syncListener)
    }

    override fun onResume() {
        super.onResume()
        reload()
        // Первый запуск: сведений ещё нет — сразу загружаем в фоне.
        if (LinePrefs.lastRun(this) == 0L && MtsService.hasAccounts(this) && !LineSync.state.running) LineSync.refreshNow(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        work.shutdown()
    }

    // ---- Данные ----

    private fun reload() {
        if (loading) return
        loading = true
        lastReload = System.currentTimeMillis()
        io.execute {
            val r = runCatching { LineStore.get(this).rows(this) }.getOrDefault(emptyList())
            runOnUiThread {
                loading = false
                if (isDestroyed) return@runOnUiThread
                rows = r
                selected.retainAll(r.map { it.msisdn }.toSet())
                apply()
            }
        }
    }

    private val applySearch = Runnable {
        filter = filter.copy(query = search.text?.toString().orEmpty())
        apply()
    }

    private fun setFilter(f: LineFilter) {
        filter = f.copy(query = search.text?.toString().orEmpty())
        apply()
        list.scrollToPosition(0)
    }

    private fun apply() {
        LinePrefs.setCurrentFilter(this, filter.toJson())
        shown = filter.apply(rows, System.currentTimeMillis())
        adapter.submitList(shown)
        header.update(Summary.of(shown))
        syncPresetChecks()
        val n = filter.activeCount
        fab.text = if (n > 0) "Фильтры · $n" else "Фильтры"
        updateTitle()
        renderEmpty()
    }

    private fun updateTitle() {
        if (selecting) {
            toolbar.title = "Выбрано ${selected.size}"
            toolbar.subtitle = "из ${shown.size} показанных"
            return
        }
        toolbar.title = "Номера МТС"
        val count = if (shown.size == rows.size) "${rows.size} ${LineFormat.plural(rows.size, "номер", "номера", "номеров")}"
        else "Показано ${shown.size} из ${rows.size}"
        val last = LinePrefs.lastRun(this)
        toolbar.subtitle = if (rows.isEmpty()) null else count + if (last > 0) " · обновлено ${LineFormat.ago(last)}" else ""
    }

    private fun renderEmpty() {
        val title = findViewById<TextView>(R.id.empty_title)
        val text = findViewById<TextView>(R.id.empty_text)
        val action = findViewById<MaterialButton>(R.id.empty_action)
        when {
            !MtsService.hasAccounts(this) -> {
                title.text = "Нужен доступ к МТС"
                text.text = "Введите логин и пароль для интеграции из личного кабинета МТС Бизнес (раздел «API для бизнеса»)."
                action.text = "Открыть настройки"
                action.setOnClickListener { openScreen(Intent(this, SettingsActivity::class.java)) }
            }
            rows.isEmpty() -> {
                title.text = "Номеров пока нет"
                text.text = "Загрузим список номеров и сведения о них из МТС Бизнес."
                action.text = "Загрузить из МТС"
                action.setOnClickListener { refreshStale() }
            }
            else -> {
                title.text = "Ничего не найдено"
                text.text = "Измените поиск или условия фильтра."
                action.text = "Сбросить фильтр"
                action.setOnClickListener { search.setText(""); setFilter(LineFilter(sort = filter.sort)) }
            }
        }
        empty.visibility = if (shown.isEmpty() && !loading) View.VISIBLE else View.GONE
    }

    // ---- Фоновое обновление ----

    private fun onSyncState() {
        val st = LineSync.state
        if (st.running) {
            syncBar.visibility = View.VISIBLE
            if (st.total > 0) {
                if (syncProgress.isIndeterminate) {
                    syncProgress.visibility = View.INVISIBLE
                    syncProgress.isIndeterminate = false
                    syncProgress.visibility = View.VISIBLE
                }
                syncProgress.max = st.total
                syncProgress.setProgressCompat(st.done, true)
                syncText.text = "${st.done} из ${st.total} · ${st.phase}"
            } else {
                if (!syncProgress.isIndeterminate) {
                    syncProgress.visibility = View.INVISIBLE
                    syncProgress.isIndeterminate = true
                    syncProgress.visibility = View.VISIBLE
                }
                syncText.text = st.phase
            }
            // Пока идёт обновление — перечитываем список не чаще раза в 4 секунды.
            if (System.currentTimeMillis() - lastReload > 4000) reload()
        } else {
            syncBar.visibility = View.GONE
            if (wasRunning) {
                reload()
                val text = LinePrefs.lastRunText(this)
                if (text.isNotBlank()) Snackbar.make(list, text.lineSequence().first(), Snackbar.LENGTH_LONG).setAnchorView(fab)
                    .setAction("Журнал") { openScreen(Intent(this, JournalActivity::class.java)) }.show()
            }
        }
        wasRunning = st.running
    }

    private fun refreshStale(numbers: Collection<String>? = null, force: Boolean = false) {
        if (!MtsService.hasAccounts(this)) { renderEmpty(); return }
        if (!LineSync.refreshNow(this, numbers, force)) {
            Snackbar.make(list, "Обновление уже идёт", Snackbar.LENGTH_SHORT).setAnchorView(fab).show()
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || LinePrefs.askedNotifications(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        LinePrefs.setAskedNotifications(this)
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- Меню ----

    private fun onMenu(id: Int) {
        when (id) {
            R.id.action_refresh -> refreshStale()
            R.id.action_refresh_all -> {
                val cost = LineSync.fullCost(this)
                val minutes = cost / LinePrefs.perMinute(this) + 1
                LineDialogs.confirm(this, "Обновить всё заново",
                    "Все сведения будут запрошены заново, даже свежие: около $cost вызовов API, примерно $minutes мин. " +
                        "Обновление идёт в фоне — можно работать дальше или закрыть приложение.", "Обновить") { refreshStale(force = true) }
            }
            R.id.action_settings -> LineSettingsSheet.show(this) { LineSync.schedule(this); updateTitle() }
            R.id.action_select -> startSelection(null)
            R.id.action_export -> export(shown)
            R.id.action_journal -> openScreen(Intent(this, JournalActivity::class.java))
            R.id.sel_all -> { selected.addAll(shown.map { it.msisdn }); adapter.notifyItemRangeChanged(0, adapter.itemCount); updateTitle() }
            R.id.sel_refresh -> withSelection { refreshStale(it, force = true); endSelection() }
            R.id.sel_comment -> withSelection { nums ->
                LineDialogs.text(this, "Комментарий на ${nums.size} ${LineFormat.plural(nums.size, "номере", "номерах", "номерах")}",
                    "Комментарий", "", LineApi.MAX_COMMENT, allowEmpty = true) { text ->
                    background("Сохраняю комментарий…") { LineActions.setComment(this, nums, text); "Комментарий сохранён в МТС" }
                }
            }
            R.id.sel_block_on -> withSelection { voluntaryBlock(it, true) }
            R.id.sel_block_off -> withSelection { voluntaryBlock(it, false) }
            R.id.sel_calls -> withSelection { nums ->
                LineDialogs.document(this, "Детализация на email", withPeriod = true, formats = listOf("PDF", "HTML", "XML")) { email, from, to, format ->
                    background("Заказываю детализацию…") {
                        val acks = LineActions.orderCalls(this, nums, from, to, format, email)
                        "Детализация заказана: ${acks.size} ${LineFormat.plural(acks.size, "заявка", "заявки", "заявок")}, придёт на $email"
                    }
                }
            }
            R.id.sel_balance_report -> withSelection { nums ->
                LineDialogs.document(this, "Отчёт по балансу на email", withPeriod = false, formats = listOf("PDF")) { email, _, _, _ ->
                    background("Заказываю отчёт…") { LineActions.orderBalanceReport(this, nums, email); "Отчёт заказан, придёт на $email" }
                }
            }
            R.id.sel_export -> withSelection { nums -> export(shown.filter { it.msisdn in nums }) }
        }
    }

    private fun withSelection(block: (List<String>) -> Unit) {
        if (selected.isEmpty()) {
            Snackbar.make(list, "Выберите номера: нажмите на карточки", Snackbar.LENGTH_SHORT).setAnchorView(fab).show()
            return
        }
        block(selected.toList())
    }

    private fun voluntaryBlock(nums: List<String>, on: Boolean) {
        val byNumber = rows.associateBy { it.msisdn }
        // Номера, где блокировка уже в нужном состоянии, пропускаем — лишних заявок не будет.
        val targets = nums.filter { byNumber[it]?.snap?.let { s -> s.blocks == null || s.voluntaryBlock != on } ?: true }
        if (targets.isEmpty()) {
            Snackbar.make(list, if (on) "На выбранных номерах блокировка уже включена" else "На выбранных номерах добровольной блокировки нет",
                Snackbar.LENGTH_LONG).setAnchorView(fab).show()
            return
        }
        LineDialogs.confirm(this, if (on) "Включить добровольную блокировку" else "Снять добровольную блокировку",
            "${targets.size} ${LineFormat.plural(targets.size, "номер", "номера", "номеров")}. " +
                (if (on) "Звонки, SMS и интернет на них перестанут работать до снятия блокировки. " else "") +
                "Для каждого номера в МТС уйдёт отдельная заявка, статус проверится в фоне.",
            if (on) "Заблокировать" else "Снять") {
            background(if (on) "Включаю блокировку…" else "Снимаю блокировку…") { progress ->
                var ok = 0; val errors = mutableListOf<String>()
                for ((i, m) in targets.withIndex()) {
                    progress("${i + 1} из ${targets.size}: ${SimFormat.phone(m)}")
                    runCatching { LineActions.setVoluntaryBlock(this, m, on) }.onSuccess { ok++ }
                        .onFailure { errors += "${SimFormat.phone(m)}: ${it.message}" }
                }
                if (errors.isNotEmpty()) throw SimActions.Failure("Отправлено заявок: $ok из ${targets.size}\n\n" + errors.take(10).joinToString("\n"))
                "Отправлено заявок: $ok. Статус появится в карточках номеров"
            }
            endSelection()
        }
    }

    /** Действие в фоне с плашкой хода; результат или ошибка — по окончании. */
    private fun background(start: String, block: (progress: (String) -> Unit) -> String) {
        val snack = Snackbar.make(list, start, Snackbar.LENGTH_INDEFINITE).setAnchorView(fab)
        snack.show()
        work.execute {
            val r = runCatching { block { t -> runOnUiThread { snack.setText(t) } } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                snack.dismiss()
                r.onSuccess { Snackbar.make(list, it, Snackbar.LENGTH_LONG).setAnchorView(fab).show() }
                r.onFailure { e ->
                    MaterialAlertDialogBuilder(this).setTitle("Не получилось").setMessage(e.message ?: "Ошибка")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Журнал") { _, _ -> openScreen(Intent(this, JournalActivity::class.java)) }.show()
                }
                reload()
            }
        }
    }

    private fun export(items: List<LineRow>) {
        if (items.isEmpty()) { Snackbar.make(list, "Нечего выгружать", Snackbar.LENGTH_SHORT).setAnchorView(fab).show(); return }
        io.execute {
            val intent = LineExport.csv(this, items)
            Journal.event(this, LogLevel.OK, LogCategory.EXPORT, "Выгрузка номеров МТС в Excel (CSV): ${items.size}")
            runOnUiThread { startActivity(intent) }
        }
    }

    // ---- Выбор номеров ----

    private fun startSelection(first: String?) {
        if (!selecting) {
            selecting = true
            back.isEnabled = true
            toolbar.menu.clear()
            toolbar.inflateMenu(R.menu.lines_selection)
            toolbar.setNavigationIcon(R.drawable.ic_close)
            fab.hide()
        }
        first?.let { toggle(it) }
        updateTitle()
    }

    private fun endSelection() {
        selecting = false
        back.isEnabled = false
        selected.clear()
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.lines)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        fab.show()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateTitle()
    }

    private fun toggle(msisdn: String) {
        if (!selected.remove(msisdn)) selected += msisdn
        val i = shown.indexOfFirst { it.msisdn == msisdn }
        if (i >= 0) adapter.notifyItemChanged(i)
        updateTitle()
    }

    // ---- Фильтры ----

    private fun openFilters() {
        LineFilterSheet.show(this, filter, rows, onApply = { setFilter(it) }, onSave = { f ->
            saved = saved.filter { it.name != f.name } + f
            LinePrefs.setFilters(this, LineFilter.listToJson(saved))
            renderPresets()
            setFilter(f)
        })
    }

    /** Сравнение условий без названия и поиска. */
    private fun sameCriteria(a: LineFilter, b: LineFilter) = a.copy(name = "", query = "") == b.copy(name = "", query = "")

    private fun renderPresets() {
        presets.removeAllViews()
        fun add(f: LineFilter, title: String, own: Boolean) {
            val chip = v.chip(title)
            chip.tag = f
            chip.setOnClickListener {
                val target = if (sameCriteria(filter, f) && title != "Все") LineFilter(sort = filter.sort) else f
                setFilter(target)
            }
            if (own) chip.setOnLongClickListener { editSaved(f); true }
            presets.addView(chip)
        }
        add(LineFilter(), "Все", false)
        LineFilter.PRESETS.forEach { add(it, it.name, false) }
        saved.forEach { add(it, it.name, true) }
        syncPresetChecks()
    }

    private fun syncPresetChecks() {
        for (i in 0 until presets.childCount) {
            val chip = presets.getChildAt(i) as? Chip ?: continue
            val f = chip.tag as? LineFilter ?: continue
            chip.isChecked = if (chip.text.toString() == "Все") filter.activeCount == 0 else sameCriteria(filter, f)
        }
    }

    private fun editSaved(f: LineFilter) {
        MaterialAlertDialogBuilder(this).setTitle("Набор «${f.name}»")
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) LineDialogs.text(this, "Название набора", "Название", f.name, 40, allowEmpty = false) { name ->
                    saved = saved.map { if (it == f) it.copy(name = name) else it }
                    LinePrefs.setFilters(this, LineFilter.listToJson(saved)); renderPresets()
                } else {
                    saved = saved - f
                    LinePrefs.setFilters(this, LineFilter.listToJson(saved)); renderPresets()
                }
            }.show()
    }

    // ---- Сводка ----

    data class Summary(
        val count: Int, val accountsBalance: Double?, val negativeAccounts: Int, val charges: Double?, val fee: Double?,
        val blocked: Int, val low: Int, val roaming: Int, val notLoaded: Int,
    ) {
        companion object {
            fun of(rows: List<LineRow>): Summary {
                val accounts = rows.filter { it.accountNo.isNotEmpty() && it.snap.balance?.amount != null }
                    .associate { it.accountNo to it.snap.balance!!.amount!! }
                fun sum(xs: List<Double>) = if (xs.isEmpty()) null else xs.sum()
                return Summary(
                    count = rows.size,
                    accountsBalance = sum(accounts.values.toList()),
                    negativeAccounts = accounts.values.count { it < 0 },
                    charges = sum(rows.mapNotNull { it.snap.charges }),
                    fee = sum(rows.mapNotNull { it.snap.monthlyFee }),
                    blocked = rows.count { it.snap.isBlocked },
                    low = rows.count { it.snap.balanceState() in setOf(BalanceState.LOW, BalanceState.NEGATIVE) },
                    roaming = rows.count { it.snap.abroad },
                    notLoaded = rows.count { !it.snap.loaded },
                )
            }
        }
    }

    /** Плитки сводки над списком: нажатие — быстрый фильтр. */
    inner class SummaryAdapter : RecyclerView.Adapter<SummaryAdapter.VH>() {
        private var s: Summary? = null

        fun update(x: Summary) {
            val had = s != null && s!!.count > 0
            s = x
            if (x.count == 0) { if (had) notifyItemRemoved(0) } else if (had) notifyItemChanged(0) else notifyItemInserted(0)
        }

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(HorizontalScrollView(row.context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = v.px(4) }
        })

        override fun getItemCount() = if ((s?.count ?: 0) > 0) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(v.px(4), v.px(2), v.px(4), v.px(2))
        })

        override fun onBindViewHolder(h: VH, position: Int) {
            val x = s ?: return
            h.row.removeAllViews()
            fun tile(title: String, value: String, color: Int?, note: String? = null, onClick: (() -> Unit)? = null) {
                val card = MaterialCardView(this@LinesActivity).apply {
                    radius = v.px(18).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(v.attr(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    strokeWidth = 0
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = v.px(8) }
                    if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
                }
                val col = LinearLayout(this@LinesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(v.px(14), v.px(10), v.px(16), v.px(10))
                    minimumWidth = v.px(112)
                }
                col.addView(TextView(this@LinesActivity).apply {
                    text = title; textSize = 12f
                    setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
                col.addView(TextView(this@LinesActivity).apply {
                    text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
                    fontFeatureSettings = "tnum"
                    setTextColor(color?.let { v.color(it) } ?: v.attr(com.google.android.material.R.attr.colorOnSurface))
                })
                note?.let { n ->
                    col.addView(TextView(this@LinesActivity).apply {
                        text = n; textSize = 11f
                        setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                }
                card.addView(col)
                h.row.addView(card)
            }
            tile("Номеров", x.count.toString(), null)
            x.accountsBalance?.let { b ->
                tile("Баланс счетов", Money.format(b), if (b < 0) R.color.bad else R.color.ok,
                    if (x.negativeAccounts > 0) "в минусе: ${x.negativeAccounts}" else null) {
                    setFilter(LineFilter(balance = ru.mycheckin.passport.mts.BalanceRule.NEGATIVE, sort = ru.mycheckin.passport.mts.LineSort.BALANCE))
                }
            }
            x.charges?.let { tile("Начислено за месяц", Money.format(it), null) { setFilter(LineFilter(sort = ru.mycheckin.passport.mts.LineSort.CHARGES)) } }
            x.fee?.let { tile("Абонплата в месяц", Money.format(it), null) { setFilter(LineFilter(sort = ru.mycheckin.passport.mts.LineSort.FEE)) } }
            tile("Заблокировано", x.blocked.toString(), if (x.blocked > 0) R.color.bad else null) { setFilter(LineFilter(blocks = BlockRule.SOME)) }
            tile("Баланс у порога", x.low.toString(), if (x.low > 0) R.color.warn else null) { setFilter(LineFilter.PRESETS[1]) }
            tile("В роуминге", x.roaming.toString(), if (x.roaming > 0) R.color.sent else null) { setFilter(LineFilter(roaming = Tri.YES)) }
            if (x.notLoaded > 0) tile("Без сведений", x.notLoaded.toString(), R.color.neutral, "нажмите — загрузить") {
                refreshStale(shown.filter { !it.snap.loaded }.map { it.msisdn })
            }
        }
    }

    // ---- Список ----

    inner class LineAdapter : ListAdapter<LineRow, LineAdapter.VH>(DIFF) {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.card)
            val phone: TextView = view.findViewById(R.id.phone)
            val balance: TextView = view.findViewById(R.id.balance)
            val owner: TextView = view.findViewById(R.id.owner)
            val tariff: TextView = view.findViewById(R.id.tariff)
            val packageRow: View = view.findViewById(R.id.package_row)
            val packageBar: LinearProgressIndicator = view.findViewById(R.id.package_bar)
            val packageText: TextView = view.findViewById(R.id.package_text)
            val badges: ChipGroup = view.findViewById(R.id.badges)
            val updated: TextView = view.findViewById(R.id.updated)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_line, parent, false))

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, position: Int) {
            val r = getItem(position)
            val s = r.snap
            h.phone.text = SimFormat.phone(r.msisdn)

            val amount = s.balance?.amount
            val state = s.balanceState()
            h.balance.text = amount?.let { Money.format(it) } ?: "нет баланса"
            h.balance.backgroundTintList = ColorStateList.valueOf(v.color(LineFormat.balanceColor(state)))

            h.owner.text = listOf(r.owner.ifBlank { "Без ФИО" }, r.contractTitle).filter { it.isNotBlank() }.joinToString(" · ")
            h.owner.alpha = if (r.owner.isBlank()) 0.65f else 1f
            h.tariff.text = listOfNotNull(
                s.tariff?.name,
                s.monthlyFee?.let { "${Money.format(it)} в мес." },
                s.charges?.let { "начислено ${Money.format(it)}" },
            ).joinToString(" · ").ifEmpty { if (s.loaded) "Тариф не загружен" else "Сведения ещё не загружены" }

            val low = s.lowestPackage
            if (low != null) {
                h.packageRow.visibility = View.VISIBLE
                h.packageBar.setIndicatorColor(v.color(LineFormat.shareColor(low.share)))
                h.packageBar.progress = ((low.share ?: 0.0) * 100).toInt()
                h.packageText.text = LineFormat.packages(s)
            } else h.packageRow.visibility = View.GONE

            h.badges.removeAllViews()
            s.blocks.orEmpty().forEach { b -> h.badges.addView(v.pill(LineApi.BLOCK_NAMES[b.code.uppercase()] ?: b.name, R.color.bad, solid = true)) }
            if (s.abroad) h.badges.addView(v.pill("Роуминг: ${s.location?.countryName ?: "за границей"}", R.color.sent))
            if (s.hasForwarding) h.badges.addView(v.pill("Переадресация", R.color.neutral))
            if (s.isEsim) h.badges.addView(v.pill("eSIM", R.color.neutral))
            if (!r.inMts) h.badges.addView(v.pill("Нет в МТС", R.color.bad))
            s.comment?.takeIf { it.isNotBlank() }?.let { h.badges.addView(v.pill("«$it»", R.color.neutral)) }
            if (s.errors.isNotEmpty()) h.badges.addView(v.pill("Ошибки: ${s.errors.size}", R.color.warn))
            h.badges.visibility = if (h.badges.childCount == 0) View.GONE else View.VISIBLE

            h.updated.text = if (s.loaded) "Обновлено ${LineFormat.ago(s.updatedAt)}" else "Нажмите «Обновить», чтобы загрузить из МТС"

            h.card.isChecked = r.msisdn in selected
            h.card.setOnClickListener {
                if (selecting) toggle(r.msisdn)
                else openScreen(Intent(this@LinesActivity, LineDetailActivity::class.java).putExtra(LineDetailActivity.EXTRA_MSISDN, r.msisdn))
            }
            h.card.setOnLongClickListener { startSelection(r.msisdn); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LineRow>() {
            override fun areItemsTheSame(a: LineRow, b: LineRow) = a.msisdn == b.msisdn
            override fun areContentsTheSame(a: LineRow, b: LineRow) = a == b
        }
    }
}
