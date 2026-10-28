package ru.mycheckin.scanner

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import ru.mycheckin.passport.mts.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал работы: что приложение делало, что отправило в МТС, куда и что получило.
 * Нажатие на запись раскрывает подробности и все обращения к МТС; нажатие на обращение —
 * полный запрос и ответ.
 */
class JournalActivity : AppCompatActivity() {

    companion object {
        /** Показать записи только по этому номеру. */
        const val EXTRA_MSISDN = "msisdn"
        private val TIME = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru"))
        private val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }

    private enum class Filter(val label: String) { ALL("Все"), ERRORS("Ошибки и внимание"), MTS("Обмен с МТС"), CARDS("Карточки и скан") }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var search: TextInputEditText
    private lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: View

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = LogAdapter()
    private var all: List<LogEntry> = emptyList()
    private var filter = Filter.ALL
    private val msisdn by lazy { intent.getStringExtra(EXTRA_MSISDN) }
    private val expanded = HashSet<Long>()
    private val exchangeCache = HashMap<Long, List<LoggedExchange>>()

    private val onJournalChanged: () -> Unit = {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 1000)
    }
    private val reloadRunnable = Runnable { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        toolbar = findViewById(R.id.toolbar)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        refresh = findViewById(R.id.refresh)
        empty = findViewById(R.id.empty)

        msisdn?.let { toolbar.title = "Журнал: ${SimFormat.phone(it)}" }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_share -> share()
                R.id.action_clear -> askClear()
            }
            true
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // Записи обновляются на лету (идёт загрузка) — без мигания при изменении.
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        refresh.setColorSchemeColors(MaterialColors.getColor(refresh, com.google.android.material.R.attr.colorPrimary))
        refresh.setOnRefreshListener { reload() }

        val chips = findViewById<ChipGroup>(R.id.filters)
        for (f in Filter.entries) chips.addView(Chip(this).apply {
            text = f.label; isCheckable = true; isChecked = f == filter; tag = f; setEnsureMinTouchTargetSize(false)
        })
        chips.setOnCheckedStateChangeListener { g, ids ->
            filter = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as Filter } ?: Filter.ALL
            apply()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { main.removeCallbacks(applyRunnable); main.postDelayed(applyRunnable, 150) }
        })
    }

    private val applyRunnable = Runnable { apply() }

    override fun onStart() {
        super.onStart()
        Journal.addListener(onJournalChanged)
        reload()
    }

    override fun onStop() {
        super.onStop()
        Journal.removeListener(onJournalChanged)
    }

    override fun finish() {
        super.finish()
        closeScreenAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun reload() {
        if (io.isShutdown) return
        val openIds = expanded.toList()   // копия в главном потоке: набор меняется по нажатиям
        io.execute {
            val loaded = runCatching {
                Journal.entries(this, msisdn) to openIds.associateWith { Journal.exchanges(this, it) }
            }.getOrNull() ?: return@execute
            val (entries, fresh) = loaded
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refresh.isRefreshing = false
                exchangeCache.putAll(fresh)
                all = entries
                apply()
            }
        }
    }

    private fun apply() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val qDigits = q.filter { it.isDigit() }
        val shown = all.filter { e ->
            val byFilter = when (filter) {
                Filter.ALL -> true
                Filter.ERRORS -> e.level == LogLevel.ERROR || e.level == LogLevel.WARN
                Filter.MTS -> e.category.isMts || e.exchangeCount > 0
                Filter.CARDS -> e.category == LogCategory.CARD || e.category == LogCategory.SCAN || e.category == LogCategory.EXPORT
            }
            byFilter && (q.isEmpty() || "${e.title}\n${e.details}\n${e.account}".lowercase().contains(q) ||
                (qDigits.length >= 3 && e.msisdn.contains(qDigits)))
        }
        adapter.submitList(shown)
        toolbar.subtitle = if (shown.size == all.size) "Записей: ${all.size}" else "Показано ${shown.size} из ${all.size}"
        findViewById<TextView>(R.id.empty_text).text = if (all.isEmpty()) "Записей пока нет.\nЗдесь появится всё, что делает приложение." else "Ничего не найдено"
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- Действия ----

    private fun share() {
        val entries = adapter.currentList
        if (entries.isEmpty()) return
        io.execute {
            val text = Journal.exportText(this, entries)
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val file = File(dir, "journal-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            runOnUiThread {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Отправить журнал"))
            }
        }
    }

    private fun askClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Очистить журнал?")
            .setMessage("Удалятся все записи и сохранённые обмены с МТС. Карточки SIM не затрагиваются.")
            .setPositiveButton("Очистить") { _, _ -> io.execute { Journal.clear(this) } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showExchange(x: LoggedExchange) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun header(t: String) = box.addView(TextView(this).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(0, pad, 0, pad / 3)
        })
        fun mono(t: String) = box.addView(HorizontalScrollView(this).apply {
            addView(TextView(this@JournalActivity).apply {
                text = t; textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            })
        })
        fun plain(t: String) = box.addView(TextView(this).apply { text = t; textSize = 13f; setTextIsSelectable(true) })

        header("Куда")
        plain("${x.method} ${x.url}")
        plain("${Journal.describe(x)} · ${SimFormat.time(x.time)} · попытка ${x.attempt}")
        header("Отправлено")
        mono(x.requestHeaders.ifEmpty { "(без заголовков)" })
        if (x.requestBody.isNotEmpty()) { box.addView(View(this).apply { minimumHeight = pad / 2 }); mono(Json.pretty(x.requestBody)) }
        header("Получено")
        plain(when {
            x.code == null -> "Ответа нет: ${x.error}"
            else -> "HTTP ${x.code} за ${x.durationMs} мс" + (x.retryAfterMs?.let { " · МТС попросил подождать ${it / 1000.0} с, запрос повторён" } ?: "")
        })
        Journal.summary(x)?.let { plain("Итог: $it") }
        if (x.responseBody.isNotEmpty()) mono(Json.pretty(x.responseBody))
        box.addView(TextView(this).apply {
            text = "Пароль и токены в журнал не записываются (показаны как •••)."
            textSize = 11f; setPadding(0, pad, 0, 0)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(Journal.describe(x))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Копировать") { _, _ ->
                val text = "${x.method} ${x.url}\n${x.requestHeaders}\n\n${x.requestBody}\n\n→ ${x.code ?: x.error} (${x.durationMs} мс)\n${x.responseBody}"
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("МТС", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---- Список ----

    inner class LogAdapter : ListAdapter<LogEntry, LogAdapter.VH>(DIFF) {
        private var lastAnimated = -1
        private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card = v as MaterialCardView
            val box: LinearLayout = v.findViewById(R.id.box)
            val dot: View = v.findViewById(R.id.dot)
            val title: TextView = v.findViewById(R.id.title)
            val time: TextView = v.findViewById(R.id.time)
            val meta: TextView = v.findViewById(R.id.meta)
            val details: TextView = v.findViewById(R.id.details)
            val exchanges: LinearLayout = v.findViewById(R.id.exchanges)
            val chevron: ImageView = v.findViewById(R.id.chevron)
            var pulse: ObjectAnimator? = null
            var boundId = 0L
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = getItem(position)
            val color = ContextCompat.getColor(h.itemView.context, e.level.color)
            h.dot.backgroundTintList = ColorStateList.valueOf(color)
            h.pulse?.cancel(); h.dot.alpha = 1f
            if (e.level == LogLevel.RUNNING) {
                h.pulse = ObjectAnimator.ofFloat(h.dot, View.ALPHA, 1f, 0.25f).apply {
                    duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
                }
            }
            h.card.strokeColor = if (e.level == LogLevel.ERROR) color
            else MaterialColors.getColor(h.card, com.google.android.material.R.attr.colorOutlineVariant)
            h.title.text = e.title
            h.time.text = TIME.format(Date(e.time))
            h.meta.text = buildList {
                add(e.category.label)
                if (e.msisdn.isNotEmpty()) add(SimFormat.phone(e.msisdn))
                if (e.account.isNotEmpty()) add("доступ «${e.account}»")
                if (e.exchangeCount > 0) add("обращений к МТС: ${e.exchangeCount}")
                if (e.finishedAt > e.time) add(duration(e.finishedAt - e.time))
                if (e.level == LogLevel.RUNNING) add("выполняется…")
            }.joinToString(" · ")
            h.details.text = e.details
            h.details.visibility = if (e.details.isEmpty()) View.GONE else View.VISIBLE
            val open = e.id in expanded
            h.boundId = e.id
            h.chevron.animate().cancel()
            h.chevron.rotation = if (open) 180f else 0f
            h.chevron.visibility = if (e.details.lines().size > 3 || e.exchangeCount > 0 || e.details.length > 160) View.VISIBLE else View.INVISIBLE
            applyExpanded(h, e, open)
            h.itemView.setOnClickListener { toggle(h, e) }

            if (position > lastAnimated) {
                lastAnimated = position
                val v = h.itemView
                v.alpha = 0f; v.translationY = 16f * resources.displayMetrics.density
                v.animate().alpha(1f).translationY(0f).setStartDelay((position.coerceAtMost(10) * 25).toLong())
                    .setDuration(260).setInterpolator(ease).start()
            }
        }

        override fun onViewRecycled(h: VH) {
            h.pulse?.cancel(); h.pulse = null
            h.boundId = 0L
            h.itemView.animate().cancel(); h.itemView.alpha = 1f; h.itemView.translationY = 0f
        }

        /**
         * Раскрыть/свернуть запись прямо в карточке. Переходы — только внутри карточки:
         * анимация всего RecyclerView при живом обновлении списка приводила к падению.
         */
        private fun toggle(h: VH, e: LogEntry) {
            val open = !expanded.remove(e.id)
            if (open) expanded += e.id
            TransitionManager.beginDelayedTransition(h.box, AutoTransition().setDuration(200))
            applyExpanded(h, e, open)
            h.chevron.animate().rotation(if (open) 180f else 0f).setDuration(200).start()
            if (open && e.exchangeCount > 0 && e.id !in exchangeCache) {
                io.execute {
                    val xs = runCatching { Journal.exchanges(this@JournalActivity, e.id) }.getOrDefault(emptyList())
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        exchangeCache[e.id] = xs
                        // Карточка могла уйти с экрана или переиспользоваться — обновляем, только если она та же.
                        val pos = currentList.indexOfFirst { it.id == e.id }
                        val holder = if (pos >= 0) list.findViewHolderForAdapterPosition(pos) as? VH else null
                        if (holder != null && holder.boundId == e.id) {
                            TransitionManager.beginDelayedTransition(holder.box, AutoTransition().setDuration(180))
                            applyExpanded(holder, e, e.id in expanded)
                        }
                    }
                }
            }
        }

        private fun applyExpanded(h: VH, e: LogEntry, open: Boolean) {
            h.details.maxLines = if (open) Int.MAX_VALUE else 3
            h.details.setTextIsSelectable(open)
            if (open) h.details.setOnClickListener(null) else h.details.setOnClickListener { toggle(h, e) }
            bindExchanges(h, e, open)
        }

        private fun bindExchanges(h: VH, e: LogEntry, open: Boolean) {
            h.exchanges.removeAllViews()
            val xs = exchangeCache[e.id]
            if (!open || e.exchangeCount == 0) { h.exchanges.visibility = View.GONE; return }
            h.exchanges.visibility = View.VISIBLE
            val ctx = h.itemView.context
            val d = resources.displayMetrics.density
            h.exchanges.addView(TextView(ctx).apply {
                text = "Обращения к МТС"
                textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
            })
            if (xs == null) {
                h.exchanges.addView(TextView(ctx).apply { text = "Загружаю…"; textSize = 12f })
                return
            }
            for (x in xs) h.exchanges.addView(exchangeRow(x))
        }

        private fun exchangeRow(x: LoggedExchange): View {
            val ctx = this@JournalActivity
            val d = resources.displayMetrics.density
            val ok = x.code != null && x.code in 200..299
            val color = ContextCompat.getColor(ctx, when {
                ok -> R.color.ok
                x.retryAfterMs != null -> R.color.waiting
                else -> R.color.bad
            })
            return MaterialCardView(ctx).apply {
                radius = 12 * d; cardElevation = 0f; strokeWidth = (1 * d).toInt(); strokeColor = color
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() }
                isClickable = true; isFocusable = true
                setOnClickListener { showExchange(x) }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(TextView(ctx).apply {
                            text = x.code?.toString() ?: "—"
                            textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(android.graphics.Color.WHITE)
                            background = ContextCompat.getDrawable(ctx, R.drawable.bg_pill)
                            backgroundTintList = ColorStateList.valueOf(color)
                            setPadding((8 * d).toInt(), (1 * d).toInt(), (8 * d).toInt(), (1 * d).toInt())
                        })
                        addView(TextView(ctx).apply {
                            text = "${x.method}  ${Journal.describe(x)}"
                            textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                            setPadding((8 * d).toInt(), 0, 0, 0)
                        })
                    })
                    addView(TextView(ctx).apply {
                        text = x.url.substringAfter("://")
                        textSize = 11f; typeface = Typeface.MONOSPACE
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        setPadding(0, (3 * d).toInt(), 0, 0)
                    })
                    addView(TextView(ctx).apply {
                        text = listOfNotNull(
                            "${x.durationMs} мс",
                            if (x.attempt > 1) "попытка ${x.attempt}" else null,
                            x.retryAfterMs?.let { "МТС просит подождать ${it / 1000.0} с" },
                            Journal.summary(x),
                        ).joinToString(" · ")
                        textSize = 12f
                        setPadding(0, (2 * d).toInt(), 0, 0)
                    })
                })
            }
        }
    }

    private fun duration(ms: Long): String = when {
        ms < 1000 -> "$ms мс"
        ms < 60_000 -> "%.1f с".format(ms / 1000.0)
        else -> "${ms / 60_000} мин ${(ms / 1000) % 60} с"
    }
}
