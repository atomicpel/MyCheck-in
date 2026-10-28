package ru.mycheckin.scanner

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.field
import ru.mycheckin.passport.mts.str
import ru.mycheckin.passport.vault.ApplyResult
import ru.mycheckin.passport.vault.BlobIo
import ru.mycheckin.passport.vault.Pending
import ru.mycheckin.passport.vault.SyncEngine
import ru.mycheckin.passport.vault.SyncReport
import ru.mycheckin.passport.vault.SyncStore
import ru.mycheckin.passport.vault.VaultApi
import ru.mycheckin.passport.vault.VaultCrypto
import ru.mycheckin.passport.vault.VaultException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Синхронизация с собственным сервером «Учёт SIM» (сквозное шифрование).
 *
 * На сервер уходят только зашифрованные записи и фото: ключ хранилища расшифровывается мастер-паролем
 * один раз при подключении и хранится на телефоне в зашифрованном хранилище (Android Keystore).
 * Мастер-пароль нигде не сохраняется. Доступы к МТС на сервер не отправляются.
 */
object VaultSync {
    private const val URL = "url"
    private const val DEVICE_TOKEN = "device_token"
    private const val DEVICE_ID = "device_id"
    private const val VAULT_KEY = "vault_key"
    private const val CURSOR = "cursor"
    private const val LAST_SYNC = "last_sync"
    private const val LAST_ERROR = "last_error"
    private const val MIN_INTERVAL_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    @Volatile var running = false; private set
    private var prefs: SharedPreferences? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    @Synchronized
    private fun securePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, "vault_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { prefs = it }
    }

    fun isConnected(context: Context) = securePrefs(context).run { contains(VAULT_KEY) && contains(DEVICE_TOKEN) }
    fun url(context: Context): String = securePrefs(context).getString(URL, "").orEmpty()
    fun lastSync(context: Context): Long = securePrefs(context).getLong(LAST_SYNC, 0)
    fun lastError(context: Context): String = securePrefs(context).getString(LAST_ERROR, "").orEmpty()

    /**
     * Подключить телефон: мастер-пароль и код из приложения-аутентификатора.
     * Вызывать не в главном потоке (вывод ключа из пароля занимает несколько секунд).
     */
    fun connect(context: Context, url: String, password: CharArray, totp: String) {
        val api = VaultApi(url)
        try {
            if (!api.initialized()) throw VaultException(409, "Хранилище на сервере ещё не создано — создайте его в веб-версии")
            val keys = VaultCrypto.deriveKeys(password, api.prelogin())
            try {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)
                val r = api.login(VaultCrypto.b64(keys.authKey), totp.trim(), trustDevice = true, deviceName = name)
                val vk = VaultCrypto.unwrapVaultKey(keys.kek, r.wrappedKey ?: throw VaultException(0, "Сервер не прислал ключ"))
                val prev = url(context)
                securePrefs(context).edit()
                    .putString(URL, api.base).putString(DEVICE_TOKEN, r.deviceToken).putString(DEVICE_ID, r.deviceId)
                    .putString(VAULT_KEY, VaultCrypto.b64(vk)).putLong(CURSOR, 0).remove(LAST_ERROR).commit()
                vk.fill(0)
                // Другой сервер — прежние связи записей недействительны: отправим всё заново.
                if (prev != api.base) resetLinks(context)
                Journal.event(context, LogLevel.OK, LogCategory.VAULT, "Телефон подключён к серверу", "Сервер: ${api.base}\nУстройство: $name\nМастер-пароль не сохраняется")
            } finally {
                keys.wipe()
            }
        } finally {
            password.fill('\u0000')
        }
        changed()
    }

    /** Отключить: забыть ключ и токен устройства. Данные на телефоне остаются. */
    fun disconnect(context: Context) {
        val p = securePrefs(context)
        val token = p.getString(DEVICE_TOKEN, null)
        val url = p.getString(URL, null)
        val deviceId = p.getString(DEVICE_ID, null)
        p.edit().remove(DEVICE_TOKEN).remove(DEVICE_ID).remove(VAULT_KEY).remove(CURSOR).remove(LAST_ERROR).commit()
        executor.execute {
            // Отзываем устройство на сервере: токен этого телефона перестаёт действовать.
            if (token != null && url != null) runCatching {
                VaultApi(url).apply { deviceToken = token; deviceLogin(); deviceId?.let(::revokeDevice); logout() }
            }
        }
        Journal.event(context, LogLevel.WARN, LogCategory.VAULT, "Телефон отключён от сервера", "Ключ хранилища удалён с телефона")
        changed()
    }

    private fun resetLinks(context: Context) {
        val db = SimDb.get(context).writableDatabase
        db.execSQL("UPDATE sims SET remote_id = '', remote_rev = 0, dirty = 1, passport_blob = '', face_blob = ''")
        db.execSQL("UPDATE contracts SET remote_id = '', remote_rev = 0, dirty = 1")
        db.execSQL("DELETE FROM vault_deletes")
    }

    /** Синхронизировать в фоне. [force] — даже если недавно уже синхронизировались. */
    fun syncSoon(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!isConnected(app) || running) return
        if (!force && System.currentTimeMillis() - lastSync(app) < MIN_INTERVAL_MS) return
        running = true
        changed()
        executor.execute {
            try { runSync(app) } finally { running = false; changed() }
        }
    }

    private fun runSync(context: Context) {
        val p = securePrefs(context)
        val url = p.getString(URL, null) ?: return
        val vk = p.getString(VAULT_KEY, null)?.let(VaultCrypto::unb64) ?: return
        val api = VaultApi(url).apply { deviceToken = p.getString(DEVICE_TOKEN, null) }
        val store = SqlStore(context, p)
        val started = System.currentTimeMillis()
        try {
            val r: SyncReport = SyncEngine(api, vk, store).run()
            p.edit().putLong(LAST_SYNC, System.currentTimeMillis()).remove(LAST_ERROR).apply()
            if (r.received + r.sent + r.deleted + r.keptLocal + r.undecryptable > 0 || r.errors.isNotEmpty()) {
                val level = when {
                    r.undecryptable > 0 || r.errors.isNotEmpty() -> LogLevel.WARN
                    else -> LogLevel.OK
                }
                Journal.event(context, level, LogCategory.VAULT, "Синхронизация с сервером", buildString {
                    appendLine("Получено с сервера: ${r.received}")
                    appendLine("Отправлено на сервер: ${r.sent}")
                    if (r.deleted > 0) appendLine("Удалено на сервере: ${r.deleted}")
                    if (r.keptLocal > 0) appendLine("Оставлены правки с телефона (они новее): ${r.keptLocal}")
                    if (r.conflicts > 0) appendLine("Конфликтов версий: ${r.conflicts} (разрешены)")
                    if (r.undecryptable > 0) appendLine("Не расшифровались: ${r.undecryptable} — возможна подмена данных на сервере, они не применены")
                    r.errors.take(10).forEach { appendLine(it) }
                    append("Время: ${(System.currentTimeMillis() - started) / 1000.0} с · все данные зашифрованы на телефоне")
                })
            }
        } catch (e: VaultException) {
            val msg = if (e.code == 401) "Телефон отключён на сервере или срок доверия истёк — подключитесь заново" else e.message.orEmpty()
            p.edit().putString(LAST_ERROR, msg).apply()
            Journal.event(context, LogLevel.ERROR, LogCategory.VAULT, "Синхронизация не удалась", msg)
            if (e.code == 401) p.edit().remove(DEVICE_TOKEN).remove(VAULT_KEY).apply()
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            p.edit().putString(LAST_ERROR, msg).apply()
            Journal.event(context, LogLevel.ERROR, LogCategory.VAULT, "Синхронизация не удалась", msg)
        } finally {
            vk.fill(0)
        }
    }

    // ---- Формат записей (общий с веб-версией) ----

    internal fun simJson(s: SimRecord, passportBlob: String, faceBlob: String): String = Json.write(mapOf(
        "t" to "sim", "msisdn" to s.msisdn, "iccid" to s.iccid, "imsi" to s.imsi, "contractId" to s.contractId,
        "accountNo" to s.accountNo, "region" to s.region, "inMts" to s.inMts,
        "surname" to s.surname, "name" to s.name, "patronymic" to s.patronymic, "sex" to s.sex?.toString(),
        "birthDate" to s.birthDate?.toString(), "birthPlace" to s.birthPlace, "series" to s.series, "number" to s.number,
        "issueDate" to s.issueDate?.toString(), "divisionCode" to s.divisionCode, "issuedBy" to s.issuedBy,
        "address" to mapOf("region" to s.address.region, "city" to s.address.city, "street" to s.address.street,
            "house" to s.address.house, "apartment" to s.address.apartment, "zip" to s.address.zip),
        "notes" to s.notes, "passportPhoto" to passportBlob.ifEmpty { null }, "facePhoto" to faceBlob.ifEmpty { null },
        "messageId" to s.messageId, "opStatus" to s.opStatus, "confirmation" to s.confirmation,
        "sentAt" to s.sentAt, "checkedAt" to s.checkedAt, "updatedAt" to s.updatedAt,
    ))

    /** Локальная база как хранилище для [SyncEngine]. */
    private class SqlStore(private val context: Context, private val p: SharedPreferences) : SyncStore {
        private val db = SimDb.get(context).writableDatabase

        override var cursor: Long
            get() = p.getLong(CURSOR, 0)
            set(v) { p.edit().putLong(CURSOR, v).apply() }

        override fun applyRemote(id: String, rev: Long, json: String?, blobs: BlobIo): ApplyResult {
            if (json == null) return relink(id)
            val o = Json.parse(json)
            return when (o.field("t").str()) {
                "deleted" -> applyDelete(id, rev)
                "sim" -> applySim(id, rev, o, blobs)
                "contract" -> applyContract(id, rev, o)
                else -> ApplyResult.SKIPPED // служебные записи веб-версии
            }
        }

        /** Неподписанное удаление от сервера: данные не стираем, а отправим заново под новым id. */
        private fun relink(id: String): ApplyResult {
            val v = ContentValues().apply { put("remote_id", ""); put("remote_rev", 0); put("dirty", 1) }
            val n = db.update("sims", ContentValues(v).apply { put("passport_blob", ""); put("face_blob", "") }, "remote_id = ?", arrayOf(id)) +
                db.update("contracts", v, "remote_id = ?", arrayOf(id))
            return if (n > 0) ApplyResult.KEPT_LOCAL else ApplyResult.SKIPPED
        }

        private fun applyDelete(id: String, rev: Long): ApplyResult {
            db.rawQuery("SELECT id, dirty, passport_photo, face_photo FROM sims WHERE remote_id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) return@use
                if (c.getInt(1) == 1) {
                    // Удалили в веб-версии, а на телефоне есть свежая правка — правка важнее, вернём запись.
                    db.update("sims", ContentValues().apply { put("remote_rev", rev) }, "id = ?", arrayOf(c.getString(0)))
                    return ApplyResult.KEPT_LOCAL
                }
                SimPhotos.delete(c.getString(2)); SimPhotos.delete(c.getString(3))
                db.delete("sims", "id = ?", arrayOf(c.getString(0)))
                return ApplyResult.APPLIED
            }
            // Контракт оставляем на телефоне (к нему привязаны SIM), только разрываем связь.
            val n = db.update("contracts", ContentValues().apply { put("remote_id", ""); put("remote_rev", 0); put("dirty", 0) }, "remote_id = ?", arrayOf(id))
            db.delete("vault_deletes", "remote_id = ?", arrayOf(id))
            return if (n > 0) ApplyResult.APPLIED else ApplyResult.SKIPPED
        }

        private fun applySim(id: String, rev: Long, o: Any?, blobs: BlobIo): ApplyResult {
            val msisdn = o.field("msisdn").str().orEmpty().filter { it.isDigit() }
            if (msisdn.isEmpty()) return ApplyResult.SKIPPED
            val remoteUpdated = (o.field("updatedAt") as? Number)?.toLong() ?: 0L
            val row = find("SELECT id, remote_id, remote_rev, dirty, updated_at, passport_blob, face_blob, passport_photo, face_photo FROM sims WHERE remote_id = ?", id)
                ?: find("SELECT id, remote_id, remote_rev, dirty, updated_at, passport_blob, face_blob, passport_photo, face_photo FROM sims WHERE msisdn = ?", msisdn)
            if (row != null) {
                val (localId, rid, rrev, dirty, upd) = row
                // Тот же номер, но связан с другой записью на сервере — дубликат из веб-версии, не смешиваем.
                if (rid.isNotEmpty() && rid != id) return ApplyResult.SKIPPED
                if (rid == id && rrev.toLong() >= rev) return ApplyResult.SKIPPED
                if (dirty == "1" && upd.toLong() > remoteUpdated) {
                    db.update("sims", ContentValues().apply { put("remote_id", id); put("remote_rev", rev) }, "id = ?", arrayOf(localId))
                    return ApplyResult.KEPT_LOCAL
                }
            }
            val pBlob = o.field("passportPhoto").str().orEmpty()
            val fBlob = o.field("facePhoto").str().orEmpty()
            val v = ContentValues().apply {
                fun s(k: String) = o.field(k).str().orEmpty()
                put("msisdn", msisdn); put("iccid", s("iccid")); put("imsi", s("imsi")); put("contract_id", s("contractId"))
                put("account_no", s("accountNo")); put("region", s("region")); put("in_mts", if (o.field("inMts") == true) 1 else 0)
                put("surname", s("surname")); put("name", s("name")); put("patronymic", s("patronymic"))
                put("sex", s("sex").take(1).ifEmpty { null }); put("birth_date", s("birthDate").ifEmpty { null }); put("birth_place", s("birthPlace"))
                put("series", s("series")); put("number", s("number")); put("issue_date", s("issueDate").ifEmpty { null })
                put("division_code", s("divisionCode")); put("issued_by", s("issuedBy"))
                val a = o.field("address")
                put("addr_region", a.field("region").str().orEmpty()); put("addr_city", a.field("city").str().orEmpty())
                put("addr_street", a.field("street").str().orEmpty()); put("addr_house", a.field("house").str().orEmpty())
                put("addr_apartment", a.field("apartment").str().orEmpty()); put("addr_zip", a.field("zip").str().orEmpty())
                put("notes", s("notes")); put("message_id", s("messageId")); put("op_status", s("opStatus")); put("confirmation", s("confirmation"))
                put("sent_at", (o.field("sentAt") as? Number)?.toLong() ?: 0L); put("checked_at", (o.field("checkedAt") as? Number)?.toLong() ?: 0L)
                put("updated_at", remoteUpdated)
                put("remote_id", id); put("remote_rev", rev); put("dirty", 0)
            }
            // Фото: скачиваем и расшифровываем, только если сменилось. Новое фото с телефона, ещё не отправленное,
            // не затираем: оставляем его и отправим карточку заново уже с ним.
            var keepLocal = false
            for ((kind, remoteBlob, idx) in listOf(Triple("passport", pBlob, 5), Triple("face", fBlob, 6))) {
                val localBlob = row?.get(idx).orEmpty(); val localPath = row?.get(idx + 2).orEmpty()
                if (row != null && row[3] == "1" && localBlob.isEmpty() && localPath.isNotEmpty() && remoteBlob != localBlob) {
                    v.put("${kind}_photo", localPath); v.put("${kind}_blob", "")
                    keepLocal = true
                } else {
                    photo(v, remoteBlob, localBlob, localPath, kind, blobs)
                    // Фото снова используется — не удалять его с сервера.
                    if (remoteBlob.isNotEmpty()) db.delete("vault_deletes", "remote_id = ? AND kind = 'blob'", arrayOf(remoteBlob))
                }
            }
            if (keepLocal) { v.put("dirty", 1); v.put("updated_at", remoteUpdated + 1) }
            if (row == null) {
                db.insertOrThrow("sims", null, v)
                // Удалили на телефоне, а в веб-версии запись изменили — правка важнее удаления.
                db.delete("vault_deletes", "remote_id = ?", arrayOf(id))
            } else db.update("sims", v, "id = ?", arrayOf(row[0]))
            return ApplyResult.APPLIED
        }

        private fun photo(v: ContentValues, blob: String, oldBlob: String, oldPath: String, kind: String, blobs: BlobIo) {
            val col = if (kind == "passport") "passport" else "face"
            if (blob == oldBlob && (blob.isEmpty() || File(oldPath).exists())) return
            if (blob.isEmpty()) {
                SimPhotos.delete(oldPath)
                v.put("${col}_photo", ""); v.put("${col}_blob", "")
                return
            }
            val bytes = try {
                blobs.download(blob)
            } catch (e: VaultException) {
                if (e.code != 404) throw e
                // Фото на сервере нет — карточку принимаем без него, синхронизацию не останавливаем.
                v.put("${col}_photo", ""); v.put("${col}_blob", blob)
                return
            }
            v.put("${col}_photo", SimPhotos.saveBytes(context, bytes, kind))
            v.put("${col}_blob", blob)
            SimPhotos.delete(oldPath)
        }

        private fun applyContract(id: String, rev: Long, o: Any?): ApplyResult {
            val cid = o.field("id").str().orEmpty()
            if (cid.isEmpty()) return ApplyResult.SKIPPED
            val row = find("SELECT id, remote_id, remote_rev, dirty FROM contracts WHERE remote_id = ?", id)
                ?: find("SELECT id, remote_id, remote_rev, dirty FROM contracts WHERE id = ?", cid)
            if (row != null) {
                if (row[1].isNotEmpty() && row[1] != id) return ApplyResult.SKIPPED
                if (row[1] == id && row[2].toLong() >= rev) return ApplyResult.SKIPPED
                if (row[3] == "1") {
                    db.update("contracts", ContentValues().apply { put("remote_id", id); put("remote_rev", rev) }, "id = ?", arrayOf(row[0]))
                    return ApplyResult.KEPT_LOCAL
                }
            }
            val v = ContentValues().apply {
                put("org_name", o.field("orgName").str().orEmpty()); put("label", o.field("label").str().orEmpty())
                put("inn", o.field("inn").str()); put("remote_id", id); put("remote_rev", rev); put("dirty", 0)
            }
            if (row == null) { v.put("id", cid); db.insertOrThrow("contracts", null, v) }
            else db.update("contracts", v, "id = ?", arrayOf(row[0]))
            return ApplyResult.APPLIED
        }

        override fun pending(blobs: BlobIo): List<Pending> {
            val out = mutableListOf<Pending>()
            // Удалённое на телефоне. Фото удаляем сразу, записи — через движок (с проверкой версии).
            db.rawQuery("SELECT remote_id, remote_rev, kind FROM vault_deletes", null).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getString(0)
                    if (c.getString(2) == "blob") {
                        if (runCatching { blobs.delete(rid) }.isSuccess) db.delete("vault_deletes", "remote_id = ?", arrayOf(rid))
                    } else out += Pending("d:$rid", rid, c.getLong(1), null)
                }
            }
            db.rawQuery("SELECT id, org_name, label, inn, remote_id, remote_rev FROM contracts WHERE dirty = 1", null).use { c ->
                while (c.moveToNext()) out += Pending("c:${c.getString(0)}", c.getString(4), c.getLong(5), Json.write(mapOf(
                    "t" to "contract", "id" to c.getString(0), "orgName" to c.getString(1), "label" to c.getString(2),
                    "inn" to (if (c.isNull(3)) null else c.getString(3)), "updatedAt" to System.currentTimeMillis(),
                )))
            }
            val sims = db.rawQuery("SELECT id FROM sims WHERE dirty = 1", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            val simDb = SimDb.get(context)
            for (localId in sims) {
                val s = simDb.byId(localId) ?: continue
                val (pBlob, fBlob, rid, rrev) = find("SELECT passport_blob, face_blob, remote_id, remote_rev FROM sims WHERE id = ?", localId.toString()) ?: continue
                val pb = pBlob.ifEmpty { upload(s.passportPhoto, blobs)?.also { setCol(localId, "passport_blob", it) }.orEmpty() }
                val fb = fBlob.ifEmpty { upload(s.facePhoto, blobs)?.also { setCol(localId, "face_blob", it) }.orEmpty() }
                out += Pending("s:$localId:${s.updatedAt}", rid, rrev.toLong(), simJson(s, pb, fb), s.msisdn)
            }
            return out
        }

        /** Фото уходит на сервер уменьшенным (до 2000 px) и зашифрованным. */
        private fun upload(path: String, blobs: BlobIo): String? {
            val bmp = SimPhotos.load(path, 2000) ?: return null
            var q = 88
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                q -= 12
            } while (bytes.size > 3_800_000 && q > 30)
            bmp.recycle()
            return blobs.upload(bytes)
        }

        override fun pushed(p: Pending, id: String, rev: Long) {
            val parts = p.key.split(':')
            when (parts[0]) {
                "c" -> db.update("contracts", ContentValues().apply { put("remote_id", id); put("remote_rev", rev); put("dirty", 0) }, "id = ?", arrayOf(parts[1]))
                "s" -> {
                    // Если карточку успели изменить во время отправки, она останется помеченной.
                    db.update("sims", ContentValues().apply { put("remote_id", id); put("remote_rev", rev) }, "id = ?", arrayOf(parts[1]))
                    db.update("sims", ContentValues().apply { put("dirty", 0) }, "id = ? AND updated_at = ?", arrayOf(parts[1], parts[2]))
                }
            }
        }

        override fun deletePushed(p: Pending) {
            db.delete("vault_deletes", "remote_id = ?", arrayOf(p.remoteId))
        }

        private fun setCol(id: Long, col: String, value: String) =
            db.update("sims", ContentValues().apply { put(col, value) }, "id = ?", arrayOf(id.toString()))

        private fun find(sql: String, arg: String): List<String>? = db.rawQuery(sql, arrayOf(arg)).use { c: Cursor ->
            if (!c.moveToFirst()) null else List(c.columnCount) { if (c.isNull(it)) "" else c.getString(it) }
        }
    }
}
