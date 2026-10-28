package ru.mycheckin.scanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mycheckin.passport.mts.HttpExchange
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.field
import ru.mycheckin.passport.mts.list
import ru.mycheckin.passport.mts.str

/** Уровень записи журнала. */
enum class LogLevel(val label: String, val color: Int) {
    INFO("Инфо", R.color.sent),
    OK("Успешно", R.color.ok),
    WARN("Внимание", R.color.waiting),
    ERROR("Ошибка", R.color.bad),
    RUNNING("Выполняется", R.color.neutral),
}

/** Раздел журнала. */
enum class LogCategory(val label: String) {
    SYNC("Загрузка из МТС"),
    OWNER("Данные владельца"),
    SEND("Внесение в МТС"),
    CHECK("Проверка статуса"),
    SCAN("Скан паспорта"),
    CARD("Карточка"),
    EXPORT("Выгрузка"),
    SETTINGS("Настройки"),
    HTTP("Обращение к МТС"),
    APP("Приложение");

    val isMts: Boolean get() = this in setOf(SYNC, OWNER, SEND, CHECK, HTTP) || this == SETTINGS
}

/** Запись журнала с обращениями к МТС, которые были сделаны в её рамках. */
data class LogEntry(
    val id: Long,
    val time: Long,
    val finishedAt: Long,
    val level: LogLevel,
    val category: LogCategory,
    val title: String,
    val details: String,
    val msisdn: String,
    val account: String,
    val exchanges: List<LoggedExchange> = emptyList(),
    val exchangeCount: Int = 0,
)

data class LoggedExchange(
    val time: Long,
    val method: String,
    val url: String,
    val requestHeaders: String,
    val requestBody: String,
    val code: Int?,
    val responseBody: String,
    val durationMs: Long,
    val attempt: Int,
    val error: String,
    val retryAfterMs: Long?,
)

/**
 * Журнал работы: что сделано, что ушло в МТС и что пришло обратно.
 * Хранится в закрытой папке приложения (резервное копирование выключено). Пароли и токены не записываются.
 * Старые записи удаляются сами: остаются последние 5000 и не старше 90 дней.
 */
object Journal {
    private const val MAX_BODY = 64 * 1024
    private const val KEEP = 5000
    private const val KEEP_DAYS = 90L

    private class Db(context: Context) : SQLiteOpenHelper(context, "journal.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0,
                  level TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, details TEXT NOT NULL DEFAULT '',
                  msisdn TEXT NOT NULL DEFAULT '', account TEXT NOT NULL DEFAULT ''
                )""")
            db.execSQL("""
                CREATE TABLE http (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, event_id INTEGER NOT NULL, time INTEGER NOT NULL,
                  method TEXT NOT NULL, url TEXT NOT NULL, req_headers TEXT NOT NULL DEFAULT '', req_body TEXT NOT NULL DEFAULT '',
                  code INTEGER, resp_body TEXT NOT NULL DEFAULT '', duration INTEGER NOT NULL DEFAULT 0,
                  attempt INTEGER NOT NULL DEFAULT 1, error TEXT NOT NULL DEFAULT '', retry_after INTEGER
                )""")
            db.execSQL("CREATE INDEX http_event ON http(event_id)")
            db.execSQL("CREATE INDEX events_msisdn ON events(msisdn)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var db: Db? = null
    private fun db(context: Context): Db = db ?: synchronized(this) { db ?: Db(context.applicationContext).also { db = it } }

    /** Слушатели (открытый экран журнала обновляется сам). */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** Журнал никогда не должен ронять приложение: сбой записи — только сообщение в системный лог. */
    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w("Journal", "Не удалось записать в журнал", e)
        default
    }

    // ---- Операции ----

    /** Текущая операция потока: обращения к МТС из [MtsService] приписываются к ней. */
    private val current = ThreadLocal<Op>()

    /** Операция в журнале: собирает строки подробностей и итог. */
    class Op internal constructor(private val context: Context, val id: Long) {
        internal val lines = mutableListOf<String>()
        internal var level = LogLevel.OK
        internal var title: String? = null
        internal var msisdn: String? = null
        internal var account: String? = null

        fun line(text: String) { lines += text }
        fun ok(title: String? = null) { level = LogLevel.OK; title?.let { this.title = it } }
        fun warn(title: String? = null) { level = LogLevel.WARN; title?.let { this.title = it } }
        fun fail(title: String? = null) { level = LogLevel.ERROR; title?.let { this.title = it } }
        fun title(t: String) { title = t }
        fun msisdn(m: String) { msisdn = m }
        fun account(a: String) { account = a }

        internal fun http(e: HttpExchange) = insertHttp(context, id, e)
    }

    /**
     * Выполнить [block] как запись журнала. Запись появляется сразу («Выполняется»),
     * по окончании получает итог; исключение записывается как ошибка и пробрасывается дальше.
     */
    fun <T> op(context: Context, category: LogCategory, title: String, msisdn: String = "", account: String = "", block: (Op) -> T): T {
        val id = insertEvent(context, LogLevel.RUNNING, category, title, "", msisdn, account)
        val op = Op(context, id)
        val outer = current.get()
        current.set(op)
        try {
            val result = block(op)
            finish(context, op, null)
            return result
        } catch (e: Throwable) {
            op.level = LogLevel.ERROR
            finish(context, op, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            if (outer != null) current.set(outer) else current.remove()
        }
    }

    private fun finish(context: Context, op: Op, error: String?) = safe(Unit) {
        val details = (op.lines + listOfNotNull(error?.let { "Ошибка: $it" })).joinToString("\n")
        val v = ContentValues().apply {
            put("level", op.level.name)
            put("details", details)
            put("finished_at", System.currentTimeMillis())
            op.title?.let { put("title", it) }
            op.msisdn?.let { put("msisdn", it) }
            op.account?.let { put("account", it) }
        }
        db(context).writableDatabase.update("events", v, "id = ?", arrayOf(op.id.toString()))
        changed()
    }

    /** Простая запись без обращений к МТС. */
    fun event(context: Context, level: LogLevel, category: LogCategory, title: String, details: String = "", msisdn: String = "", account: String = "") {
        insertEvent(context, level, category, title, details, msisdn, account, finished = true)
        changed()
    }

    /** Обращение к МТС: к текущей операции потока или отдельной записью. */
    fun http(context: Context, e: HttpExchange) = safe(Unit) {
        val op = current.get()
        if (op != null) { op.http(e); changed(); return@safe }
        val id = insertEvent(context, if (e.ok) LogLevel.OK else LogLevel.ERROR, LogCategory.HTTP,
            "${e.method} ${path(e.url)} → ${e.code ?: "нет ответа"}", "", "", "", finished = true)
        insertHttp(context, id, e)
        changed()
    }

    private fun insertEvent(
        context: Context, level: LogLevel, category: LogCategory, title: String, details: String,
        msisdn: String, account: String, finished: Boolean = false,
    ): Long = safe(-1L) {
        val now = System.currentTimeMillis()
        val w = db(context).writableDatabase
        val id = w.insert("events", null, ContentValues().apply {
            put("time", now); put("finished_at", if (finished) now else 0)
            put("level", level.name); put("category", category.name); put("title", title)
            put("details", details); put("msisdn", msisdn); put("account", account)
        })
        if (id % 200 == 0L) cleanup(w)
        id
    }

    private fun insertHttp(context: Context, eventId: Long, e: HttpExchange) = safe(Unit) {
        if (eventId < 0) return@safe
        db(context).writableDatabase.insert("http", null, ContentValues().apply {
            put("event_id", eventId); put("time", System.currentTimeMillis())
            put("method", e.method); put("url", e.url)
            put("req_headers", e.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            put("req_body", cut(e.requestBody.orEmpty()))
            put("code", e.code); put("resp_body", cut(e.responseBody.orEmpty()))
            put("duration", e.durationMs); put("attempt", e.attempt)
            put("error", e.error.orEmpty()); put("retry_after", e.retryAfterMs)
        })
    }

    private fun cut(s: String): String =
        if (s.length <= MAX_BODY) s else s.take(MAX_BODY) + "\n… обрезано: показано ${MAX_BODY / 1024} КБ из ${s.length / 1024} КБ"

    private fun cleanup(w: SQLiteDatabase) {
        val border = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        w.execSQL("DELETE FROM events WHERE time < ? OR id <= (SELECT MAX(id) FROM events) - ?", arrayOf<Any>(border, KEEP))
        w.execSQL("DELETE FROM http WHERE event_id NOT IN (SELECT id FROM events)")
    }

    fun path(url: String): String = url.substringAfter("://").substringAfter('/', "").let { "/" + it }

    // ---- Чтение ----

    /** Записи (новые сверху) без тел запросов — для списка. */
    fun entries(context: Context, msisdn: String? = null, limit: Int = 1500): List<LogEntry> {
        val r = db(context).readableDatabase
        val sql = """
            SELECT e.id, e.time, e.finished_at, e.level, e.category, e.title, e.details, e.msisdn, e.account,
                   (SELECT COUNT(*) FROM http h WHERE h.event_id = e.id)
            FROM events e ${if (msisdn != null) "WHERE e.msisdn = ?" else ""}
            ORDER BY e.id DESC LIMIT $limit"""
        return r.rawQuery(sql, msisdn?.let { arrayOf(it) }).use { c ->
            buildList {
                while (c.moveToNext()) add(LogEntry(
                    id = c.getLong(0), time = c.getLong(1), finishedAt = c.getLong(2),
                    level = runCatching { LogLevel.valueOf(c.getString(3)) }.getOrDefault(LogLevel.INFO),
                    category = runCatching { LogCategory.valueOf(c.getString(4)) }.getOrDefault(LogCategory.HTTP),
                    title = c.getString(5), details = c.getString(6), msisdn = c.getString(7), account = c.getString(8),
                    exchangeCount = c.getInt(9),
                ))
            }
        }
    }

    fun exchanges(context: Context, eventId: Long): List<LoggedExchange> =
        db(context).readableDatabase.rawQuery(
            "SELECT time, method, url, req_headers, req_body, code, resp_body, duration, attempt, error, retry_after FROM http WHERE event_id = ? ORDER BY id",
            arrayOf(eventId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(LoggedExchange(
                    time = c.getLong(0), method = c.getString(1), url = c.getString(2), requestHeaders = c.getString(3),
                    requestBody = c.getString(4), code = if (c.isNull(5)) null else c.getInt(5), responseBody = c.getString(6),
                    durationMs = c.getLong(7), attempt = c.getInt(8), error = c.getString(9),
                    retryAfterMs = if (c.isNull(10)) null else c.getLong(10),
                ))
            }
        }

    fun clear(context: Context) {
        val w = db(context).writableDatabase
        w.delete("http", null, null)
        w.delete("events", null, null)
        changed()
    }

    private val startupDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Один раз за запуск приложения: незавершённые записи прошлого запуска помечаются прерванными. */
    fun markInterruptedOnce(context: Context) {
        if (startupDone.compareAndSet(false, true)) markInterrupted(context)
    }

    /** Записи, оставшиеся «Выполняется» после закрытия приложения, помечаются как прерванные. */
    fun markInterrupted(context: Context) {
        db(context).writableDatabase.update("events", ContentValues().apply {
            put("level", LogLevel.WARN.name); put("finished_at", System.currentTimeMillis())
            put("details", "Прервано: приложение было закрыто до окончания операции")
        }, "level = ?", arrayOf(LogLevel.RUNNING.name))
    }

    /** Текст для отправки: записи с подробностями и обменами с МТС. */
    fun exportText(context: Context, entries: List<LogEntry>): String = buildString {
        appendLine("Журнал «Учёт SIM» — ${SimFormat.time(System.currentTimeMillis())}")
        appendLine("Пароли и токены в журнал не записываются.")
        for (e in entries.reversed()) {
            appendLine()
            appendLine("[${SimFormat.time(e.time)}] ${e.level.label.uppercase()} · ${e.category.label} · ${e.title}")
            if (e.msisdn.isNotEmpty()) appendLine("Номер: ${SimFormat.phone(e.msisdn)}")
            if (e.account.isNotEmpty() && !e.details.startsWith("Доступ:")) appendLine("Доступ: ${e.account}")
            if (e.details.isNotEmpty()) appendLine(e.details)
            for (x in exchanges(context, e.id)) {
                appendLine("  → ${x.method} ${x.url}  [${x.code ?: "нет ответа"}, ${x.durationMs} мс, попытка ${x.attempt}]")
                if (x.error.isNotEmpty()) appendLine("    ошибка: ${x.error}")
                if (x.requestBody.isNotEmpty()) appendLine("    отправлено: ${x.requestBody.replace("\n", " ").take(2000)}")
                if (x.responseBody.isNotEmpty()) appendLine("    получено: ${x.responseBody.replace("\n", " ").take(2000)}")
            }
        }
    }

    /** Подпись для HTTP-вызова в журнале. */
    fun describe(x: LoggedExchange): String {
        val what = when {
            x.url.endsWith("/token") -> "Получение токена"
            "HierarchyStructure" in x.url -> "Структура абонента (номера и SIM)"
            "ChangePersonalData" in x.url -> "Внесение персональных данных"
            "GetOperationResult" in x.url -> "Статус заявки"
            "PersonalDataInfo" in x.url -> "Данные владельца и подтверждение"
            else -> "Запрос к МТС"
        }
        return what
    }

    /** Краткий итог ответа МТС: число номеров, номер заявки, статус — то, что видно без раскрытия. */
    fun summary(x: LoggedExchange): String? {
        if (x.code == null) return x.error.ifEmpty { "Нет ответа" }
        val j = Json.parseOrNull(x.responseBody) ?: return null
        return when {
            "HierarchyStructure" in x.url -> runCatching { ru.mycheckin.passport.mts.Hierarchy.parse(x.responseBody) }.getOrNull()
                ?.let { "контрактов ${it.contracts.size}, номеров ${it.numbers.size}" + if (it.hasMore) ", есть ещё (hasMore)" else "" }
            "ChangePersonalData" in x.url -> {
                val sent = Json.parseOrNull(x.requestBody).field("SubscriberInformation").field("MessageId").str()
                val r = ru.mycheckin.passport.mts.PersonalDataApi.parseSubmit(x.responseBody, sent)
                when {
                    r.accepted && r.messageIdFromResponse -> "принято, заявка ${r.messageId}"
                    r.accepted -> "принято (ErrorCode пустой), заявка ${r.messageId} — номер из запроса"
                    else -> "отклонено: ErrorCode ${r.errorCode.ifEmpty { "—" }} ${r.errorDescription}".trim()
                }
            }
            "GetOperationResult" in x.url -> j.field("response").field("Body").field("GetOperationResultResult").list().lastOrNull()
                .field("BusinessProcessCommonResult").field("State").str()?.let { "состояние $it" }
            "PersonalDataInfo" in x.url -> ru.mycheckin.passport.mts.OwnerInfo.parse(x.responseBody).confirmation.code?.let { "подтверждение $it" }
            x.url.endsWith("/token") -> if (x.code == 200) "токен получен" else null
            else -> null
        }
    }
}
