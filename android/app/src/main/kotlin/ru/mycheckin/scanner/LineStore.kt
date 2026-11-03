package ru.mycheckin.scanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.LineChange
import ru.mycheckin.passport.mts.LineDigest
import ru.mycheckin.passport.mts.LineRow
import ru.mycheckin.passport.mts.LineSection
import ru.mycheckin.passport.mts.LineSnapshot
import ru.mycheckin.passport.mts.SectionStatus
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
class LineStore private constructor(context: Context) : SQLiteOpenHelper(context, "lines.db", null, 3) {

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
              unavailable INTEGER NOT NULL DEFAULT 0,
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
        createDigests(db)
    }

    /** v3: готовые сводки по номерам для быстрого списка и отметки «сведения изменились». */
    private fun createDigests(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS digests (msisdn TEXT PRIMARY KEY, account_no TEXT NOT NULL DEFAULT '', json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dirty (key TEXT PRIMARY KEY, ver INTEGER NOT NULL DEFAULT 1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: признак «недоступно для номера». Это кеш — проще пересоздать, сведения загрузятся заново.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS sections")
            db.execSQL("DROP TABLE IF EXISTS requests")
            onCreate(db)
            return
        }
        // v3: сводки появятся при первом открытии списка (один раз — дольше обычного).
        if (oldVersion < 3) createDigests(db)
    }

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

    /** Состояние каждого раздела: когда получен и что получено (для первичной загрузки и её прогресса). */
    fun status(): Map<Pair<LineSection, String>, Pair<Long, SectionStatus>> {
        val out = HashMap<Pair<LineSection, String>, Pair<Long, SectionStatus>>()
        readableDatabase.rawQuery("SELECT key, section, fetched_at, body IS NOT NULL, unavailable FROM sections", null).use { c ->
            while (c.moveToNext()) {
                val s = LineSection.of(c.getString(1)) ?: continue
                val st = when {
                    c.getInt(3) == 1 -> SectionStatus.LOADED
                    c.getInt(4) == 1 -> SectionStatus.UNAVAILABLE
                    else -> SectionStatus.FAILED
                }
                out[s to c.getString(0)] = c.getLong(2) to st
            }
        }
        return out
    }

    /** Сведения номера или счёта изменились — его сводку нужно пересчитать (номер версии — от гонок). */
    private fun touch(db: SQLiteDatabase, key: String) {
        db.execSQL("INSERT OR REPLACE INTO dirty (key, ver) VALUES (?, COALESCE((SELECT ver FROM dirty WHERE key = ?), 0) + 1)", arrayOf(key, key))
    }

    /** Сохранить ответ МТС. */
    fun put(section: LineSection, key: String, body: String, at: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.insertWithOnConflict("sections", null, ContentValues().apply {
            put("key", key); put("section", section.name); put("body", compact(body)); put("fetched_at", at); putNull("error"); put("unavailable", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        touch(db, key)
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
            put("unavailable", if (final) 1 else 0)
            if (final) { putNull("body"); put("fetched_at", at) }
        }, "key = ? AND section = ?", arrayOf(key, section.name))
        if (updated == 0) db.insert("sections", null, ContentValues().apply {
            put("key", key); put("section", section.name); putNull("body"); put("error", error.take(500)); put("unavailable", if (final) 1 else 0)
            put("fetched_at", if (final) at else 0L)
        })
        touch(db, key)
    }

    /** Пометить разделы устаревшими (после изменения через API) — обновятся первыми. */
    fun markStale(keys: Collection<String>, sections: Collection<LineSection>) {
        if (keys.isEmpty() || sections.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) {
                for (s in sections) db.update("sections", ContentValues().apply { put("fetched_at", 0L) }, "key = ? AND section = ?", arrayOf(k, s.name))
                touch(db, k)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun sections(key: String): Map<LineSection, StoredSection> =
        readableDatabase.rawQuery("SELECT section, body, fetched_at, error, unavailable FROM sections WHERE key = ?", arrayOf(key)).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val s = LineSection.of(c.getString(0)) ?: continue
                    put(s, StoredSection(if (c.isNull(1)) null else c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4) == 1))
                }
            }
        }

    fun snapshot(msisdn: String, accountNo: String): LineSnapshot =
        LineSnapshot.build(msisdn, accountNo, sections(msisdn), if (accountNo.isNotEmpty()) sections(accountNo) else emptyMap())

    /** Сводки номеров в памяти: повторное открытие списка — без чтения базы. */
    private val cache = HashMap<String, LineSnapshot>()
    private var cacheLoaded = false

    /** Последний собранный список — показать сразу при открытии экрана, пока проверяются изменения. */
    @Volatile var lastRows: List<LineRow>? = null
        private set

    private fun digestKeys(c: android.database.Cursor) = buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }

    /**
     * Строки списка «Номера МТС» для всех SIM учёта. Сводка номера берётся готовой ([LineDigest]);
     * заново из ответов МТС считаются только номера, чьи сведения изменились с прошлого раза
     * (на 1000+ номерах полный разбор — секунды и десятки мегабайт, готовые сводки — доли секунды).
     */
    @Synchronized
    fun rows(context: Context): List<LineRow> {
        val sims = SimDb.get(context).all().filter { it.msisdn.length == 11 }
        val contracts = SimDb.get(context).contracts().associateBy { it.id }
        val db = writableDatabase

        if (!cacheLoaded) {
            db.rawQuery("SELECT msisdn, json FROM digests", null).use { c ->
                while (c.moveToNext()) LineDigest.decode(c.getString(1))?.let { cache[c.getString(0)] = it }
            }
            cacheLoaded = true
        }
        val dirty = db.rawQuery("SELECT key, ver FROM dirty", null).use { digestKeys(it) }
        val stale = sims.filter { s ->
            val cached = cache[s.msisdn]
            cached == null || s.msisdn in dirty || (s.accountNo.isNotEmpty() && s.accountNo in dirty) || cached.accountNo != s.accountNo
        }
        if (stale.isNotEmpty()) {
            val accountSections = HashMap<String, Map<LineSection, StoredSection>>()
            db.beginTransaction()
            try {
                for (s in stale) {
                    val acc = if (s.accountNo.isEmpty()) emptyMap() else accountSections.getOrPut(s.accountNo) { sections(s.accountNo) }
                    val snap = LineSnapshot.build(s.msisdn, s.accountNo, sections(s.msisdn), acc)
                    cache[s.msisdn] = snap
                    db.insertWithOnConflict("digests", null, ContentValues().apply {
                        put("msisdn", s.msisdn); put("account_no", s.accountNo); put("json", LineDigest.encode(snap))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                // Отметки снимаем только той версии, что прочитали: изменённое за это время пересчитается в следующий раз.
                for ((k, ver) in dirty) db.delete("dirty", "key = ? AND ver = ?", arrayOf(k, ver.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } else if (dirty.isNotEmpty()) {
            for ((k, ver) in dirty) db.delete("dirty", "key = ? AND ver = ?", arrayOf(k, ver.toString()))
        }

        val rows = sims.map { s ->
            LineRow(
                msisdn = s.msisdn,
                owner = s.fullName,
                contractId = s.contractId,
                contractTitle = contracts[s.contractId]?.title ?: s.contractId,
                accountNo = s.accountNo,
                iccid = s.iccid,
                region = s.region,
                inMts = s.inMts,
                snap = cache[s.msisdn] ?: LineSnapshot(s.msisdn, s.accountNo),
            )
        }
        lastRows = rows
        return rows
    }

    /** Удалить сведения о номерах, которых больше нет в учёте. */
    fun prune(keep: Set<String>) {
        val db = writableDatabase
        val keys = db.rawQuery("SELECT DISTINCT key FROM sections", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val gone = keys.filter { it !in keep }
        if (gone.isEmpty()) return
        db.beginTransaction()
        try {
            gone.forEach { db.delete("sections", "key = ?", arrayOf(it)); db.delete("digests", "msisdn = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Кеш — после транзакции: rows() берёт сначала кеш, потом базу; обратный порядок здесь дал бы взаимную блокировку.
        synchronized(this) { gone.forEach { cache.remove(it) } }
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
