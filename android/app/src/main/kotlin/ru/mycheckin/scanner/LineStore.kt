package ru.mycheckin.scanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.LineChange
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.LineSection
import ru.mycheckin.passport.mts.LineSnapshot
import ru.mycheckin.passport.mts.StoredSection

/** Заявка на изменение, отправленная в МТС: её статус проверяется в фоне. */
data class LineRequest(
    val id: Long,
    val msisdn: String,
    val accountNo: String,
    val kind: LineChange,
    val title: String,
    val eventId: String,
    val createdAt: Long,
    val status: String,
    val error: String,
    val checkedAt: Long,
    val checks: Int,
) {
    val done: Boolean get() = status == "Completed"
    val failed: Boolean get() = status == "Faulted" || status == "Rejected"
    val pending: Boolean get() = !done && !failed && status != STATUS_SENT && status != STATUS_UNKNOWN

    val statusTitle: String get() = when {
        done -> "Выполнено"
        failed -> "Отклонено"
        status == STATUS_SENT -> "Отправлено"
        status == STATUS_UNKNOWN -> "Статус неизвестен"
        status == "InProgress" || status.isEmpty() -> "В работе"
        else -> status
    }

    companion object {
        /** Заявка без номера для проверки (документ на email) — проверять нечего. */
        const val STATUS_SENT = "Sent"
        /** МТС так и не ответил о статусе за отведённое время. */
        const val STATUS_UNKNOWN = "Unknown"
    }
}

/**
 * Сведения о номерах из МТС на телефоне (SQLite в закрытой папке приложения, без резервного копирования).
 * Отдельная база от учёта SIM: это кеш, который всегда можно получить из МТС заново, на сервер он не уходит.
 */
class LineStore private constructor(context: Context) : SQLiteOpenHelper(context, "lines.db", null, 1) {

    companion object {
        @Volatile private var instance: LineStore? = null
        fun get(context: Context): LineStore = instance ?: synchronized(this) {
            instance ?: LineStore(context.applicationContext).also { instance = it }
        }

        /** Убрать из ответа пробелы и переносы: так база меньше, а разбор тот же. */
        fun compact(body: String): String = Json.parseOrNull(body.trim())?.let { runCatching { Json.write(it) }.getOrNull() } ?: body
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sections (
              key TEXT NOT NULL, section TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL DEFAULT 0, error TEXT,
              PRIMARY KEY (key, section)
            )""")
        db.execSQL("""
            CREATE TABLE requests (
              id INTEGER PRIMARY KEY AUTOINCREMENT, msisdn TEXT NOT NULL, account_no TEXT NOT NULL DEFAULT '',
              kind TEXT NOT NULL, title TEXT NOT NULL, event_id TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
              status TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', checked_at INTEGER NOT NULL DEFAULT 0,
              checks INTEGER NOT NULL DEFAULT 0
            )""")
        db.execSQL("CREATE INDEX requests_msisdn ON requests(msisdn)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- Разделы ----

    /** Когда получен каждый раздел: для плана обновления. */
    fun fetchedAt(): Map<Pair<LineSection, String>, Long> {
        val out = HashMap<Pair<LineSection, String>, Long>()
        readableDatabase.rawQuery("SELECT key, section, fetched_at FROM sections", null).use { c ->
            while (c.moveToNext()) {
                val s = LineSection.of(c.getString(1)) ?: continue
                out[s to c.getString(0)] = c.getLong(2)
            }
        }
        return out
    }

    /** Сохранить ответ МТС. */
    fun put(section: LineSection, key: String, body: String, at: Long = System.currentTimeMillis()) {
        writableDatabase.insertWithOnConflict("sections", null, ContentValues().apply {
            put("key", key); put("section", section.name); put("body", compact(body)); put("fetched_at", at); putNull("error")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putAll(section: LineSection, bodies: Map<String, String>, at: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((k, b) in bodies) put(section, k, b, at)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Запомнить ошибку. [final] — МТС ответил, что раздел для номера недоступен (4xx): прежние сведения
     * убираются и раздел не запрашивается до истечения срока свежести. Иначе (сбой МТС) прежние
     * сведения остаются и запрос повторится при следующем обновлении.
     */
    fun putError(section: LineSection, key: String, error: String, final: Boolean, at: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val updated = db.update("sections", ContentValues().apply {
            put("error", error.take(500))
            if (final) { putNull("body"); put("fetched_at", at) }
        }, "key = ? AND section = ?", arrayOf(key, section.name))
        if (updated == 0) db.insert("sections", null, ContentValues().apply {
            put("key", key); put("section", section.name); putNull("body"); put("error", error.take(500))
            put("fetched_at", if (final) at else 0L)
        })
    }

    /** Пометить разделы устаревшими (после изменения через API) — обновятся первыми. */
    fun markStale(keys: Collection<String>, sections: Collection<LineSection>) {
        if (keys.isEmpty() || sections.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) for (s in sections) db.update("sections", ContentValues().apply { put("fetched_at", 0L) },
                "key = ? AND section = ?", arrayOf(k, s.name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun sections(key: String): Map<LineSection, StoredSection> =
        readableDatabase.rawQuery("SELECT section, body, fetched_at, error FROM sections WHERE key = ?", arrayOf(key)).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val s = LineSection.of(c.getString(0)) ?: continue
                    put(s, StoredSection(if (c.isNull(1)) null else c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3)))
                }
            }
        }

    fun snapshot(msisdn: String, accountNo: String): LineSnapshot =
        LineSnapshot.build(msisdn, accountNo, sections(msisdn), if (accountNo.isNotEmpty()) sections(accountNo) else emptyMap())

    /**
     * Строки списка «Номера МТС» для всех SIM учёта. Разделы читаются по одному номеру за раз,
     * чтобы большие ответы МТС не держать в памяти целиком.
     */
    fun rows(context: Context): List<LineRow> {
        val sims = SimDb.get(context).all().filter { it.msisdn.length == 11 }
        val contracts = SimDb.get(context).contracts().associateBy { it.id }
        val accountNos = sims.map { it.accountNo }.filter { it.isNotEmpty() }.toSet()
        val accounts = accountNos.associateWith { sections(it) }
        val numbers = sims.map { it.msisdn }.toSet()

        val byNumber = HashMap<String, MutableMap<LineSection, StoredSection>>()
        readableDatabase.rawQuery("SELECT key, section, body, fetched_at, error FROM sections ORDER BY key", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                if (key !in numbers) continue
                val s = LineSection.of(c.getString(1)) ?: continue
                if (s.byAccount) continue
                byNumber.getOrPut(key) { HashMap() }[s] =
                    StoredSection(if (c.isNull(2)) null else c.getString(2), c.getLong(3), if (c.isNull(4)) null else c.getString(4))
            }
        }
        return sims.map { s ->
            val snap = LineSnapshot.build(s.msisdn, s.accountNo, byNumber.remove(s.msisdn).orEmpty(), accounts[s.accountNo].orEmpty())
            LineRow(
                msisdn = s.msisdn,
                owner = s.fullName,
                contractId = s.contractId,
                contractTitle = contracts[s.contractId]?.title ?: s.contractId,
                accountNo = s.accountNo,
                iccid = s.iccid,
                region = s.region,
                inMts = s.inMts,
                snap = snap,
            )
        }
    }

    /** Удалить сведения о номерах, которых больше нет в учёте. */
    fun prune(keep: Set<String>) {
        val db = writableDatabase
        val keys = db.rawQuery("SELECT DISTINCT key FROM sections", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val gone = keys.filter { it !in keep }
        if (gone.isEmpty()) return
        db.beginTransaction()
        try {
            gone.forEach { db.delete("sections", "key = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Заявки ----

    fun addRequest(msisdn: String, accountNo: String, kind: LineChange, title: String, eventId: String?, status: String = ""): Long =
        writableDatabase.insertOrThrow("requests", null, ContentValues().apply {
            put("msisdn", msisdn); put("account_no", accountNo); put("kind", kind.name); put("title", title)
            put("event_id", eventId.orEmpty()); put("created_at", System.currentTimeMillis())
            put("status", if (eventId.isNullOrBlank() && status.isEmpty()) LineRequest.STATUS_UNKNOWN else status)
        })

    fun updateRequest(id: Long, status: String, error: String) {
        writableDatabase.execSQL(
            "UPDATE requests SET status = ?, error = ?, checked_at = ?, checks = checks + 1 WHERE id = ?",
            arrayOf<Any>(status, error.take(500), System.currentTimeMillis(), id),
        )
    }

    fun requests(msisdn: String? = null, limit: Int = 200): List<LineRequest> {
        val where = if (msisdn != null) "WHERE msisdn = ?" else ""
        val args = if (msisdn != null) arrayOf(msisdn) else null
        return readableDatabase.rawQuery("SELECT * FROM requests $where ORDER BY id DESC LIMIT $limit", args).use { c ->
            fun s(col: String) = c.getString(c.getColumnIndexOrThrow(col)).orEmpty()
            fun l(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
            buildList {
                while (c.moveToNext()) add(LineRequest(
                    id = l("id"), msisdn = s("msisdn"), accountNo = s("account_no"),
                    kind = LineChange.of(s("kind")) ?: LineChange.SERVICE, title = s("title"), eventId = s("event_id"),
                    createdAt = l("created_at"), status = s("status"), error = s("error"), checkedAt = l("checked_at"),
                    checks = l("checks").toInt(),
                ))
            }
        }
    }

    fun pendingRequests(): List<LineRequest> = requests(limit = 500).filter { it.pending && it.eventId.isNotEmpty() }
}
