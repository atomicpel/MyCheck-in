package ru.mycheckin.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mycheckin.passport.PassportData
import ru.mycheckin.passport.mts.ContractInfo
import ru.mycheckin.passport.mts.HierarchyPage
import ru.mycheckin.passport.mts.OwnerInfo
import ru.mycheckin.passport.mts.RegAddress
import java.time.LocalDate

/** Контракт МТС: номер, организация и короткое имя, которое задаёт пользователь («Ростовский»). */
data class Contract(val id: String, val orgName: String, val label: String, val inn: String?, val accountId: String = "") {
    val title: String get() = label.ifBlank { orgName.ifBlank { id } }
}

/** SIM-карта: данные МТС, паспорт владельца, фото и состояние заявки на внесение данных. */
data class SimRecord(
    val id: Long = 0,
    val msisdn: String,
    val iccid: String = "",
    val imsi: String = "",
    val contractId: String = "",
    val accountNo: String = "",
    val region: String = "",
    val inMts: Boolean = false,
    val surname: String = "",
    val name: String = "",
    val patronymic: String = "",
    val sex: Char? = null,
    val birthDate: LocalDate? = null,
    val birthPlace: String = "",
    val series: String = "",
    val number: String = "",
    val issueDate: LocalDate? = null,
    val divisionCode: String = "",
    val issuedBy: String = "",
    val address: RegAddress = RegAddress(),
    val notes: String = "",
    val passportPhoto: String = "",
    val facePhoto: String = "",
    val messageId: String = "",
    val opStatus: String = "",
    val confirmation: String = "",
    val sentAt: Long = 0,
    val checkedAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val fullName: String get() = listOf(surname, name, patronymic).filter { it.isNotBlank() }.joinToString(" ")
    val hasPassport: Boolean get() = series.isNotBlank() && number.isNotBlank()

    fun toPassport(): PassportData = PassportData().also {
        it.surname = surname.ifBlank { null }; it.name = name.ifBlank { null }; it.patronymic = patronymic.ifBlank { null }
        it.sex = sex; it.birthDate = birthDate; it.birthPlace = birthPlace.ifBlank { null }
        it.series = series.ifBlank { null }; it.number = number.ifBlank { null }; it.issueDate = issueDate
        it.divisionCode = divisionCode.ifBlank { null }; it.issuedBy = issuedBy.ifBlank { null }; it.citizenship = "RUS"
    }

    /** Заполнить пустые поля данными, уже внесёнными в МТС (введённое вручную не трогаем). */
    fun fillFrom(o: OwnerInfo): SimRecord = copy(
        surname = surname.ifBlank { o.surname.orEmpty() },
        name = name.ifBlank { o.name.orEmpty() },
        patronymic = patronymic.ifBlank { o.patronymic.orEmpty() },
        sex = sex ?: o.sex,
        birthDate = birthDate ?: o.birthDate,
        birthPlace = birthPlace.ifBlank { o.birthPlace.orEmpty() },
        series = series.ifBlank { o.series.orEmpty() },
        number = number.ifBlank { o.number.orEmpty() },
        issueDate = issueDate ?: o.issueDate,
        divisionCode = divisionCode.ifBlank { o.divisionCode.orEmpty() },
        issuedBy = issuedBy.ifBlank { o.issuedBy.orEmpty() },
        address = if (address.isEmpty) o.address ?: address else address,
        confirmation = o.confirmation.code.orEmpty(),
        checkedAt = System.currentTimeMillis(),
    )
}

/**
 * Учёт SIM-карт на телефоне (SQLite в закрытой папке приложения, резервное копирование выключено).
 * Данные из МТС (номер, ICCID, контракт) при синхронизации обновляются, введённое вручную сохраняется.
 */
class SimDb private constructor(context: Context) : SQLiteOpenHelper(context, "sims.db", null, 1) {

    companion object {
        @Volatile private var instance: SimDb? = null
        fun get(context: Context): SimDb = instance ?: synchronized(this) {
            instance ?: SimDb(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE contracts (
              id TEXT PRIMARY KEY, org_name TEXT NOT NULL DEFAULT '', label TEXT NOT NULL DEFAULT '', inn TEXT,
              account_id TEXT NOT NULL DEFAULT ''
            )""")
        db.execSQL("""
            CREATE TABLE sims (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              msisdn TEXT NOT NULL UNIQUE, iccid TEXT NOT NULL DEFAULT '', imsi TEXT NOT NULL DEFAULT '',
              contract_id TEXT NOT NULL DEFAULT '', account_no TEXT NOT NULL DEFAULT '', region TEXT NOT NULL DEFAULT '',
              in_mts INTEGER NOT NULL DEFAULT 0,
              surname TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '', patronymic TEXT NOT NULL DEFAULT '',
              sex TEXT, birth_date TEXT, birth_place TEXT NOT NULL DEFAULT '',
              series TEXT NOT NULL DEFAULT '', number TEXT NOT NULL DEFAULT '', issue_date TEXT,
              division_code TEXT NOT NULL DEFAULT '', issued_by TEXT NOT NULL DEFAULT '',
              addr_region TEXT NOT NULL DEFAULT '', addr_city TEXT NOT NULL DEFAULT '', addr_street TEXT NOT NULL DEFAULT '',
              addr_house TEXT NOT NULL DEFAULT '', addr_apartment TEXT NOT NULL DEFAULT '', addr_zip TEXT NOT NULL DEFAULT '',
              notes TEXT NOT NULL DEFAULT '', passport_photo TEXT NOT NULL DEFAULT '', face_photo TEXT NOT NULL DEFAULT '',
              message_id TEXT NOT NULL DEFAULT '', op_status TEXT NOT NULL DEFAULT '', confirmation TEXT NOT NULL DEFAULT '',
              sent_at INTEGER NOT NULL DEFAULT 0, checked_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0
            )""")
        db.execSQL("CREATE INDEX sims_contract ON sims(contract_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- Контракты ----

    fun contracts(): List<Contract> = readableDatabase.rawQuery("SELECT id, org_name, label, inn, account_id FROM contracts ORDER BY label, org_name, id", null).use { c ->
        buildList { while (c.moveToNext()) add(Contract(c.getString(0), c.getString(1), c.getString(2), c.getStringOrNull(3), c.getString(4))) }
    }

    fun contract(id: String): Contract? = contracts().firstOrNull { it.id == id }

    fun renameContract(id: String, label: String) {
        writableDatabase.update("contracts", ContentValues().apply { put("label", label.trim()) }, "id = ?", arrayOf(id))
    }

    // ---- SIM-карты ----

    fun all(): List<SimRecord> = readableDatabase.rawQuery("SELECT * FROM sims ORDER BY surname = '', surname, name, msisdn", null).use { c ->
        buildList { while (c.moveToNext()) add(read(c)) }
    }

    fun byId(id: Long): SimRecord? = readableDatabase.rawQuery("SELECT * FROM sims WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) read(c) else null
    }

    fun byMsisdn(msisdn: String): SimRecord? = readableDatabase.rawQuery("SELECT * FROM sims WHERE msisdn = ?", arrayOf(msisdn)).use { c ->
        if (c.moveToFirst()) read(c) else null
    }

    /** Сохранить карточку. Возвращает id. Номер уникален: дубликат даёт [IllegalStateException]. */
    fun save(s: SimRecord): Long {
        val other = byMsisdn(s.msisdn)
        if (other != null && other.id != s.id) throw IllegalStateException("Номер ${s.msisdn} уже есть в списке")
        val v = values(s.copy(updatedAt = System.currentTimeMillis()))
        return if (s.id == 0L) writableDatabase.insertOrThrow("sims", null, v)
        else { writableDatabase.update("sims", v, "id = ?", arrayOf(s.id.toString())); s.id }
    }

    fun delete(id: Long) {
        writableDatabase.delete("sims", "id = ?", arrayOf(id.toString()))
    }

    /** Итог синхронизации с МТС. */
    data class SyncResult(val added: Int, val updated: Int, val missing: Int, val total: Int)

    /**
     * Применить структуру абонента: новые номера добавляются, у известных обновляются ICCID/контракт/счёт,
     * номера, которых больше нет в МТС, помечаются (не удаляются — на них могут быть паспортные данные).
     */
    fun applyHierarchy(
        h: HierarchyPage,
        accountByContract: Map<String, String>,
        defaultLabels: Map<String, String>,
        markMissing: Boolean,
    ): SyncResult {
        val db = writableDatabase
        var added = 0; var updated = 0
        db.beginTransaction()
        try {
            for (c in h.contracts) upsertContract(db, c, accountByContract[c.id].orEmpty(), defaultLabels[c.id].orEmpty())
            val seen = HashSet<String>()
            for (n in h.numbers) {
                seen += n.msisdn
                val v = ContentValues().apply {
                    put("contract_id", n.contractId); put("account_no", n.accountNo); put("region", n.region.orEmpty())
                    n.iccid?.let { put("iccid", it) }; n.imsi?.let { put("imsi", it) }
                    put("in_mts", 1)
                }
                if (db.update("sims", v, "msisdn = ?", arrayOf(n.msisdn)) > 0) updated++
                else { v.put("msisdn", n.msisdn); v.put("updated_at", System.currentTimeMillis()); db.insertOrThrow("sims", null, v); added++ }
            }
            var missing = 0
            if (markMissing) db.rawQuery("SELECT msisdn FROM sims WHERE in_mts = 1", null).use { c ->
                val gone = buildList { while (c.moveToNext()) c.getString(0).takeIf { it !in seen }?.let(::add) }
                for (m in gone) db.update("sims", ContentValues().apply { put("in_mts", 0) }, "msisdn = ?", arrayOf(m))
                missing = gone.size
            }
            db.setTransactionSuccessful()
            return SyncResult(added, updated, missing, h.numbers.size)
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertContract(db: SQLiteDatabase, c: ContractInfo, accountId: String, defaultLabel: String) {
        val v = ContentValues().apply { put("org_name", c.orgName); put("inn", c.inn); if (accountId.isNotEmpty()) put("account_id", accountId) }
        if (db.update("contracts", v, "id = ?", arrayOf(c.id)) == 0) {
            v.put("id", c.id); v.put("label", defaultLabel)
            db.insertOrThrow("contracts", null, v)
        }
    }

    private fun values(s: SimRecord) = ContentValues().apply {
        put("msisdn", s.msisdn); put("iccid", s.iccid); put("imsi", s.imsi)
        put("contract_id", s.contractId); put("account_no", s.accountNo); put("region", s.region); put("in_mts", if (s.inMts) 1 else 0)
        put("surname", s.surname); put("name", s.name); put("patronymic", s.patronymic)
        put("sex", s.sex?.toString()); put("birth_date", s.birthDate?.toString()); put("birth_place", s.birthPlace)
        put("series", s.series); put("number", s.number); put("issue_date", s.issueDate?.toString())
        put("division_code", s.divisionCode); put("issued_by", s.issuedBy)
        put("addr_region", s.address.region); put("addr_city", s.address.city); put("addr_street", s.address.street)
        put("addr_house", s.address.house); put("addr_apartment", s.address.apartment); put("addr_zip", s.address.zip)
        put("notes", s.notes); put("passport_photo", s.passportPhoto); put("face_photo", s.facePhoto)
        put("message_id", s.messageId); put("op_status", s.opStatus); put("confirmation", s.confirmation)
        put("sent_at", s.sentAt); put("checked_at", s.checkedAt); put("updated_at", s.updatedAt)
    }

    private fun read(c: Cursor): SimRecord {
        fun s(col: String) = c.getStringOrNull(c.getColumnIndexOrThrow(col)).orEmpty()
        fun l(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
        fun d(col: String) = s(col).ifEmpty { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return SimRecord(
            id = l("id"), msisdn = s("msisdn"), iccid = s("iccid"), imsi = s("imsi"),
            contractId = s("contract_id"), accountNo = s("account_no"), region = s("region"), inMts = l("in_mts") == 1L,
            surname = s("surname"), name = s("name"), patronymic = s("patronymic"), sex = s("sex").firstOrNull(),
            birthDate = d("birth_date"), birthPlace = s("birth_place"), series = s("series"), number = s("number"),
            issueDate = d("issue_date"), divisionCode = s("division_code"), issuedBy = s("issued_by"),
            address = RegAddress(s("addr_region"), s("addr_city"), s("addr_street"), s("addr_house"), s("addr_apartment"), "", s("addr_zip")),
            notes = s("notes"), passportPhoto = s("passport_photo"), facePhoto = s("face_photo"),
            messageId = s("message_id"), opStatus = s("op_status"), confirmation = s("confirmation"),
            sentAt = l("sent_at"), checkedAt = l("checked_at"), updatedAt = l("updated_at"),
        )
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}
