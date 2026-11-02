package ru.mycheckin.scanner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ru.mycheckin.passport.mts.LineApi
import ru.mycheckin.passport.mts.LineCategory
import ru.mycheckin.passport.mts.LineChange
import ru.mycheckin.passport.mts.LineSchedule
import ru.mycheckin.passport.mts.LinePlanner
import ru.mycheckin.passport.mts.LineScope
import ru.mycheckin.passport.mts.LineSection
import ru.mycheckin.passport.mts.LineSnapshot
import ru.mycheckin.passport.mts.MtsApiException
import ru.mycheckin.passport.mts.RateLimiter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Настройки сведений о номерах. */
object LinePrefs {
    private fun p(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("lines", Context.MODE_PRIVATE)

    /** Периоды обновления по категориям. */
    fun schedule(c: Context): LineSchedule = LineSchedule.fromJson(p(c).getString("schedule", null))
    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    /** Сколько вызовов в минуту может тратить обычное обновление (остальное — экранам приложения). */
    fun perMinute(c: Context) = p(c).getInt("per_minute", 150).coerceIn(30, 250)
    /** Темп первичной загрузки: быстрее обычного, но с запасом для экранов. */
    fun initialPerMinute(c: Context) = p(c).getInt("initial_per_minute", 220).coerceIn(30, 260)

    fun save(c: Context, schedule: LineSchedule, wifiOnly: Boolean, perMinute: Int, initialPerMinute: Int) {
        p(c).edit().putString("schedule", schedule.toJson()).putBoolean("wifi_only", wifiOnly)
            .putInt("per_minute", perMinute).putInt("initial_per_minute", initialPerMinute).apply()
    }

    fun lastNumbersSync(c: Context) = p(c).getLong("last_numbers_sync", 0)
    fun setLastNumbersSync(c: Context, t: Long) = p(c).edit().putLong("last_numbers_sync", t).apply()

    fun lastRun(c: Context) = p(c).getLong("last_run", 0)
    fun lastRunText(c: Context) = p(c).getString("last_run_text", "").orEmpty()
    fun setLastRun(c: Context, t: Long, text: String) = p(c).edit().putLong("last_run", t).putString("last_run_text", text).apply()

    // ---- Первичная загрузка ----

    enum class Initial { NONE, RUNNING, DONE, FAILED }

    fun initial(c: Context): Initial = runCatching { Initial.valueOf(p(c).getString("initial_status", "NONE")!!) }.getOrDefault(Initial.NONE)
    fun initialPaused(c: Context) = p(c).getBoolean("initial_paused", false)
    fun initialNumbersDone(c: Context) = p(c).getBoolean("initial_numbers_done", false)
    fun initialPasses(c: Context) = p(c).getInt("initial_passes", 0)
    fun initialStarted(c: Context) = p(c).getLong("initial_started", 0)
    fun initialFinished(c: Context) = p(c).getLong("initial_finished", 0)
    fun initialMessage(c: Context) = p(c).getString("initial_message", "").orEmpty()
    fun initialDismissed(c: Context) = p(c).getBoolean("initial_dismissed", false)

    fun startInitial(c: Context, reset: Boolean) {
        val e = p(c).edit().putString("initial_status", Initial.RUNNING.name).putBoolean("initial_paused", false)
            .putBoolean("initial_dismissed", false).putString("initial_message", "")
        if (reset || initial(c) != Initial.RUNNING) e.putInt("initial_passes", 0).putBoolean("initial_numbers_done", false)
            .putLong("initial_started", System.currentTimeMillis())
        e.apply()
    }
    fun setInitialPaused(c: Context, v: Boolean) = p(c).edit().putBoolean("initial_paused", v).apply()
    fun setInitialNumbersDone(c: Context) = p(c).edit().putBoolean("initial_numbers_done", true).apply()
    fun setInitialPasses(c: Context, n: Int) = p(c).edit().putInt("initial_passes", n).apply()
    fun finishInitial(c: Context, status: Initial, message: String) = p(c).edit().putString("initial_status", status.name)
        .putString("initial_message", message).putLong("initial_finished", System.currentTimeMillis()).putBoolean("initial_paused", false).apply()
    fun setInitialMessage(c: Context, m: String) = p(c).edit().putString("initial_message", m).apply()
    fun dismissInitial(c: Context) = p(c).edit().putBoolean("initial_dismissed", true).apply()

    // ---- Прочее ----

    fun filters(c: Context): String? = p(c).getString("filters", null)
    fun setFilters(c: Context, json: String) = p(c).edit().putString("filters", json).apply()
    fun currentFilter(c: Context): String? = p(c).getString("current_filter", null)
    fun setCurrentFilter(c: Context, json: String) = p(c).edit().putString("current_filter", json).apply()

    fun email(c: Context) = p(c).getString("email", "").orEmpty()
    fun setEmail(c: Context, v: String) = p(c).edit().putString("email", v.trim()).apply()

    fun askedNotifications(c: Context) = p(c).getBoolean("asked_notifications", false)
    fun setAskedNotifications(c: Context) = p(c).edit().putBoolean("asked_notifications", true).apply()
}

/**
 * Загрузка и обновление сведений о номерах из МТС Бизнес API.
 *
 * Два режима:
 * - **Первичная загрузка** — один раз, пока не загрузится всё. Сначала список номеров, затем разделы
 *   по всем номерам в порядке важности ([LinePlanner.INITIAL_ORDER]). Временные сбои МТС повторяются
 *   с паузами, при обрыве связи или остановке системой WorkManager продолжает её сам (с уже полученного
 *   ничего не запрашивается повторно), в конце — проверка полноты и догрузка недостающего.
 * - **Обновление** — после первичной загрузки, по расписанию: только то, что устарело по периоду
 *   своей категории ([LineSchedule]).
 *
 * Фон не мешает работе приложения: свой темп ([LinePrefs.perMinute], [LinePrefs.initialPerMinute])
 * поверх общего ограничителя доступа (8/с, 280/мин), который остаётся общим с экранами.
 */
object LineSync {
    private const val WORK_PERIODIC = "lines-periodic"
    private const val WORK_NOW = "lines-now"
    private const val WORK_INITIAL = "lines-initial"
    private const val WORK_POLL = "lines-requests"
    private const val CHANNEL = "lines_sync"
    const val NOTIFICATION_ID = 4101

    /** Полных проходов первичной загрузки, после которых оставшиеся сбои догружает обычное обновление. */
    private const val MAX_FULL_PASSES = 4
    /** Без уведомления (система не разрешила) работа WorkManager ограничена 10 минутами — заканчиваем раньше. */
    private const val BACKGROUND_BUDGET_MS = 8 * 60_000L + 30_000L
    /** Паузы перед повтором раздела при временном сбое МТС или связи. */
    private val RETRY_MS = longArrayOf(3_000, 10_000, 30_000)

    data class State(
        val running: Boolean = false,
        val initial: Boolean = false,
        val phase: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val startedAt: Long = 0,
        val stopping: Boolean = false,
    )

    @Volatile var state = State(); private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val runLock = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var lastNotify = 0L
    private val limiters = HashMap<Int, RateLimiter>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { runCatching { it() } } }

    private fun setState(s: State) { state = s; changed() }

    fun initialDone(context: Context): Boolean = LinePrefs.initial(context) == LinePrefs.Initial.DONE

    // ---- Расписание ----

    private fun network(context: Context) = if (LinePrefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED

    /**
     * Привести расписание в соответствие с настройками. Вызывается при запуске приложения и после
     * изменения настроек. Пока первичная загрузка не завершена, обычное обновление не запускается,
     * а сама загрузка (если идёт и не на паузе) поставлена в очередь — переживает перезапуск телефона.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!MtsService.hasAccounts(context)) { wm.cancelUniqueWork(WORK_PERIODIC); return }
        if (!initialDone(context)) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            if (LinePrefs.initial(context) == LinePrefs.Initial.RUNNING && !LinePrefs.initialPaused(context)) enqueueInitial(context, ExistingWorkPolicy.KEEP)
            return
        }
        val every = LinePrefs.schedule(context).runEveryMinutes
        if (every <= 0) { wm.cancelUniqueWork(WORK_PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<LineSyncWorker>(every.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network(context)).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(minOf(every, 30).toLong(), TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(LineSyncWorker.KEY_MODE, LineSyncWorker.MODE_PERIODIC).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Следующая часть первичной загрузки — сразу после текущей, без растущих пауз повторов. */
    fun continueInitial(context: Context) = enqueueInitial(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueueInitial(context: Context, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<LineSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(LineSyncWorker.KEY_MODE, LineSyncWorker.MODE_INITIAL).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_INITIAL, policy, req)
    }

    /**
     * Начать или продолжить первичную загрузку. [reset] — начать заново (счётчики проходов с нуля;
     * уже загруженное не запрашивается повторно, только недостающее).
     */
    fun startInitial(context: Context, reset: Boolean = false) {
        stopRequested.set(false)
        LinePrefs.startInitial(context, reset)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        enqueueInitial(context, if (reset) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        if (!state.running) setState(State(running = true, initial = true, phase = "Ждёт подключения к сети…", startedAt = System.currentTimeMillis()))
    }

    /**
     * Обновить сейчас, в фоне, с уведомлением о ходе. [msisdns] — только эти номера (null — все),
     * [force] — запросить всё заново, даже свежее. Пока нет первичной загрузки — продолжает её.
     * false — обновление уже идёт.
     */
    fun refreshNow(context: Context, msisdns: Collection<String>? = null, force: Boolean = false): Boolean {
        if (state.running) return false
        if (!initialDone(context) && msisdns == null && !force) { startInitial(context); return true }
        stopRequested.set(false)
        val data = Data.Builder()
            .putString(LineSyncWorker.KEY_MODE, LineSyncWorker.MODE_NOW)
            .putBoolean(LineSyncWorker.KEY_FORCE, force)
        msisdns?.let { data.putStringArray(LineSyncWorker.KEY_NUMBERS, it.toTypedArray()) }
        val req = OneTimeWorkRequestBuilder<LineSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data.build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
        setState(state.copy(running = true, initial = false, phase = "Ждёт подключения к сети…", done = 0, total = 0, startedAt = System.currentTimeMillis()))
        return true
    }

    /** Остановить идущее обновление. Первичная загрузка ставится на паузу — полученное сохраняется. */
    fun stop(context: Context) {
        stopRequested.set(true)
        if (LinePrefs.initial(context) == LinePrefs.Initial.RUNNING) LinePrefs.setInitialPaused(context, true)
        if (!runLock.get()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_INITIAL)
            stopRequested.set(false)
            setState(State())
        } else setState(state.copy(stopping = true, phase = "Останавливаю…"))
    }

    /** Проверить заявки через [minutes] минут (после отправки изменения в МТС). */
    fun schedulePoll(context: Context, minutes: Long = 1) {
        val req = OneTimeWorkRequestBuilder<LineSyncWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(LineSyncWorker.KEY_MODE, LineSyncWorker.MODE_REQUESTS).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_POLL, ExistingWorkPolicy.REPLACE, req)
    }

    // ---- Клиенты ----

    /** Доступ и API для номера: для действий пользователя (полный журнал обращений). */
    fun api(context: Context, msisdn: String): Pair<LineApi, MtsAccount> {
        val sim = SimDb.get(context).byMsisdn(msisdn) ?: SimRecord(msisdn = msisdn)
        val acc = SimActions.accountFor(context, sim)
        return LineApi(MtsService.client(context, acc)) to acc
    }

    /** Темп фона. Счётчик за месяц ведёт общий ограничитель доступа; этот — только скорость. */
    @Synchronized
    private fun limiter(perMinute: Int): RateLimiter =
        limiters.getOrPut(perMinute) { RateLimiter(perSecond = 5, perMinute = perMinute, perMonth = Long.MAX_VALUE) }

    // ---- Запросы ----

    data class Summary(val calls: Int, val errors: Int, val numbers: Int, val stopped: Boolean, val message: String)

    /** Почему обработка заданий остановилась. */
    private enum class Stop { USER, BUDGET, NETWORK, LIMIT }

    private class StopRun(val reason: Stop, message: String) : Exception(message)

    /** Временный сбой: связь, таймаут, перегрузка МТС — стоит повторить. */
    private fun transient(e: Exception): Boolean = when (e) {
        is java.io.IOException -> true
        is MtsApiException -> (e.response?.code ?: 0).let { it >= 500 || it == 429 || it == 408 }
        else -> false
    }

    private fun isAuthProblem(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return e is IllegalArgumentException || m.contains("логин и пароль") || m.contains("токен", ignoreCase = true)
    }

    private fun sleep(ms: Long, isStopped: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (isStopped()) return
            Thread.sleep(minOf(500L, until - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }

    /**
     * Запросить раздел и сохранить. Временные сбои повторяются [retries] раз с паузами; если связи
     * так и нет — [StopRun] (обработка остановится и продолжится позже). Ответ 4xx — раздел недоступен
     * для номера, это запоминается. false — раздел не получен.
     */
    private fun fetch(store: LineStore, api: LineApi, job: LinePlanner.Job, retries: Int, isStopped: () -> Boolean): Boolean {
        var attempt = 0
        while (true) {
            try {
                if (job.section.bulk) store.putAll(job.section, api.rawBulk(job.section, job.keys))
                else store.put(job.section, job.keys.single(), api.raw(job.section, job.keys.single()))
                return true
            } catch (e: RateLimiter.MonthlyLimitExceeded) {
                throw StopRun(Stop.LIMIT, "Исчерпан месячный лимит вызовов тарифа")
            } catch (e: Exception) {
                if (transient(e)) {
                    if (attempt < retries && !isStopped()) { sleep(RETRY_MS[minOf(attempt, RETRY_MS.size - 1)], isStopped); attempt++; continue }
                    if (e is java.io.IOException) throw StopRun(Stop.NETWORK, "Нет связи с МТС: ${e.message ?: e.javaClass.simpleName}")
                    job.keys.forEach { store.putError(job.section, it, e.message ?: "Сбой МТС", final = false) }
                    return false
                }
                if (isAuthProblem(e)) throw e
                val code = (e as? MtsApiException)?.response?.code
                val final = code != null && code in 400..499
                job.keys.forEach { store.putError(job.section, it, e.message ?: e.javaClass.simpleName, final) }
                return false
            }
        }
    }

    private class Exec(var calls: Int = 0, var errors: Int = 0, var done: Int = 0, var stop: Stop? = null)

    /** Выполнить задания по доступам: общий цикл обычного обновления и первичной загрузки. */
    private fun execute(
        app: Context, plans: List<Pair<MtsAccount, List<LinePlanner.Job>>>, total: Int, initial: Boolean,
        perMinute: Int, retries: Int, deadline: Long, isStopped: () -> Boolean, problems: MutableSet<String>,
        progress: (String, Int, Int) -> Unit,
    ): Exec {
        val store = LineStore.get(app)
        val limiter = limiter(perMinute)
        val x = Exec()
        val stopped = { isStopped() || stopRequested.get() }
        outer@ for ((acc, jobs) in plans) {
            val api = LineApi(MtsService.quietClient(app, acc))
            for ((i, job) in jobs.withIndex()) {
                if (stopped()) { x.stop = Stop.USER; break@outer }
                if (System.currentTimeMillis() > deadline) { x.stop = Stop.BUDGET; break@outer }
                val text = job.section.title + if (job.keys.size == 1) " · " + SimFormat.phone(job.keys[0]) else " · ${job.keys.size} шт."
                try {
                    limiter.acquire()
                    if (!fetch(store, api, job, retries, stopped)) x.errors++
                    x.calls++
                } catch (e: StopRun) {
                    problems += e.message.orEmpty(); x.stop = e.reason; break@outer
                } catch (e: Exception) {
                    // Доступ не принят МТС — остальные разделы этого доступа не запрашиваем.
                    problems += "${acc.title.ifBlank { "Доступ" }}: ${e.message}"
                    x.errors++
                    x.done += jobs.size - i - 1
                    break
                }
                x.done++
                setState(state.copy(running = true, initial = initial, done = x.done, total = total, phase = text))
                progress(text, x.done, total)
            }
        }
        return x
    }

    /** Номера в МТС по доступам: у каждого контракта свой логин. */
    private fun groups(app: Context, only: Set<String>?, problems: MutableSet<String>): List<Pair<MtsAccount, List<SimRecord>>> {
        val sims = SimDb.get(app).all().filter { it.msisdn.length == 11 && (it.inMts || only != null) && (only == null || it.msisdn in only) }
        val out = LinkedHashMap<String, Pair<MtsAccount, MutableList<SimRecord>>>()
        for (s in sims) {
            val acc = runCatching { SimActions.accountFor(app, s) }.getOrElse { problems += it.message.orEmpty(); null } ?: continue
            out.getOrPut(acc.id) { acc to mutableListOf() }.second += s
        }
        return out.values.map { it.first to it.second.toList() }
    }

    /** Насколько полна первичная загрузка — для экрана. Не в главном потоке. */
    fun coverage(context: Context): LinePlanner.Coverage {
        val app = context.applicationContext
        val sims = SimDb.get(app).all().filter { it.inMts && it.msisdn.length == 11 }
        val status = LineStore.get(app).status()
        return LinePlanner.coverage(sims.associate { it.msisdn to it.accountNo }, LinePrefs.schedule(app).sections) { s, k -> status[s to k]?.second }
    }

    // ---- Первичная загрузка ----

    /** RETRY — после паузы (нет связи, сбой); CONTINUE — сразу следующей частью (кончилось время фоновой работы). */
    enum class InitialOutcome { DONE, RETRY, CONTINUE, PAUSED, FAILED }

    private fun looksLikeNetwork(m: String) = listOf("интернет", "не ответил", "соединени", "HTTP 5", "HTTP 429", "связ").any { m.contains(it, true) }

    /**
     * Один проход первичной загрузки (вызывается из [LineSyncWorker]). [foreground] — работа идёт
     * с уведомлением и не ограничена 10 минутами. Возвращает, что делать дальше: всё загружено,
     * повторить позже (WorkManager подождёт сеть и продолжит), пауза пользователя или ошибка доступа.
     */
    fun runInitial(context: Context, isStopped: () -> Boolean, foreground: Boolean, progress: (String, Int, Int) -> Unit): InitialOutcome {
        val app = context.applicationContext
        if (LinePrefs.initial(app) != LinePrefs.Initial.RUNNING) return InitialOutcome.DONE
        if (LinePrefs.initialPaused(app)) return InitialOutcome.PAUSED
        if (!runLock.compareAndSet(false, true)) return InitialOutcome.RETRY
        val started = System.currentTimeMillis()
        val deadline = if (foreground) Long.MAX_VALUE else started + BACKGROUND_BUDGET_MS
        val problems = LinkedHashSet<String>()
        setState(State(running = true, initial = true, phase = "Первичная загрузка: подготовка…", startedAt = started))
        try {
            return Journal.op(app, LogCategory.LINES, "Первичная загрузка сведений о номерах") { op ->
                if (MtsService.accounts(app).none { it.isConfigured }) {
                    LinePrefs.finishInitial(app, LinePrefs.Initial.FAILED, "Не введены логин и пароль для интеграции МТС")
                    op.fail(); return@op InitialOutcome.FAILED
                }

                // 1. Список номеров и SIM — без него не понятно, что загружать.
                if (!LinePrefs.initialNumbersDone(app)) {
                    setState(state.copy(phase = "Список номеров и SIM из МТС…"))
                    try {
                        val r = SimActions.syncNumbers(app) { t, n -> setState(state.copy(phase = if (n > 0) "$t · найдено $n" else t)) }
                        op.line("Список номеров: ${r.result.total}" + if (r.errors.isNotEmpty()) ", не загрузились доступы: ${r.errors.size}" else "")
                        if (r.errors.isNotEmpty()) problems += r.errors
                        LinePrefs.setInitialNumbersDone(app)
                        LinePrefs.setLastNumbersSync(app, System.currentTimeMillis())
                    } catch (e: Exception) {
                        val m = e.message.orEmpty()
                        op.line("Список номеров не загрузился: $m")
                        LinePrefs.setInitialMessage(app, "Список номеров: $m")
                        return@op if (looksLikeNetwork(m)) { op.warn(); InitialOutcome.RETRY }
                        else { LinePrefs.finishInitial(app, LinePrefs.Initial.FAILED, m); op.fail(); InitialOutcome.FAILED }
                    }
                }

                // 2. Разделы по всем номерам, в порядке важности; уже полученное не запрашивается.
                val sections = LinePrefs.schedule(app).sections
                val status = LineStore.get(app).status()
                val groups = groups(app, null, problems)
                val plans = groups.map { (acc, sims) ->
                    acc to LinePlanner.initial(sims.associate { it.msisdn to it.accountNo }, sections) { s, k -> status[s to k]?.second }
                }
                val total = plans.sumOf { it.second.size }
                op.line("Номеров: ${groups.sumOf { it.second.size }}, осталось загрузить: $total, проход ${LinePrefs.initialPasses(app) + 1}")
                val x = if (total == 0) Exec() else execute(app, plans, total, true, LinePrefs.initialPerMinute(app), retries = RETRY_MS.size,
                    deadline = deadline, isStopped = isStopped, problems = problems, progress = progress)
                op.line("Запросов: ${x.calls}, не получено: ${x.errors}, время: ${(System.currentTimeMillis() - started) / 1000} с")
                problems.forEach { op.line(it) }

                when (x.stop) {
                    Stop.USER -> {
                        if (!isStopped()) LinePrefs.setInitialPaused(app, true)
                        LinePrefs.setInitialMessage(app, if (isStopped()) "Прервано системой — продолжится автоматически" else "На паузе")
                        op.warn(); return@op if (isStopped()) InitialOutcome.RETRY else InitialOutcome.PAUSED
                    }
                    Stop.NETWORK -> {
                        LinePrefs.setInitialMessage(app, "Нет связи с МТС — продолжится, когда связь появится")
                        op.warn(); return@op InitialOutcome.RETRY
                    }
                    Stop.BUDGET -> {
                        LinePrefs.setInitialMessage(app, "Загружаю частями — следующая часть сразу за этой")
                        op.line("Время фоновой работы вышло — следующая часть поставлена в очередь")
                        return@op InitialOutcome.CONTINUE
                    }
                    Stop.LIMIT -> {
                        LinePrefs.finishInitial(app, LinePrefs.Initial.FAILED, "Исчерпан месячный лимит вызовов тарифа")
                        op.fail(); return@op InitialOutcome.FAILED
                    }
                    null -> Unit
                }

                // 3. Проверка полноты: что не получено из-за сбоев — ещё один проход (до MAX_FULL_PASSES).
                val cov = coverage(app)
                val passes = LinePrefs.initialPasses(app) + 1
                LinePrefs.setInitialPasses(app, passes)
                val gaps = cov.failed + cov.missing
                if (gaps > 0 && passes < MAX_FULL_PASSES && groups.isNotEmpty()) {
                    LinePrefs.setInitialMessage(app, "Не загрузилось $gaps — повторю через минуту (проход ${passes + 1} из $MAX_FULL_PASSES)")
                    op.warn("Первичная загрузка: осталось $gaps, будет ещё проход"); return@op InitialOutcome.RETRY
                }
                val message = buildString {
                    append("Загружено ${cov.loaded} из ${cov.total}")
                    if (cov.unavailable > 0) append(", недоступно для номеров: ${cov.unavailable}")
                    if (gaps > 0) append(", не удалось: $gaps — догрузится при обычном обновлении")
                }
                LinePrefs.finishInitial(app, LinePrefs.Initial.DONE, message)
                LinePrefs.setLastRun(app, System.currentTimeMillis(), message)
                if (gaps > 0) op.warn("Первичная загрузка завершена с пропусками") else op.ok("Первичная загрузка завершена")
                op.line(message)
                InitialOutcome.DONE
            }
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            LinePrefs.setInitialMessage(app, m)
            return if (looksLikeNetwork(m)) InitialOutcome.RETRY else InitialOutcome.FAILED.also { LinePrefs.finishInitial(app, LinePrefs.Initial.FAILED, m) }
        } finally {
            stopRequested.set(false)
            runLock.set(false)
            setState(State())
            schedule(app)
        }
    }

    // ---- Обычное обновление ----

    /**
     * Обновить устаревшее (вызывается из [LineSyncWorker], не в главном потоке).
     * [only] — только эти номера; [force] — всё заново; [progress] — для уведомления.
     */
    fun run(context: Context, only: Set<String>?, force: Boolean, isStopped: () -> Boolean, deadline: Long = Long.MAX_VALUE,
            progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Summary {
        val app = context.applicationContext
        if (!initialDone(app) && only == null && !force) { startInitial(app); return Summary(0, 0, 0, false, "Идёт первичная загрузка") }
        if (!runLock.compareAndSet(false, true)) return Summary(0, 0, 0, false, "Обновление уже идёт")
        val started = System.currentTimeMillis()
        setState(State(running = true, phase = "Подготовка…", startedAt = started))
        var calls = 0; var errors = 0; var stopped = false; var numbersCount = 0
        val problems = LinkedHashSet<String>()
        try {
            return Journal.op(app, LogCategory.LINES, if (only == null) "Фоновое обновление сведений о номерах" else "Обновление сведений: ${only.size} номеров") { op ->
                if (MtsService.accounts(app).none { it.isConfigured }) throw SimActions.Failure("Не введены логин и пароль для интеграции МТС")
                val schedule = LinePrefs.schedule(app)

                // Список номеров и SIM — по периоду своей категории.
                val numbersEvery = schedule.of(LineCategory.NUMBERS)
                if (only == null && numbersEvery > 0 && started - LinePrefs.lastNumbersSync(app) >= numbersEvery * 60_000L) {
                    setState(state.copy(phase = "Список номеров из МТС…"))
                    runCatching { SimActions.syncNumbers(app) { t, _ -> setState(state.copy(phase = t)) } }
                        .onSuccess { LinePrefs.setLastNumbersSync(app, System.currentTimeMillis()); op.line("Список номеров обновлён: ${it.result.total}") }
                        .onFailure { op.line("Список номеров не обновился: ${it.message}") }
                }

                runCatching { pollRequests(app) }

                val ttl = if (force) LineSection.entries.associateWith { 0L } else schedule.ttl
                val fetched = LineStore.get(app).fetchedAt()
                val now = System.currentTimeMillis()
                val groups = groups(app, only, problems)
                numbersCount = groups.sumOf { it.second.size }
                val plans = groups.map { (acc, sims) ->
                    acc to LinePlanner.plan(sims.associate { it.msisdn to it.accountNo }, ttl, { s, k -> fetched[s to k] }, now, force)
                }
                val total = plans.sumOf { it.second.size }
                op.line("Номеров: $numbersCount, доступов: ${groups.size}, нужно запросов: $total" + if (force) " (всё заново)" else "")
                if (total == 0) {
                    op.ok("Сведения о номерах свежие — запросов не понадобилось")
                    return@op Summary(0, 0, numbersCount, false, "Сведения свежие")
                }
                setState(state.copy(phase = "Обновляю сведения…", total = total))
                val x = execute(app, plans, total, false, LinePrefs.perMinute(app), retries = 1, deadline = deadline,
                    isStopped = isStopped, problems = problems, progress = progress)
                calls = x.calls; errors = x.errors; stopped = x.stop != null
                LineStore.get(app).prune(SimDb.get(app).all().flatMap { listOf(it.msisdn, it.accountNo) }.filter { it.isNotEmpty() }.toSet())
                val message = buildString {
                    append("Запросов: $calls")
                    if (errors > 0) append(", с ошибкой: $errors")
                    if (stopped) append(" · остановлено, продолжится при следующем обновлении")
                }
                op.line(message)
                problems.forEach { op.line(it) }
                op.line("Время: ${(System.currentTimeMillis() - started) / 1000} с, темп — не больше ${LinePrefs.perMinute(app)} вызовов в минуту")
                if (errors > 0 || problems.isNotEmpty()) op.warn()
                Summary(calls, errors, numbersCount, stopped, message)
            }.also { LinePrefs.setLastRun(app, System.currentTimeMillis(), it.message + problems.firstOrNull()?.let { p -> "\n$p" }.orEmpty()) }
        } catch (e: Exception) {
            LinePrefs.setLastRun(app, System.currentTimeMillis(), "Ошибка: ${e.message}")
            return Summary(calls, errors + 1, numbersCount, true, e.message ?: "Ошибка")
        } finally {
            stopRequested.set(false)
            runLock.set(false)
            setState(State())
        }
    }

    /**
     * Обновить один номер сразу (экран номера): все разделы номера и его лицевого счёта,
     * в три потока. Не в главном потоке. Возвращает свежую сводку.
     */
    fun refreshNumber(context: Context, msisdn: String, only: Set<LineSection>? = null): LineSnapshot {
        val app = context.applicationContext
        val sim = SimDb.get(app).byMsisdn(msisdn) ?: SimRecord(msisdn = msisdn)
        val acc = SimActions.accountFor(app, sim)
        val api = LineApi(MtsService.quietClient(app, acc))
        val store = LineStore.get(app)
        val sections = (only ?: LineSection.entries.toSet()).filter { !it.byAccount || sim.accountNo.isNotEmpty() }
        val pool = Executors.newFixedThreadPool(3)
        val failures = mutableListOf<String>()
        try {
            val futures = sections.map { s ->
                pool.submit<Unit> {
                    val key = if (s.byAccount) sim.accountNo else msisdn
                    try {
                        fetch(store, api, LinePlanner.Job(s, listOf(key)), retries = 1) { false }
                    } catch (e: Exception) {
                        synchronized(failures) { failures += e.message ?: e.javaClass.simpleName }
                    }
                }
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdown()
        }
        val errors = store.snapshot(msisdn, sim.accountNo).errors
        Journal.event(app, if (failures.isEmpty() && errors.isEmpty()) LogLevel.OK else LogLevel.WARN, LogCategory.LINES,
            "Сведения о номере из МТС: ${sections.size} разделов",
            (failures.distinct() + errors.map { "${it.key.title}: ${it.value}" }).joinToString("\n"), msisdn = msisdn, account = acc.title)
        failures.firstOrNull()?.let { throw SimActions.Failure(it) }
        changed()
        return store.snapshot(msisdn, sim.accountNo)
    }

    /** Проверить статусы заявок. true — остались невыполненные (проверить ещё раз позже). */
    fun pollRequests(context: Context): Boolean {
        val app = context.applicationContext
        val store = LineStore.get(app)
        val pending = store.pendingRequests()
        if (pending.isEmpty()) return false
        var left = false
        for (r in pending) {
            val age = System.currentTimeMillis() - r.createdAt
            if (age > 3 * 24 * 3_600_000L || r.checks >= 60) {
                store.updateRequest(r.id, LineRequest.STATUS_UNKNOWN, "МТС не сообщил итог заявки")
                continue
            }
            val st = try {
                val sim = SimDb.get(app).byMsisdn(r.msisdn) ?: SimRecord(msisdn = r.msisdn)
                LineApi(MtsService.quietClient(app, SimActions.accountFor(app, sim))).requestStatus(r.msisdn, r.eventId)
            } catch (e: Exception) {
                store.updateRequest(r.id, r.status, e.message.orEmpty()); left = true; continue
            }
            if (st == null) { store.updateRequest(r.id, r.status, ""); left = true; continue }
            val status = st.status.orEmpty()
            store.updateRequest(r.id, status, listOfNotNull(st.errorCode, st.errorDescription).joinToString(" ").trim())
            when {
                st.done -> {
                    val keys = listOf(r.msisdn) + listOf(r.accountNo).filter { it.isNotEmpty() }
                    store.markStale(keys, LineSection.affectedBy(r.kind))
                    runCatching { refreshNumber(app, r.msisdn, LineSection.affectedBy(r.kind)) }
                    Journal.event(app, LogLevel.OK, LogCategory.CHECK, "Заявка выполнена: ${r.title}", "Номер заявки: ${r.eventId}", msisdn = r.msisdn)
                }
                st.failed -> Journal.event(app, LogLevel.ERROR, LogCategory.CHECK, "Заявка отклонена: ${r.title}",
                    listOfNotNull("Номер заявки: ${r.eventId}", st.errorCode?.let { "Код: $it" }, st.errorDescription).joinToString("\n"), msisdn = r.msisdn)
                else -> left = true
            }
        }
        changed()
        return left
    }

    // ---- Уведомление ----

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновление сведений МТС", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ход фонового обновления баланса, услуг и блокировок"
                setShowBadge(false)
            })
        }
    }

    fun notification(context: Context, text: String, done: Int, total: Int, initial: Boolean = false): Notification {
        channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, LinesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_sync)
            .setContentTitle((if (initial) "Первичная загрузка" else "Сведения МТС") + if (total > 0) ": $done из $total" else "")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun foregroundInfo(context: Context, text: String, done: Int, total: Int): ForegroundInfo {
        val n = notification(context, text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    /** Обновить уведомление не чаще раза в секунду. */
    fun updateNotification(context: Context, text: String, done: Int, total: Int, initial: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000 && done < total) return
        lastNotify = now
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text, done, total, initial)) }
    }

    /** Сколько вызовов займёт полное обновление — для подсказки в настройках. */
    fun fullCost(context: Context): Int {
        val sims = SimDb.get(context).all().filter { it.inMts && it.msisdn.length == 11 }
        return LinePlanner.fullCost(sims.size, sims.map { it.accountNo }.filter { it.isNotEmpty() }.distinct().size, LinePrefs.schedule(context).sections)
    }

    /** Разделы, которые запрашиваются по номеру (для подписи «N вызовов на номер»). */
    fun perNumberSections(context: Context) = LinePrefs.schedule(context).sections.count { it.scope == LineScope.NUMBER }

    /** Записать заявку и запланировать проверку её статуса. */
    fun track(context: Context, msisdn: String, accountNo: String, kind: LineChange, title: String, eventId: String?) {
        val store = LineStore.get(context)
        val status = if (kind == LineChange.DOCUMENT) LineRequest.STATUS_SENT else ""
        store.addRequest(msisdn, accountNo, kind, title, eventId, status)
        if (kind != LineChange.DOCUMENT && !eventId.isNullOrBlank()) schedulePoll(context, 1)
        else if (kind != LineChange.DOCUMENT) {
            // Номера заявки нет — просто перечитать затронутое через минуту.
            store.markStale(listOf(msisdn) + listOf(accountNo).filter { it.isNotEmpty() }, LineSection.affectedBy(kind))
        }
        changed()
    }
}

/** Фоновая работа WorkManager: первичная загрузка, обновление по расписанию и по кнопке, проверка заявок. */
class LineSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    /** Работать с уведомлением: так система не прервёт долгую загрузку. false — не разрешила (запуск из фона). */
    private fun foreground(text: String): Boolean =
        runCatching { setForegroundAsync(LineSync.foregroundInfo(applicationContext, text, 0, 0)).get(); true }.getOrDefault(false)

    override fun doWork(): Result {
        val app = applicationContext
        return when (inputData.getString(KEY_MODE)) {
            MODE_REQUESTS -> {
                val left = runCatching { LineSync.pollRequests(app) }.getOrDefault(true)
                if (left) LineSync.schedulePoll(app, 2)
                Result.success()
            }
            MODE_INITIAL -> {
                val fg = foreground("Первичная загрузка…")
                when (LineSync.runInitial(app, { isStopped }, fg) { text, done, total -> LineSync.updateNotification(app, text, done, total, initial = true) }) {
                    LineSync.InitialOutcome.RETRY -> Result.retry()
                    LineSync.InitialOutcome.CONTINUE -> { LineSync.continueInitial(app); Result.success() }
                    LineSync.InitialOutcome.FAILED -> Result.failure()
                    else -> Result.success()
                }
            }
            MODE_NOW -> {
                val fg = foreground("Подготовка…")
                val only = inputData.getStringArray(KEY_NUMBERS)?.toSet()
                val deadline = if (fg) Long.MAX_VALUE else System.currentTimeMillis() + 8 * 60_000L
                LineSync.run(app, only, inputData.getBoolean(KEY_FORCE, false), { isStopped }, deadline) { text, done, total ->
                    LineSync.updateNotification(app, text, done, total)
                }
                Result.success()
            }
            else -> {
                // По расписанию — без уведомления, в пределах 10 минут; недоделанное подхватит следующий запуск.
                LineSync.run(app, null, false, { isStopped }, System.currentTimeMillis() + 8 * 60_000L)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_NUMBERS = "numbers"
        const val KEY_FORCE = "force"
        const val MODE_PERIODIC = "periodic"
        const val MODE_NOW = "now"
        const val MODE_INITIAL = "initial"
        const val MODE_REQUESTS = "requests"
    }
}
