package ru.mycheckin.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import ru.mycheckin.passport.mts.AvailableTariff
import ru.mycheckin.passport.mts.BudgetRule
import ru.mycheckin.passport.mts.Forwarding
import ru.mycheckin.passport.mts.LineApi
import ru.mycheckin.passport.mts.LineSection
import ru.mycheckin.passport.mts.LineSnapshot
import ru.mycheckin.passport.mts.Money
import ru.mycheckin.passport.mts.PersonalDataApi
import ru.mycheckin.passport.mts.ProductItem
import ru.mycheckin.passport.mts.PromisedPayment
import java.util.concurrent.Executors

/**
 * Карточка номера: всё, что МТС Бизнес API сообщает о номере и его лицевом счёте, и все доступные
 * изменения. Сведения показываются сразу из базы на телефоне; «Обновить» запрашивает свежие у МТС.
 */
class LineDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MSISDN = "msisdn"
        private const val MENU_REFRESH = 1
        private const val MENU_SIM = 2
    }

    private lateinit var msisdn: String
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: LinearLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout
    private val ui by lazy { Ui(this) }
    private val v by lazy { LineViews(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val work = Executors.newSingleThreadExecutor()

    private var sim: SimRecord? = null
    private var snap: LineSnapshot? = null
    private var requests: List<LineRequest> = emptyList()
    private var busy = 0

    private val syncListener: () -> Unit = { if (!isDestroyed && !LineSync.state.running) load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        msisdn = intent.getStringExtra(EXTRA_MSISDN).orEmpty()
        if (msisdn.isEmpty()) { finish(); return }

        toolbar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_back)
            navigationContentDescription = "Назад"
            setNavigationOnClickListener { finish(); closeScreenAnimation() }
            title = SimFormat.phone(msisdn)
            menu.add(0, MENU_REFRESH, 0, "Обновить из МТС").setIcon(R.drawable.ic_sync).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, MENU_SIM, 1, "Карточка SIM и паспорт").setIcon(R.drawable.ic_sim).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_REFRESH -> refresh()
                    MENU_SIM -> openSim()
                }
                true
            }
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true; visibility = View.INVISIBLE }
        val bar = AppBarLayout(this).apply {
            setBackgroundColor(v.attr(com.google.android.material.R.attr.colorSurface))
            addView(toolbar, AppBarLayout.LayoutParams(-1, v.px(56)))
            addView(progress, AppBarLayout.LayoutParams(-1, -2))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(12), v.px(4), v.px(12), v.px(40)) }
        val scroll = NestedScrollView(this).apply { addView(content) }
        swipe = SwipeRefreshLayout(this).apply {
            addView(scroll)
            setOnRefreshListener { isRefreshing = false; refresh() }
        }
        val root = CoordinatorLayout(this).apply {
            setBackgroundColor(v.attr(com.google.android.material.R.attr.colorSurface))
            addView(bar, CoordinatorLayout.LayoutParams(-1, -2))
            addView(swipe, CoordinatorLayout.LayoutParams(-1, -1).apply { behavior = AppBarLayout.ScrollingViewBehavior() })
        }
        setContentView(root)
        load(firstTime = true)
    }

    override fun onStart() { super.onStart(); LineSync.addListener(syncListener) }
    override fun onStop() { super.onStop(); LineSync.removeListener(syncListener) }
    override fun onDestroy() { super.onDestroy(); io.shutdown(); work.shutdown() }

    // ---- Данные ----

    private fun load(firstTime: Boolean = false) {
        io.execute {
            val s = SimDb.get(this).byMsisdn(msisdn)
            val snapshot = LineStore.get(this).snapshot(msisdn, s?.accountNo.orEmpty())
            val reqs = LineStore.get(this).requests(msisdn, 30)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                sim = s; snap = snapshot; requests = reqs
                render()
                // Сведений нет совсем — сразу запрашиваем.
                if (firstTime && !snapshot.loaded && MtsService.hasAccounts(this)) refresh()
            }
        }
    }

    private fun refresh() {
        mts("Запрашиваю сведения у МТС…", { LineSync.refreshNumber(this, msisdn) }) { load(); toast("Сведения обновлены") }
    }

    /** Запрос к МТС в фоне: индикатор сверху, по окончании — [done] или сообщение об ошибке. */
    private fun <T> mts(text: String?, block: () -> T, done: (T) -> Unit) {
        busy++
        progress.visibility = View.VISIBLE
        text?.let { toolbar.subtitle = it }
        work.execute {
            val r = runCatching(block)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy--
                if (busy == 0) progress.visibility = View.INVISIBLE
                updateSubtitle()
                r.onSuccess(done)
                r.onFailure { e ->
                    MaterialAlertDialogBuilder(this).setTitle("Не получилось").setMessage(e.message ?: "Ошибка")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Журнал") { _, _ -> openScreen(Intent(this, JournalActivity::class.java)) }.show()
                    load()
                }
            }
        }
    }

    private fun toast(text: String) = Snackbar.make(content, text, Snackbar.LENGTH_SHORT).show()

    private fun updateSubtitle() {
        val s = snap ?: return
        toolbar.subtitle = if (s.loaded) "Обновлено ${LineFormat.ago(s.updatedAt)}" else "Сведения не загружены"
    }

    private fun openSim() {
        val id = sim?.id ?: 0L
        val i = Intent(this, SimEditActivity::class.java)
        if (id != 0L) i.putExtra(SimEditActivity.EXTRA_ID, id) else i.putExtra(SimEditActivity.EXTRA_PHONE, msisdn)
        openScreen(i)
    }

    // ---- Построение экрана ----

    private fun card(title: String, sections: List<LineSection>, actions: List<Pair<String, () -> Unit>> = emptyList(), body: LinearLayout.() -> Unit) {
        val s = snap ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(16), v.px(14), v.px(16), v.px(12)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(v.attr(com.google.android.material.R.attr.colorPrimary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val at = sections.mapNotNull { s.fetchedAt[it] }.filter { it > 0 }.minOrNull()
        if (sections.isNotEmpty()) head.addView(TextView(this).apply {
            text = LineFormat.ago(at); textSize = 11f
            setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        box.addView(head)
        box.body()
        sections.mapNotNull { sec -> s.errors[sec]?.let { "${sec.title}: $it" } }.forEach { e ->
            box.addView(TextView(this).apply {
                text = e; textSize = 12f; setTextColor(v.color(R.color.warn)); setPadding(0, v.px(6), 0, 0)
            })
        }
        if (actions.isNotEmpty()) {
            val g = ChipGroup(this).apply { chipSpacingHorizontal = v.px(6); setPadding(0, v.px(8), 0, 0) }
            actions.forEach { (t, f) -> g.addView(v.chip(t, checkable = false).apply { setOnClickListener { f() } }) }
            box.addView(g)
        }
        content.addView(MaterialCardView(this).apply {
            radius = v.px(22).toFloat(); cardElevation = 0f
            setCardBackgroundColor(v.attr(com.google.android.material.R.attr.colorSurfaceContainer))
            strokeColor = v.attr(com.google.android.material.R.attr.colorOutlineVariant); strokeWidth = v.px(1)
            addView(box)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(v.px(4), v.px(6), v.px(4), v.px(6)) })
    }

    private fun LinearLayout.row(label: String, value: String, color: Int? = null, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this@LineDetailActivity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, v.px(6), 0, v.px(2))
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } ; setBackgroundResource(android.R.drawable.list_selector_background) }
        }
        r.addView(TextView(this@LineDetailActivity).apply {
            text = label; textSize = 14f; setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(this@LineDetailActivity).apply {
            text = value; textSize = 14f; setTypeface(typeface, Typeface.BOLD); fontFeatureSettings = "tnum"
            gravity = android.view.Gravity.END
            setTextColor(color?.let { v.color(it) } ?: v.attr(com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, -2, 1.3f))
        addView(r)
    }

    private fun LinearLayout.item(title: String, subtitle: String?, badge: Pair<String, Int>? = null, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this@LineDetailActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, v.px(8), 0, v.px(6))
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        val texts = LinearLayout(this@LineDetailActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@LineDetailActivity).apply {
            text = title; textSize = 15f; setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurface))
        })
        subtitle?.takeIf { it.isNotBlank() }?.let {
            texts.addView(TextView(this@LineDetailActivity).apply {
                text = it; textSize = 12f; setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        badge?.let { (t, c) -> r.addView(v.pill(t, c)) }
        addView(r)
    }

    private fun LinearLayout.empty(text: String) = addView(v.hint(text).apply { setPadding(0, v.px(6), 0, 0) })

    private fun render() {
        val s = snap ?: return
        content.removeAllViews()
        updateSubtitle()
        val accountNo = sim?.accountNo.orEmpty()
        val contract = sim?.contractId?.takeIf { it.isNotEmpty() }?.let { SimDb.get(this).contract(it)?.title ?: it }

        // Заголовок
        card(sim?.fullName?.ifBlank { null } ?: "Владелец не указан", listOf(LineSection.SIM_TYPE)) {
            row("Номер", SimFormat.phone(msisdn)) { copy(msisdn) }
            contract?.let { row("Контракт", it) }
            if (accountNo.isNotEmpty()) row("Лицевой счёт", accountNo) { copy(accountNo) }
            sim?.iccid?.takeIf { it.isNotEmpty() }?.let { row("SIM (ICCID)", it) { copy(it) } }
            sim?.region?.takeIf { it.isNotEmpty() }?.let { row("Регион", it) }
            s.simType?.let { row("Тип SIM-карты", if (s.isEsim) "eSIM" else it) }
            if (sim?.inMts == false) row("В МТС", "номер пропал из структуры", R.color.bad)
        }

        // Баланс и счёт
        val bal = s.balance
        card("Баланс и начисления", listOf(LineSection.BALANCE, LineSection.UNPAID, LineSection.CHARGES, LineSection.PROMISED), listOfNotNull(
            if (accountNo.isNotEmpty()) "Обещанный платёж" to { promised(accountNo) } else null,
            "Платежи" to { payments(accountNo) },
            "Счёт на email" to { orderBill() },
            "Отчёт по балансу" to { balanceReport() },
        )) {
            if (bal?.amount != null) {
                addView(TextView(this@LineDetailActivity).apply {
                    text = Money.format(bal.amount!!); textSize = 30f; setTypeface(typeface, Typeface.BOLD); fontFeatureSettings = "tnum"
                    setTextColor(v.color(LineFormat.balanceColor(s.balanceState())))
                    setPadding(0, v.px(6), 0, v.px(2))
                })
                addView(v.hint("Баланс лицевого счёта — ${s.balanceState().title.lowercase()}"))
            } else empty("Баланс ещё не загружен")
            s.blockThreshold?.let { row("Порог блокировки", Money.format(it)) }
            s.available?.let { if (s.blockThreshold != null) row("Можно потратить до блокировки", Money.format(it), if (it < 300) R.color.warn else null) }
            s.unpaid?.let { row("Неоплаченные счета", Money.format(it), if (it > 0) R.color.bad else null) }
            s.charges?.let { row("Начислено за месяц", Money.format(it)) }
            bal?.validTo?.takeIf { it.year < 2037 }?.let { row("Баланс действует до", LineFormat.date(it)) }
            s.promised?.let { p ->
                if (p.amount != null && p.amount!! > 0) row("Обещанный платёж", Money.format(p.amount!!) + (p.dateTo?.let { " до ${LineFormat.date(it)}" } ?: ""), R.color.sent)
            }
        }

        // Тариф
        card("Тариф", listOf(LineSection.TARIFF, LineSection.TARIFF_FEE), listOf("Сменить тариф" to { changeTariff() })) {
            val t = s.tariff
            if (t == null) empty("Тариф ещё не загружен")
            else {
                item(t.name, listOfNotNull(t.code.takeIf { it.isNotBlank() }?.let { "код $it" }, t.regionalName,
                    t.since?.let { "с ${LineFormat.date(it)}" }).joinToString(" · "))
            }
            s.tariffFee?.let { f ->
                f.current?.let { row("Абонплата", Money.format(it) + (Money.unit(f.currentUnit)?.let { u -> " $u" } ?: "")) }
                f.next?.let { row("Со следующего периода", Money.format(it) + (Money.unit(f.nextUnit)?.let { u -> " $u" } ?: ""), R.color.sent) }
                f.discount?.takeIf { it != 0.0 }?.let { row("Скидка", Money.format(it)) }
            }
            s.monthlyFee?.let { row("Всего в месяц с услугами", Money.format(it)) }
        }

        // Пакеты
        card("Остатки пакетов", listOf(LineSection.COUNTERS)) {
            val list = s.counters.orEmpty()
            if (s.counters == null) empty("Остатки ещё не загружены")
            else if (list.isEmpty()) empty("Пакетов минут, SMS и интернета нет")
            list.forEach { c ->
                addView(TextView(this@LineDetailActivity).apply {
                    text = c.title; textSize = 14f; setPadding(0, v.px(8), 0, v.px(2))
                    setTextColor(v.attr(com.google.android.material.R.attr.colorOnSurface))
                })
                c.share?.let { share ->
                    addView(LinearProgressIndicator(this@LineDetailActivity).apply {
                        max = 100; progress = (share * 100).toInt(); trackCornerRadius = v.px(4); trackThickness = v.px(8)
                        setIndicatorColor(v.color(LineFormat.shareColor(share)))
                    })
                }
                addView(v.hint(LineFormat.counter(c) + (c.until?.let { " · до ${LineFormat.date(it)}" } ?: "")))
            }
        }

        // Блокировки
        card("Блокировки", listOf(LineSection.BLOCKS)) {
            val blocks = s.blocks
            if (blocks == null) empty("Блокировки ещё не загружены")
            else if (blocks.isEmpty()) item("Блокировок нет", "Номер работает", "Активен" to R.color.ok)
            blocks.orEmpty().forEach { b ->
                item(LineApi.BLOCK_NAMES[b.code.uppercase()] ?: b.name, "с ${LineFormat.dateTime(b.since)}" +
                    (b.until?.let { " · до ${LineFormat.date(it)}" } ?: ""), b.code to R.color.bad)
            }
            if (blocks != null) addView(MaterialSwitch(this@LineDetailActivity).apply {
                text = "Добровольная блокировка"
                isChecked = s.voluntaryBlock
                // Переключатель меняется только после ответа МТС: сначала подтверждение и заявка.
                setOnClickListener { val on = isChecked; isChecked = !on; voluntaryBlock(on) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = v.px(8) }
            })
        }

        // Услуги
        card("Услуги", listOf(LineSection.SERVICES), listOf("Подключить услугу" to { addService() })) {
            val list = s.services
            if (list == null) empty("Услуги ещё не загружены")
            else if (list.isEmpty()) empty("Подключённых услуг нет")
            list.orEmpty().sortedByDescending { it.fee ?: 0.0 }.forEach { p ->
                item(p.name, listOfNotNull(p.code, p.since?.let { "с ${LineFormat.date(it)}" }, p.until?.let { "до ${LineFormat.date(it)}" })
                    .joinToString(" · "), p.feeText?.let { it to R.color.neutral }) { serviceMenu(p) }
            }
        }

        // Переадресация
        card("Переадресация", listOf(LineSection.FORWARDING), listOf("Добавить" to { addForwarding() })) {
            val list = s.forwarding
            if (list == null) empty("Переадресация ещё не загружена")
            else if (list.isEmpty()) empty("Переадресация не настроена")
            list.orEmpty().forEach { f ->
                item(f.title, SimFormat.phone(f.address) + (f.timer?.takeIf { it > 0 }?.let { " · через $it с" } ?: "")) {
                    LineDialogs.confirm(this@LineDetailActivity, "Отключить переадресацию", "«${f.title}» на ${SimFormat.phone(f.address)}", "Отключить") {
                        mts("Отправляю заявку…", { LineActions.setForwarding(this@LineDetailActivity, msisdn, f, false) }) { ack -> sent(ack.id) }
                    }
                }
            }
        }

        // Роуминг
        card("Где номер", listOf(LineSection.LOCATION)) {
            val loc = s.location
            when {
                loc == null -> empty("Сведения ещё не загружены")
                loc.abroad -> item("За границей: ${loc.countryName ?: loc.alpha2}", loc.date?.let { "по данным на ${LineFormat.dateTime(it)}" }, "Роуминг" to R.color.sent)
                else -> item("В России", loc.date?.let { "по данным на ${LineFormat.dateTime(it)}" }, "Дома" to R.color.ok)
            }
        }

        // Комментарии
        card("Комментарии в МТС", listOf(LineSection.COMMENT, LineSection.ACCOUNT_COMMENT)) {
            row("На номере", s.comment?.ifBlank { null } ?: "нет") {
                LineDialogs.text(this@LineDetailActivity, "Комментарий на номере", "Комментарий", s.comment.orEmpty(), LineApi.MAX_COMMENT, allowEmpty = true) { t ->
                    mts("Сохраняю…", { LineActions.setComment(this@LineDetailActivity, listOf(msisdn), t) }) { load(); toast("Комментарий сохранён") }
                }
            }
            if (accountNo.isNotEmpty()) row("На лицевом счёте", s.accountComment?.ifBlank { null } ?: "нет") {
                LineDialogs.text(this@LineDetailActivity, "Комментарий на лицевом счёте", "Комментарий", s.accountComment.orEmpty(), LineApi.MAX_ACCOUNT_COMMENT, allowEmpty = true) { t ->
                    mts("Сохраняю…", { LineActions.setAccountComment(this@LineDetailActivity, msisdn, accountNo, t) }) { load(); toast("Комментарий сохранён") }
                }
            }
            addView(v.hint("Нажмите, чтобы изменить. Комментарии видны и в личном кабинете МТС Бизнес."))
        }

        // Корпоративный бюджет
        val rules: List<BudgetRule> = s.budget.orEmpty() + s.accountBudget.orEmpty()
        if (s.budget != null || s.accountBudget != null) card("Корпоративный бюджет", listOf(LineSection.BUDGET, LineSection.ACCOUNT_BUDGET)) {
            if (rules.isEmpty()) empty("Правил списания нет")
            rules.forEach { r ->
                item(r.title, listOfNotNull(r.subTitle, r.limit?.let { "лимит $it" }, r.remainder?.let { "осталось $it" },
                    r.to?.let { "до ${LineFormat.date(it)}" }).joinToString(" · "))
            }
        }

        // Детализация
        card("Детализация", emptyList(), listOf(
            "За сутки" to { statement(1) }, "За 7 дней" to { statement(7) }, "За 30 дней" to { statement(30) }, "На email" to { orderCalls() },
        )) {
            addView(v.hint("Звонки, SMS, интернет и списания. Загружается по запросу и на телефоне не хранится."))
        }

        // Заявки
        card("Заявки в МТС", emptyList(), listOf("Проверить статусы" to {
            mts("Проверяю заявки…", { LineSync.pollRequests(this) }) { load() }
        })) {
            if (requests.isEmpty()) empty("Изменений через приложение ещё не было")
            requests.forEach { r ->
                val color = when { r.done -> R.color.ok; r.failed -> R.color.bad; r.status == LineRequest.STATUS_SENT -> R.color.sent; r.pending -> R.color.waiting; else -> R.color.neutral }
                item(r.title, listOfNotNull(SimFormat.time(r.createdAt), r.eventId.takeIf { it.isNotEmpty() }?.let { "№ $it" }, r.error.ifBlank { null })
                    .joinToString(" · "), r.statusTitle to color)
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("МТС", text))
        toast("Скопировано: $text")
    }

    private fun sent(id: String?) {
        load()
        Snackbar.make(content, if (id != null) "Заявка отправлена в МТС (№ $id). Статус проверится сам." else "Заявка отправлена в МТС",
            Snackbar.LENGTH_LONG).show()
    }

    // ---- Действия ----

    private fun voluntaryBlock(on: Boolean) {
        LineDialogs.confirm(this, if (on) "Включить добровольную блокировку?" else "Снять добровольную блокировку?",
            if (on) "Звонки, SMS и интернет на номере ${SimFormat.phone(msisdn)} перестанут работать до снятия блокировки."
            else "Номер ${SimFormat.phone(msisdn)} снова сможет звонить и выходить в интернет.",
            if (on) "Заблокировать" else "Снять") {
            mts("Отправляю заявку…", { LineActions.setVoluntaryBlock(this, msisdn, on) }) { sent(it.id) }
        }
    }

    private fun serviceMenu(p: ProductItem) {
        val canDelete = p.actions.isEmpty() || p.actions.any { it.equals("DELETE", true) }
        val text = listOfNotNull(
            "Код: ${p.code}", p.feeText?.let { "Плата: $it" }, p.since?.let { "Подключена: ${LineFormat.dateTime(it)}" },
            p.until?.let { "Отключится: ${LineFormat.date(it)}" }, p.status?.let { "Статус: $it" },
            if (!canDelete) "МТС не разрешает отключить эту услугу через API." else null,
        ).joinToString("\n")
        val b = MaterialAlertDialogBuilder(this).setTitle(p.name).setMessage(text).setPositiveButton("Закрыть", null)
        if (canDelete) b.setNegativeButton("Отключить") { _, _ ->
            LineDialogs.confirm(this, "Отключить услугу?", "«${p.name}» на номере ${SimFormat.phone(msisdn)}", "Отключить") {
                mts("Отправляю заявку…", { LineActions.removeService(this, msisdn, p) }) { sent(it.id) }
            }
        }
        b.show()
    }

    /** Список с поиском (каталог услуг или тарифов может быть длинным). */
    private fun <T> pick(title: String, items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
        if (items.isEmpty()) { LineDialogs.message(this, title, "МТС не предложил вариантов для этого номера"); return }
        val labels = items.map(label)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels.toMutableList())
        val searchField = ui.field("Поиск", Ui.Kind.TEXT)
        val listView = ListView(this).apply { this.adapter = adapter }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(v.px(16), 0, v.px(16), 0)
            addView(searchField.layout)
            addView(listView, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5).toInt()))
        }
        val d = MaterialAlertDialogBuilder(this).setTitle("$title · ${items.size}").setView(col).setNegativeButton("Отмена", null).create()
        var visible = items
        searchField.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().lowercase()
                visible = items.filter { label(it).lowercase().contains(q) }
                adapter.clear(); adapter.addAll(visible.map(label)); adapter.notifyDataSetChanged()
            }
        })
        listView.setOnItemClickListener { _, _, pos, _ -> d.dismiss(); onPick(visible[pos]) }
        d.show()
    }

    private fun addService() {
        mts("Загружаю доступные услуги…", { LineActions.availableServices(this, msisdn) }) { list ->
            val connected = snap?.services.orEmpty().map { it.code }.toSet()
            pick("Подключить услугу", list.filter { it.code !in connected }, { p -> p.name + (p.feeText?.let { " · $it" } ?: "") }) { p ->
                mts("МТС проверяет возможность подключения…", { LineActions.validateService(this, msisdn, p.code) }) { st ->
                    val verdict = when {
                        st == null -> "МТС ещё проверяет возможность подключения. Можно отправить заявку — если подключить нельзя, МТС её отклонит."
                        st.failed -> "МТС не разрешает подключить услугу: ${listOfNotNull(st.errorCode, st.errorDescription).joinToString(" ").ifBlank { st.title }}"
                        else -> "Проверка пройдена: услугу можно подключить."
                    }
                    if (st?.failed == true) { LineDialogs.message(this, p.name, verdict); return@mts }
                    LineDialogs.confirm(this, "Подключить «${p.name}»?", verdict + (p.feeText?.let { "\n\nПлата: $it" } ?: ""), "Подключить") {
                        mts("Отправляю заявку…", { LineActions.addService(this, msisdn, p) }) { sent(it.id) }
                    }
                }
            }
        }
    }

    private fun changeTariff() {
        mts("Загружаю доступные тарифы…", { LineActions.availableTariffs(this, msisdn) }) { list ->
            pick("Сменить тариф", list, { t -> t.name + (t.price?.let { p -> " · ${Money.format(p)}" + (Money.unit(t.unit)?.let { " $it" } ?: "") } ?: "") }) { t: AvailableTariff ->
                mts("Проверяю возможность смены…", { LineActions.validateTariff(this, msisdn, t) }) { ack ->
                    LineDialogs.confirm(this, "Перейти на «${t.name}»?",
                        "Номер ${SimFormat.phone(msisdn)}." + (ack.message?.let { "\nОтвет МТС на проверку: $it" } ?: "") +
                            "\n\nПри смене тарифа часть услуг может отключиться, остатки пакетов — сгореть.", "Сменить") {
                        mts("Отправляю заявку…", { LineActions.changeTariff(this, msisdn, t) }) { sent(it.id) }
                    }
                }
            }
        }
    }

    private fun addForwarding() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(v.px(24), v.px(4), v.px(24), 0) }
        var type = "CFU"
        var timer = 20
        col.addView(v.label("Когда переадресовывать", 4))
        val tg = v.chipGroup(single = true, required = true)
        Forwarding.TYPES.forEach { (code, title) -> tg.addView(v.chip(title, checked = code == type).apply { setOnClickListener { type = code } }) }
        col.addView(tg)
        val number = ui.field("На номер", Ui.Kind.PHONE)
        col.addView(number.layout)
        col.addView(v.label("Ждать ответа (для «Если нет ответа»)"))
        val wg = v.chipGroup(single = true, required = true)
        listOf(5, 10, 15, 20, 25, 30).forEach { s -> wg.addView(v.chip("$s с", checked = s == timer).apply { setOnClickListener { timer = s } }) }
        col.addView(wg)
        val d = MaterialAlertDialogBuilder(this).setTitle("Переадресация").setView(col)
            .setPositiveButton("Включить", null).setNegativeButton("Отмена", null).create()
        d.setOnShowListener {
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val to = PersonalDataApi.normalizeMsisdn(number.value)
                if (to == null) { number.error = "Номер в формате +7 XXX XXX-XX-XX"; return@setOnClickListener }
                d.dismiss()
                val rule = Forwarding(type, to, if (type == "CFNRY") timer else null, "Regular")
                mts("Отправляю заявку…", { LineActions.setForwarding(this, msisdn, rule, true) }) { sent(it.id) }
            }
        }
        d.show()
    }

    private fun promised(accountNo: String) {
        mts("Узнаю условия…", { LineActions.promisedParams(this, msisdn, accountNo) }) { p: PromisedPayment ->
            if (!p.available) {
                LineDialogs.message(this, "Обещанный платёж", "Сейчас недоступен" +
                    (p.quantity?.let { q -> " (использовано $q из ${p.maxQuantity ?: "?"})" } ?: "") + "." +
                    (p.amount?.takeIf { it > 0 }?.let { "\nДействует обещанный платёж ${Money.format(it)}" + (p.dateTo?.let { d -> " до ${LineFormat.date(d)}" } ?: "") } ?: ""))
                return@mts
            }
            val max = (p.maxAmount ?: 1000.0).toInt()
            val amounts = listOf(100, 200, 300, 500, 1000, 1500, 2000, 3000, 5000).filter { it <= max }.ifEmpty { listOf(max) }
            pick("Сумма обещанного платежа", amounts, { a -> Money.formatInt(a) + (p.priceFor(a.toDouble())?.let { " · комиссия ${Money.format(it)}" } ?: "") }) { a ->
                LineDialogs.confirm(this, "Обещанный платёж ${Money.formatInt(a)}",
                    "На лицевой счёт $accountNo." + (p.priceFor(a.toDouble())?.let { "\nКомиссия: ${Money.format(it)}" } ?: ""), "Подключить") {
                    mts("Отправляю…", { LineActions.setPromised(this, msisdn, accountNo, a) }) { sent(it.id) }
                }
            }
        }
    }

    private fun payments(accountNo: String) {
        mts("Загружаю платежи за 90 дней…", { LineActions.payments(this, msisdn, accountNo.ifEmpty { null }, 90) }) { list ->
            if (list.isEmpty()) { LineDialogs.message(this, "Платежи", "За 90 дней платежей нет"); return@mts }
            MaterialAlertDialogBuilder(this).setTitle("Платежи за 90 дней · ${list.size}")
                .setItems(list.map { p ->
                    "${LineFormat.dateTime(p.date)}  ${p.amount?.let { Money.format(it) } ?: "—"}" +
                        listOfNotNull(p.form, p.type, p.note).filter { it.isNotBlank() }.joinToString(" · ", prefix = "\n").ifBlank { "" }
                }.toTypedArray(), null)
                .setPositiveButton("Закрыть", null).show()
        }
    }

    private fun statement(days: Long) {
        mts("Загружаю детализацию…", { LineActions.statement(this, msisdn, days) }) { list ->
            if (list.isEmpty()) { LineDialogs.message(this, "Детализация", "Событий нет"); return@mts }
            val total = list.mapNotNull { it.amount }.sum()
            MaterialAlertDialogBuilder(this).setTitle("Детализация · ${list.size} · ${Money.format(total)}")
                .setItems(list.take(500).map { e ->
                    "${LineFormat.dateTime(e.date)}  ${e.label ?: e.type ?: ""}" +
                        listOfNotNull(e.party?.let { SimFormat.phone(it) }, e.units, e.amount?.takeIf { it != 0.0 }?.let { Money.format(it) })
                            .joinToString(" · ", prefix = "\n")
                }.toTypedArray(), null)
                .setPositiveButton("Закрыть", null).show()
        }
    }

    private fun orderCalls() {
        LineDialogs.document(this, "Детализация на email", withPeriod = true, formats = listOf("PDF", "HTML", "XML")) { email, from, to, format ->
            mts("Заказываю…", { LineActions.orderCalls(this, listOf(msisdn), from, to, format, email) }) { sent(it.firstOrNull()?.id) }
        }
    }

    private fun orderBill() {
        LineDialogs.document(this, "Счёт закрытого периода на email", withPeriod = true, formats = listOf("PDF", "HTML", "XML")) { email, from, to, format ->
            mts("Заказываю…", { LineActions.orderBill(this, msisdn, from, to, format, email) }) { sent(it.id) }
        }
    }

    private fun balanceReport() {
        LineDialogs.document(this, "Отчёт по балансу на email", withPeriod = false, formats = listOf("PDF")) { email, _, _, _ ->
            mts("Заказываю…", { LineActions.orderBalanceReport(this, listOf(msisdn), email) }) { sent(it.firstOrNull()?.id) }
        }
    }
}
