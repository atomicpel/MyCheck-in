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
import ru.mycheckin.passport.mts.LineChange
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

    /** Через сколько часов обновлять в фоне; 0 — только вручную. */
    fun intervalHours(c: Context) = p(c).getInt("interval_hours", 3)
    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    /** Сколько вызовов в минуту может тратить фон (остальное — экранам приложения). */
    fun perMinute(c: Context) = p(c).getInt("per_minute", 150).coerceIn(30, 250)
    /** Множитель сроков свежести: 0.5 — чаще, 1 — обычно, 2 — экономно. */
    fun ttlFactor(c: Context) = p(c).getFloat("ttl_factor", 1f).toDouble()
    fun autoNumbers(c: Context) = p(c).getBoolean("auto_numbers", true)
    fun sections(c: Context): Set<LineSection> =
        p(c).getStringSet("sections", null)?.mapNotNull { LineSection.of(it) }?.toSet() ?: LineSection.entries.toSet()

    fun save(c: Context, intervalHours: Int, wifiOnly: Boolean, perMinute: Int, ttlFactor: Double, autoNumbers: Boolean, sections: Set<LineSection>) {
        p(c).edit().putInt("interval_hours", intervalHours).putBoolean("wifi_only", wifiOnly).putInt("per_minute", perMinute)
            .putFloat("ttl_factor", ttlFactor.toFloat()).putBoolean("auto_numbers", autoNumbers)
            .putStringSet("sections", sections.map { it.name }.toSet()).apply()
    }

    fun lastNumbersSync(c: Context) = p(c).getLong("last_numbers_sync", 0)
    fun setLastNumbersSync(c: Context, t: Long) = p(c).edit().putLong("last_numbers_sync", t).apply()

    fun lastRun(c: Context) = p(c).getLong("last_run", 0)
    fun lastRunText(c: Context) = p(c).getString("last_run_text", "").orEmpty()
    fun setLastRun(c: Context, t: Long, text: String) = p(c).edit().putLong("last_run", t).putString("last_run_text", text).apply()

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
 * Фоновое обновление сведений о номерах из МТС Бизнес API.
 *
 * - По расписанию (WorkManager, переживает перезапуск телефона) и по кнопке.
 * - Фон тратит не больше [LinePrefs.perMinute] вызовов в минуту, а общий ограничитель доступа
 *   (8/с, 280/мин) остаётся общим с экранами — работа в приложении не ждёт фон.
 * - Запрашивается только устаревшее (у каждого раздела свой срок свежести), поэтому прерванное
 *   обновление в следующий раз продолжается с того же места, а лимит не тратится повторно.
 * - Заявки на изменения проверяются в фоне, после выполнения затронутые разделы перечитываются.
 */
object LineSync {
    private const val WORK_PERIODIC = "lines-periodic"
    private const val WORK_NOW = "lines-now"
    private const val WORK_POLL = "lines-requests"
    private const val CHANNEL = "lines_sync"
    const val NOTIFICATION_ID = 4101

    data class State(
        val running: Boolean = false,
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
    private var bgLimiter: Pair<Int, RateLimiter>? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { runCatching { it() } } }

    private fun setState(s: State) { state = s; changed() }

    // ---- Расписание ----

    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (LinePrefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Включить (или обновить) обновление по расписанию по текущим настройкам. Вызывается при запуске. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val hours = LinePrefs.intervalHours(context)
        if (hours <= 0 || !MtsService.hasAccounts(context)) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<LineSyncWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(context))
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(LineSyncWorker.KEY_MODE, LineSyncWorker.MODE_PERIODIC).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /**
     * Обновить сейчас, в фоне, с уведомлением о ходе. [msisdns] — только эти номера (null — все),
     * [force] — запросить всё заново, даже свежее. false — обновление уже идёт.
     */
    fun refreshNow(context: Context, msisdns: Collection<String>? = null, force: Boolean = false): Boolean {
        if (state.running) return false
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
        setState(state.copy(running = true, phase = "Ждёт подключения к сети…", done = 0, total = 0, startedAt = System.currentTimeMillis()))
        return true
    }

    /** Остановить идущее обновление (сведения, полученные до остановки, сохраняются). */
    fun stop(context: Context) {
        stopRequested.set(true)
        if (!runLock.get()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
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

    private fun backgroundLimiter(context: Context): RateLimiter {
        val per = LinePrefs.perMinute(context)
        bgLimiter?.let { if (it.first == per) return it.second }
        // Счётчик за месяц ведёт общий ограничитель доступа; этот — только темп фона.
        return RateLimiter(perSecond = 4, perMinute = per, perMonth = Long.MAX_VALUE).also { bgLimiter = per to it }
    }

    // ---- Обновление ----

    data class Summary(val calls: Int, val errors: Int, val numbers: Int, val stopped: Boolean, val message: String)

    private class StopRun(message: String) : Exception(message)

    /** Ошибка одного раздела: окончательная (раздел недоступен для номера) или временная (повторить позже). */
    private fun classify(e: Exception): Pair<String, Boolean> = when (e) {
        is MtsApiException -> {
            val code = e.response?.code
            (e.message ?: "Ошибка МТС") to (code != null && code in 400..499 && code != 429)
        }
        is SimActions.Failure -> (e.message ?: "Ошибка") to false
        else -> "${e.javaClass.simpleName}: ${e.message ?: ""}".trim() to false
    }

    private fun isAuthProblem(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return e is IllegalArgumentException || m.contains("логин и пароль") || m.contains("токен", ignoreCase = true)
    }

    /** Запросить один раздел и сохранить. Сетевые сбои и исчерпание лимита останавливают обновление. */
    private fun fetch(store: LineStore, api: LineApi, job: LinePlanner.Job): Boolean {
        try {
            if (job.section.bulk) store.putAll(job.section, api.rawBulk(job.section, job.keys))
            else store.put(job.section, job.keys.single(), api.raw(job.section, job.keys.single()))
            return true
        } catch (e: RateLimiter.MonthlyLimitExceeded) {
            throw StopRun("Исчерпан месячный лимит вызовов тарифа")
        } catch (e: java.io.IOException) {
            throw StopRun("Нет связи с МТС: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (isAuthProblem(e)) throw e
            val (msg, final) = classify(e)
            job.keys.forEach { store.putError(job.section, it, msg, final) }
            return false
        }
    }

    /**
     * Обновить сведения. Вызывается из [LineSyncWorker] (не в главном потоке).
     * [only] — только эти номера; [progress] — для уведомления.
     */
    fun run(context: Context, only: Set<String>?, force: Boolean, isStopped: () -> Boolean, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Summary {
        if (!runLock.compareAndSet(false, true)) return Summary(0, 0, 0, false, "Обновление уже идёт")
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        setState(State(running = true, phase = "Подготовка…", startedAt = started))
        var calls = 0; var errors = 0; var stopped = false; var numbersCount = 0
        val problems = LinkedHashSet<String>()
        try {
            return Journal.op(app, LogCategory.LINES, if (only == null) "Фоновое обновление сведений о номерах" else "Обновление сведений: ${only.size} номеров") { op ->
                val accounts = MtsService.accounts(app).filter { it.isConfigured }
                if (accounts.isEmpty()) throw SimActions.Failure("Не введены логин и пароль для интеграции МТС")

                // Раз в сутки — список номеров и SIM (новые номера, смена SIM-карты).
                if (only == null && LinePrefs.autoNumbers(app) && started - LinePrefs.lastNumbersSync(app) > 24 * 3_600_000L) {
                    setState(state.copy(phase = "Список номеров из МТС…"))
                    runCatching { SimActions.syncNumbers(app) { t, _ -> setState(state.copy(phase = t)) } }
                        .onSuccess { LinePrefs.setLastNumbersSync(app, System.currentTimeMillis()); op.line("Список номеров обновлён: ${it.result.total}") }
                        .onFailure { op.line("Список номеров не обновился: ${it.message}") }
                }

                runCatching { pollRequests(app) }

                val store = LineStore.get(app)
                val sims = SimDb.get(app).all().filter { it.msisdn.length == 11 && (it.inMts || only != null) && (only == null || it.msisdn in only) }
                numbersCount = sims.size
                val sections = LinePrefs.sections(app)
                val fetched = store.fetchedAt()
                val now = System.currentTimeMillis()

                // Номера по доступам МТС: у каждого контракта свой логин.
                val groups = LinkedHashMap<String, Pair<MtsAccount, MutableList<SimRecord>>>()
                for (s in sims) {
                    val acc = runCatching { SimActions.accountFor(app, s) }.getOrElse { problems += it.message.orEmpty(); null } ?: continue
                    groups.getOrPut(acc.id) { acc to mutableListOf() }.second += s
                }
                val plans = groups.values.map { (acc, list) ->
                    Triple(acc, list, LinePlanner.plan(list.associate { it.msisdn to it.accountNo }, sections,
                        { s, k -> fetched[s to k] }, now, force, LinePrefs.ttlFactor(app)))
                }
                val total = plans.sumOf { it.third.size }
                op.line("Номеров: ${sims.size}, доступов: ${groups.size}, нужно запросов: $total" + if (force) " (всё заново)" else "")
                if (total == 0) {
                    op.ok("Сведения о номерах свежие — запросов не понадобилось")
                    return@op Summary(0, 0, sims.size, false, "Сведения свежие")
                }
                setState(state.copy(phase = "Обновляю сведения…", total = total))
                val limiter = backgroundLimiter(app)
                var done = 0
                outer@ for ((acc, _, jobs) in plans) {
                    val api = LineApi(MtsService.quietClient(app, acc))
                    for (job in jobs) {
                        if (isStopped() || stopRequested.get()) { stopped = true; break@outer }
                        val text = job.section.title + if (job.keys.size == 1) " · " + SimFormat.phone(job.keys[0]).ifEmpty { job.keys[0] } else " · ${job.keys.size} шт."
                        try {
                            limiter.acquire()
                            if (!fetch(store, api, job)) errors++
                            calls++
                        } catch (e: StopRun) {
                            problems += e.message.orEmpty(); stopped = true; break@outer
                        } catch (e: Exception) {
                            // Доступ не принят МТС — остальные разделы этого доступа не запрашиваем.
                            problems += "${acc.title.ifBlank { "Доступ" }}: ${e.message}"
                            errors++
                            done += jobs.size - jobs.indexOf(job) - 1
                            break
                        }
                        done++
                        setState(state.copy(done = done, phase = text))
                        progress(text, done, total)
                    }
                }
                store.prune(SimDb.get(app).all().flatMap { listOf(it.msisdn, it.accountNo) }.filter { it.isNotEmpty() }.toSet())
                val message = buildString {
                    append("Запросов: $calls")
                    if (errors > 0) append(", с ошибкой: $errors")
                    if (stopped) append(" · остановлено, продолжится при следующем обновлении")
                }
                op.line(message)
                problems.forEach { op.line(it) }
                op.line("Время: ${(System.currentTimeMillis() - started) / 1000} с, темп фона — не больше ${LinePrefs.perMinute(app)} вызовов в минуту")
                if (errors > 0 || problems.isNotEmpty()) op.warn()
                Summary(calls, errors, sims.size, stopped, message)
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
        val sections = (only ?: LinePrefs.sections(app)).filter { !it.byAccount || sim.accountNo.isNotEmpty() }
        val pool = Executors.newFixedThreadPool(3)
        val failures = mutableListOf<String>()
        try {
            val futures = sections.map { s ->
                pool.submit<Unit> {
                    val key = if (s.byAccount) sim.accountNo else msisdn
                    try {
                        fetch(store, api, LinePlanner.Job(s, listOf(key)))
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

    fun notification(context: Context, text: String, done: Int, total: Int): Notification {
        channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, LinesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_sync)
            .setContentTitle(if (total > 0) "Сведения МТС: $done из $total" else "Сведения МТС")
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
    fun updateNotification(context: Context, text: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000 && done < total) return
        lastNotify = now
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text, done, total)) }
    }

    /** Сколько вызовов займёт полное обновление — для подсказки в настройках. */
    fun fullCost(context: Context): Int {
        val sims = SimDb.get(context).all().filter { it.inMts && it.msisdn.length == 11 }
        return LinePlanner.fullCost(sims.size, sims.map { it.accountNo }.filter { it.isNotEmpty() }.distinct().size, LinePrefs.sections(context))
    }

    /** Разделы, которые запрашиваются по номеру (для подписи «N вызовов на номер»). */
    fun perNumberSections(context: Context) = LinePrefs.sections(context).count { it.scope == LineScope.NUMBER }

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

/** Фоновая работа WorkManager: обновление по расписанию, по кнопке и проверка заявок. */
class LineSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        return when (inputData.getString(KEY_MODE)) {
            MODE_REQUESTS -> {
                val left = runCatching { LineSync.pollRequests(app) }.getOrDefault(true)
                if (left) LineSync.schedulePoll(app, 2)
                Result.success()
            }
            MODE_NOW -> {
                // По кнопке — с уведомлением о ходе: так система не прервёт долгое обновление.
                runCatching { setForegroundAsync(LineSync.foregroundInfo(app, "Подготовка…", 0, 0)).get() }
                val only = inputData.getStringArray(KEY_NUMBERS)?.toSet()
                LineSync.run(app, only, inputData.getBoolean(KEY_FORCE, false), { isStopped }) { text, done, total ->
                    LineSync.updateNotification(app, text, done, total)
                }
                Result.success()
            }
            else -> {
                LineSync.run(app, null, false, { isStopped })
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
        const val MODE_REQUESTS = "requests"
    }
}
