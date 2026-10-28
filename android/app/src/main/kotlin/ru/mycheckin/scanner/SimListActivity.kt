package ru.mycheckin.scanner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Плавное открытие и закрытие экранов. */
@Suppress("DEPRECATION")
fun Activity.openScreen(intent: Intent) {
    startActivity(intent)
    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
}

@Suppress("DEPRECATION")
fun Activity.closeScreenAnimation() = overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)

/** Главный экран: учёт SIM-карт — поиск, контракты, загрузка из МТС, выгрузка в Excel. */
class SimListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var search: TextInputEditText
    private lateinit var chips: ChipGroup
    private lateinit var progress: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var fab: ExtendedFloatingActionButton

    private val db by lazy { SimDb.get(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val api = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = SimAdapter()

    private var all: List<SimRecord> = emptyList()
    private var contracts: Map<String, Contract> = emptyMap()
    private var contractFilter: String? = null
    private var busy = false
    private val cancelBulk = AtomicBoolean(false)
    private lateinit var searchLayout: com.google.android.material.textfield.TextInputLayout

    /** Сканер кода SIM: найденная карточка открывается сразу, иначе значение подставляется в поиск. */
    private val scanCode = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val data = r.data ?: return@registerForActivityResult
        val value = data.getStringExtra(CodeScanActivity.RESULT_VALUE).orEmpty()
        val simId = data.getLongExtra(CodeScanActivity.RESULT_SIM_ID, 0L)
        search.setText(value)
        search.setSelection(search.text?.length ?: 0)
        if (simId != 0L) openScreen(Intent(this, SimEditActivity::class.java).putExtra(SimEditActivity.EXTRA_ID, simId))
        else Snackbar.make(list, "SIM с кодом $value нет в списке", Snackbar.LENGTH_LONG).setAnchorView(fab)
            .setAction("Добавить") {
                val kind = data.getStringExtra(CodeScanActivity.RESULT_KIND)
                openScreen(Intent(this, SimEditActivity::class.java)
                    .putExtra(SimEditActivity.EXTRA_CONTRACT, contractFilter)
                    .putExtra(if (kind == "PHONE") SimEditActivity.EXTRA_PHONE else SimEditActivity.EXTRA_ICCID, value))
            }.show()
    }

    private fun updateSearchIcon() {
        val empty = search.text.isNullOrEmpty()
        val mode = if (empty) com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
        else com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
        if (searchLayout.endIconMode == mode) return
        searchLayout.endIconMode = mode
        if (empty) {
            searchLayout.setEndIconDrawable(R.drawable.ic_scan_code)
            searchLayout.endIconContentDescription = "Сканировать номер или штрихкод SIM"
            searchLayout.setEndIconOnClickListener { openScanner() }
        }
    }

    private fun openScanner() {
        scanCode.launch(Intent(this, CodeScanActivity::class.java).putExtra(CodeScanActivity.EXTRA_TARGET, CodeScanActivity.TARGET_ANY))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sims)
        toolbar = findViewById(R.id.toolbar)
        search = findViewById(R.id.search)
        searchLayout = findViewById(R.id.search_layout)
        searchLayout.setEndIconOnClickListener { openScanner() }
        chips = findViewById(R.id.contracts)
        progress = findViewById(R.id.sync_progress)
        progressText = findViewById(R.id.sync_text)
        refresh = findViewById(R.id.refresh)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fab)

        contractFilter = savedInstanceState?.getString("contract")
        io.execute {
            Journal.markInterruptedOnce(this)
            val crash = CrashLog.takePending(this) ?: return@execute
            Journal.event(this, LogLevel.ERROR, LogCategory.APP, "Приложение аварийно закрылось", crash)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(this)
                    .setTitle("Прошлый раз приложение закрылось с ошибкой")
                    .setMessage(crash.lineSequence().take(5).joinToString("\n") + "\n\nПолный отчёт записан в журнал. Отправьте его разработчику, чтобы исправить причину.")
                    .setPositiveButton("Открыть журнал") { _, _ -> openJournal() }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator?.apply { addDuration = 220; removeDuration = 160; moveDuration = 260; changeDuration = 200 }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8 && fab.isExtended) fab.shrink() else if (dy < -8 && !fab.isExtended) fab.extend()
            }
        })

        refresh.setColorSchemeColors(MaterialColors.getColor(refresh, com.google.android.material.R.attr.colorPrimary))
        refresh.setOnRefreshListener { syncFromMts() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSearchIcon()
                main.removeCallbacks(applyFilter)
                main.postDelayed(applyFilter, 140)
            }
        })

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sync -> syncFromMts()
                R.id.action_export -> export()
                R.id.action_owners -> askLoadOwners()
                R.id.action_contracts -> renameContracts()
                R.id.action_settings -> openScreen(Intent(this, SettingsActivity::class.java))
                R.id.action_journal -> openJournal()
            }
            true
        }

        fab.setOnClickListener {
            openScreen(Intent(this, SimEditActivity::class.java).putExtra(SimEditActivity.EXTRA_CONTRACT, contractFilter))
        }
        findViewById<View>(R.id.empty_action).setOnClickListener {
            if (all.isEmpty()) syncFromMts() else search.setText("")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("contract", contractFilter)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelBulk.set(true)
        io.shutdown()
        api.shutdown()
        faceLoader.shutdown()
    }

    // ---- Данные ----

    private fun reload(animate: Boolean = false) {
        io.execute {
            val sims = db.all()
            val cs = db.contracts().associateBy { it.id }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val firstLoad = all.isEmpty() && sims.isNotEmpty()
                all = sims
                contracts = cs
                renderChips()
                if (firstLoad || animate) adapter.resetEntrance()
                applyFilter.run()
            }
        }
    }

    private val applyFilter = Runnable {
        val q = search.text?.toString().orEmpty()
        val shown = all.filter { (contractFilter == null || it.contractId == contractFilter) && SimSearch.matches(it, q) }
        adapter.contracts = contracts
        adapter.submitList(shown)
        toolbar.subtitle = when {
            all.isEmpty() -> null
            shown.size == all.size -> "${all.size} ${plural(all.size, "SIM-карта", "SIM-карты", "SIM-карт")}"
            else -> "Показано ${shown.size} из ${all.size}"
        }
        val showEmpty = shown.isEmpty() && !busy
        findViewById<TextView>(R.id.empty_title).text = if (all.isEmpty()) "Список SIM-карт пуст" else "Ничего не найдено"
        findViewById<TextView>(R.id.empty_text).text = if (all.isEmpty())
            "Загрузите номера и SIM-карты из МТС Бизнес или добавьте карту вручную" else "Проверьте запрос или выберите другой контракт"
        findViewById<TextView>(R.id.empty_action).text = if (all.isEmpty()) "Загрузить из МТС" else "Сбросить поиск"
        fade(empty, showEmpty)
    }

    private fun renderChips() {
        val counts = all.groupingBy { it.contractId }.eachCount()
        val ids = (contracts.keys + counts.keys.filter { it.isNotEmpty() }).distinct()
        if (contractFilter != null && contractFilter !in ids) contractFilter = null
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        chips.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        fun chip(text: String, id: String?): Chip = Chip(this).apply {
            this.text = text
            isCheckable = true
            isCheckedIconVisible = true
            tag = id
            setEnsureMinTouchTargetSize(false)
        }
        chips.addView(chip("Все · ${all.size}", null).apply { isChecked = contractFilter == null })
        for (id in ids.sortedBy { contracts[it]?.title ?: it }) {
            val title = contracts[id]?.title ?: id
            chips.addView(chip("$title · ${counts[id] ?: 0}", id).apply { isChecked = contractFilter == id })
        }
        chips.setOnCheckedStateChangeListener { group, checked ->
            val chip = checked.firstOrNull()?.let { group.findViewById<Chip>(it) }
            contractFilter = chip?.tag as? String
            adapter.resetEntrance()
            applyFilter.run()
            list.scrollToPosition(0)
        }
    }

    // ---- МТС ----

    private fun setBusy(on: Boolean, text: String? = null, determinate: Int? = null) {
        busy = on
        toolbar.menu.findItem(R.id.action_sync)?.isEnabled = !on
        toolbar.menu.findItem(R.id.action_owners)?.isEnabled = !on
        if (!on) refresh.isRefreshing = false
        if (determinate != null) {
            if (progress.isIndeterminate) progress.isIndeterminate = false
            progress.setProgressCompat(determinate, true)
        } else if (on && !progress.isIndeterminate) {
            // Material запрещает включать «бесконечный» режим у видимого индикатора — прячем на время переключения.
            progress.animate().cancel()
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = true
        }
        fade(progress, on)
        progressText.text = text
        fade(progressText, on && !text.isNullOrEmpty())
    }

    private fun syncFromMts() {
        if (busy) { refresh.isRefreshing = false; return }
        if (!MtsService.hasAccounts(this)) {
            refresh.isRefreshing = false
            askSettings()
            return
        }
        setBusy(true, "Подключаюсь к МТС…")
        api.execute {
            val outcome = try {
                Result.success(SimActions.syncNumbers(this) { text, n ->
                    runOnUiThread { progressText.text = if (n > 0) "$text · найдено $n" else text }
                })
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                outcome.onSuccess { report ->
                    val r = report.result
                    reload(animate = true)
                    if (report.errors.isNotEmpty()) error("Часть доступов не загрузилась", report.errors.joinToString("\n\n"))
                    val msg = buildString {
                        append("Из МТС: ${r.total} номеров")
                        if (r.added > 0) append(", новых ${r.added}")
                        if (r.missing > 0) append(", пропали из МТС ${r.missing}")
                    }
                    val withoutNames = all.count { it.inMts && it.fullName.isEmpty() } + r.added
                    Snackbar.make(list, msg, Snackbar.LENGTH_LONG).apply {
                        anchorView = fab
                        if (withoutNames > 0) setAction("ФИО из МТС") { askLoadOwners() }
                        else setAction("Журнал") { openJournal() }
                    }.show()
                }
                outcome.onFailure { e -> error("Не удалось загрузить номера", e.message) }
            }
        }
    }

    /** Загрузить ФИО, паспорт и статус подтверждения для номеров, где их ещё нет. */
    private fun askLoadOwners() {
        if (busy) return
        if (!MtsService.hasAccounts(this)) { askSettings(); return }
        val lacking = all.filter { it.inMts && (!it.hasPassport || it.fullName.isEmpty()) }
        // Номера, по которым МТС уже ответил «данных нет» за последние сутки, не запрашиваем повторно — экономим вызовы.
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
        val targets = lacking.filter { it.checkedAt < dayAgo || it.confirmation.isEmpty() }
        val skipped = lacking.size - targets.size
        if (targets.isEmpty()) {
            val text = if (lacking.isEmpty()) "У всех номеров из МТС уже есть ФИО и паспорт"
            else "По $skipped номерам МТС сегодня уже ответил, что данных нет — их нужно внести"
            Snackbar.make(list, text, Snackbar.LENGTH_LONG).setAnchorView(fab).show()
            return
        }
        val minutes = (targets.size / 280) + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Данные владельцев из МТС")
            .setMessage(
                "Для ${targets.size} ${plural(targets.size, "номера", "номеров", "номеров")} нет ФИО или паспорта. " +
                    "Запросить то, что уже внесено в МТС, и статус подтверждения на Госуслугах?\n\n" +
                    "Это ${targets.size} вызовов API, около $minutes мин. Заполняются только пустые поля." +
                    if (skipped > 0) "\n\nПропущено $skipped номеров: МТС за последние сутки уже ответил, что данных нет." else "",
            )
            .setPositiveButton("Загрузить") { _, _ -> loadOwners(targets) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun loadOwners(targets: List<SimRecord>) {
        cancelBulk.set(false)
        setBusy(true, "Данные владельцев: 0 из ${targets.size}", 0)
        val snack = Snackbar.make(list, "Загружаю данные владельцев…", Snackbar.LENGTH_INDEFINITE)
            .setAction("Остановить") { cancelBulk.set(true) }.setAnchorView(fab)
        snack.show()
        api.execute {
            var filled = 0; var failed = 0; var lastError: String? = null; var done = 0
            val failedSims = mutableListOf<SimRecord>()
            val started = System.currentTimeMillis()
            for ((i, s) in targets.withIndex()) {
                if (cancelBulk.get()) break
                try {
                    val u = SimActions.loadOwner(this, s)
                    if (u.fullName.isNotEmpty() && s.fullName.isEmpty() || u.hasPassport && !s.hasPassport) filled++
                } catch (e: SimActions.Failure) {
                    failed++; lastError = e.message; failedSims += s
                    if (e.message?.contains("лимит") == true || e.message?.contains("логин") == true) break
                } catch (e: Exception) {
                    // Непредвиденная ошибка по одному номеру не должна останавливать загрузку и ронять приложение.
                    failed++; lastError = "${e.javaClass.simpleName}: ${e.message}"; failedSims += s
                    runCatching {
                        Journal.event(this, LogLevel.ERROR, LogCategory.OWNER, "Сбой при загрузке данных владельца",
                            android.util.Log.getStackTraceString(e).take(4000), msisdn = s.msisdn)
                    }
                }
                done = i + 1
                val d = done
                runOnUiThread { setBusy(true, "Данные владельцев: $d из ${targets.size}", d * 100 / targets.size) }
            }
            Journal.event(this, when { failed == 0 -> LogLevel.OK; filled > 0 -> LogLevel.WARN; else -> LogLevel.ERROR },
                LogCategory.OWNER, "Массовая загрузка данных владельцев: $done из ${targets.size}",
                listOfNotNull(
                    "Заполнено карточек: $filled, ошибок: $failed",
                    if (cancelBulk.get()) "Остановлено вручную" else null,
                    lastError?.let { "Последняя ошибка: $it" },
                    "Время: ${(System.currentTimeMillis() - started) / 1000} с. Каждый номер — отдельная запись журнала.",
                ).joinToString("\n"))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                snack.dismiss()
                setBusy(false)
                reload()
                val msg = "Заполнено карточек: $filled" + if (failed > 0) " · ошибок: $failed" else ""
                Snackbar.make(list, msg, if (failedSims.isEmpty()) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE).apply {
                    anchorView = fab
                    // Ошибки чаще всего — обрывы связи: предлагаем повторить только эти номера.
                    if (failedSims.isNotEmpty()) setAction("Повторить (${failedSims.size})") { loadOwners(failedSims.toList()) }
                    else setAction("Журнал") { openJournal() }
                }.show()
            }
        }
    }

    private fun export() {
        val shown = adapter.currentList
        if (shown.isEmpty()) {
            Snackbar.make(list, "Нечего выгружать", Snackbar.LENGTH_SHORT).setAnchorView(fab).show()
            return
        }
        io.execute {
            val intent = SimExport.csv(this, shown, contracts)
            Journal.event(this, LogLevel.OK, LogCategory.EXPORT, "Выгрузка в Excel (CSV): ${shown.size} SIM",
                listOfNotNull(
                    "Контракт: " + (contractFilter?.let { contracts[it]?.title ?: it } ?: "все"),
                    search.text?.toString()?.takeIf { it.isNotBlank() }?.let { "Поиск: «$it»" },
                    "Файл: " + intent.getParcelableExtraCompat()?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                ).joinToString("\n"))
            runOnUiThread { startActivity(intent) }
        }
    }

    private fun renameContracts() {
        val cs = contracts.values.sortedBy { it.title }
        if (cs.isEmpty()) {
            Snackbar.make(list, "Контракты появятся после загрузки из МТС", Snackbar.LENGTH_SHORT).setAnchorView(fab).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Названия контрактов")
            .setItems(cs.map { "${it.title}\n${it.id}" + (it.orgName.takeIf { o -> o.isNotBlank() && o != it.title }?.let { o -> " · $o" } ?: "") }.toTypedArray()) { _, i ->
                val c = cs[i]
                val input = EditText(this).apply { setText(c.label); hint = c.orgName.ifBlank { "Например: Ростовский" }; setSelection(text.length) }
                val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Контракт ${c.id}")
                    .setView(box)
                    .setPositiveButton("Сохранить") { _, _ ->
                        io.execute {
                            db.renameContract(c.id, input.text.toString())
                            Journal.event(this, LogLevel.INFO, LogCategory.SETTINGS, "Контракт ${c.id}: название «${input.text.toString().trim()}»")
                            reload()
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .show()
    }

    private fun askSettings() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Нужен доступ к МТС")
            .setMessage("Введите логин и пароль для интеграции из личного кабинета МТС Бизнес (раздел «API для бизнеса»).")
            .setPositiveButton("Открыть настройки") { _, _ -> openScreen(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun error(title: String, text: String?) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text ?: "Неизвестная ошибка").setPositiveButton("OK", null)
            .setNeutralButton("Журнал") { _, _ -> openJournal() }.show()
    }

    private fun openJournal() = openScreen(Intent(this, JournalActivity::class.java))

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(): Intent? = getParcelableExtra(Intent.EXTRA_INTENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fade(v: View, show: Boolean) {
        // Прежняя анимация отменяется (её завершающее действие не выполнится), чтобы
        // быстро идущие подряд «показать/скрыть» не оставили элемент в неверном состоянии.
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(200).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
        } else {
            v.alpha = 1f
        }
    }

    // ---- Список ----

    private val faces = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val faceLoader = Executors.newFixedThreadPool(2)

    inner class SimAdapter : ListAdapter<SimRecord, SimAdapter.VH>(DIFF) {
        var contracts: Map<String, Contract> = emptyMap()
        private var lastAnimated = -1
        private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

        fun resetEntrance() { lastAnimated = -1 }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val initials: TextView = v.findViewById(R.id.initials)
            val face: ImageView = v.findViewById(R.id.face)
            val name: TextView = v.findViewById(R.id.name)
            val status: TextView = v.findViewById(R.id.status)
            val phone: TextView = v.findViewById(R.id.phone)
            val iccid: TextView = v.findViewById(R.id.iccid)
            val meta: TextView = v.findViewById(R.id.meta)
            val flag: TextView = v.findViewById(R.id.flag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sim, parent, false))

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, position: Int) {
            val s = getItem(position)
            val ctx = h.itemView.context
            h.name.text = s.fullName.ifEmpty { "Без ФИО" }
            h.name.alpha = if (s.fullName.isEmpty()) 0.6f else 1f
            h.phone.text = SimFormat.phone(s.msisdn)
            h.iccid.text = if (s.iccid.isNotEmpty()) "SIM ${s.iccid}" else "SIM не указана"
            val contract = contracts[s.contractId]?.title ?: s.contractId
            h.meta.text = listOfNotNull(
                contract.ifEmpty { null },
                SimFormat.passport(s),
                s.issueDate?.let { "выдан ${it.format(SimFormat.DATE)}" },
            ).joinToString(" · ")

            val st = SimStatus.of(s)
            h.status.text = st.label
            h.status.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, st.color))
            h.flag.visibility = if (!s.inMts && s.contractId.isNotEmpty()) View.VISIBLE else View.GONE
            h.flag.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.bad))

            val primary = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorPrimaryContainer)
            h.initials.backgroundTintList = ColorStateList.valueOf(primary)
            h.initials.setTextColor(MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer))
            h.initials.text = listOf(s.surname, s.name).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifEmpty { "#" }
            bindFace(h, s.facePhoto)

            h.itemView.setOnClickListener {
                openScreen(Intent(this@SimListActivity, SimEditActivity::class.java).putExtra(SimEditActivity.EXTRA_ID, s.id))
            }

            if (position > lastAnimated) {
                lastAnimated = position
                val v = h.itemView
                v.alpha = 0f
                v.translationY = dp(28).toFloat()
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay((position.coerceAtMost(8) * 35).toLong())
                    .setDuration(320).setInterpolator(ease).start()
            }
        }

        override fun onViewRecycled(h: VH) {
            h.itemView.animate().cancel()
            h.itemView.alpha = 1f
            h.itemView.translationY = 0f
        }

        private fun bindFace(h: VH, path: String) {
            h.face.tag = path
            if (path.isEmpty()) { h.face.visibility = View.GONE; return }
            faces.get(path)?.let { h.face.setImageBitmap(it); h.face.visibility = View.VISIBLE; return }
            h.face.visibility = View.GONE
            faceLoader.execute {
                val bmp = SimPhotos.load(path, 200) ?: return@execute
                faces.put(path, bmp)
                runOnUiThread {
                    if (h.face.tag == path) {
                        h.face.setImageBitmap(bmp)
                        h.face.alpha = 0f
                        h.face.visibility = View.VISIBLE
                        h.face.animate().alpha(1f).setDuration(180).start()
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SimRecord>() {
            override fun areItemsTheSame(a: SimRecord, b: SimRecord) = a.id == b.id
            override fun areContentsTheSame(a: SimRecord, b: SimRecord) = a == b
        }

        fun plural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10; val m100 = n % 100
            return when {
                m10 == 1 && m100 != 11 -> one
                m10 in 2..4 && m100 !in 12..14 -> few
                else -> many
            }
        }
    }
}
