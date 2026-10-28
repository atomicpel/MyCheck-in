package ru.mycheckin.passport.vault

/** Локальное изменение для отправки на сервер. [json] == null — запись удалена на телефоне. */
data class Pending(val key: String, val remoteId: String, val remoteRev: Long, val json: String?, val label: String = "")

/** Шифрованные фото: загрузка на сервер и скачивание (ключ хранилища — внутри). */
interface BlobIo {
    /** Зашифровать и загрузить; возвращает id фото на сервере. */
    fun upload(bytes: ByteArray): String
    fun download(id: String): ByteArray
    fun delete(id: String)
}

/**
 * Локальная база телефона. Правила слияния (кто «победил») решает она:
 * изменения на телефоне, которые ещё не отправлены, сравниваются с пришедшими по времени изменения.
 */
interface SyncStore {
    var cursor: Long
    /**
     * Запись с сервера. [json] == null — удалена на сервере.
     * Если на телефоне есть более свежая неотправленная правка, store оставляет её и запоминает [rev],
     * чтобы следующая отправка прошла поверх.
     */
    fun applyRemote(id: String, rev: Long, json: String?, blobs: BlobIo): ApplyResult
    /** Что нужно отправить. Может загрузить фото через [blobs]. */
    fun pending(blobs: BlobIo): List<Pending>
    fun pushed(p: Pending, id: String, rev: Long)
    fun deletePushed(p: Pending)
}

enum class ApplyResult { APPLIED, SKIPPED, KEPT_LOCAL }

data class SyncReport(
    val received: Int, val sent: Int, val deleted: Int, val keptLocal: Int, val conflicts: Int,
    val undecryptable: Int, val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty() && undecryptable == 0
}

/** Синхронизация: сначала получить изменения, потом отправить свои. Всё шифруется ключом хранилища. */
class SyncEngine(private val api: VaultApi, private val vaultKey: ByteArray, private val store: SyncStore) {

    val blobs = object : BlobIo {
        override fun upload(bytes: ByteArray): String {
            val id = VaultCrypto.newId()
            api.putBlob(id, VaultCrypto.encryptBlob(vaultKey, id, bytes))
            return id
        }
        override fun download(id: String): ByteArray = VaultCrypto.decryptBlob(vaultKey, id, api.getBlob(id))
        override fun delete(id: String) = api.deleteBlob(id)
    }

    private var received = 0; private var sent = 0; private var deleted = 0; private var kept = 0
    private var conflicts = 0; private var bad = 0
    private val errors = mutableListOf<String>()

    fun run(): SyncReport {
        pull()
        val c = push()
        // Конфликт: запись успели изменить в веб-версии. Получаем свежую и пробуем ещё раз.
        if (c > 0) { pull(); push() }
        return SyncReport(received, sent, deleted, kept, conflicts, bad, errors.toList())
    }

    private fun pull() {
        val (rev, recs) = api.records(store.cursor)
        for (r in recs) apply(r)
        store.cursor = rev
    }

    private fun apply(r: RemoteRecord) {
        val json = if (r.deleted) null else try {
            VaultCrypto.decryptRecord(vaultKey, r.id, r.data)
        } catch (e: Exception) {
            // Не расшифровалось: чужой ключ или подмена на сервере. Не применяем.
            bad++; return
        }
        try {
            when (store.applyRemote(r.id, r.rev, json, blobs)) {
                ApplyResult.APPLIED -> received++
                ApplyResult.KEPT_LOCAL -> kept++
                ApplyResult.SKIPPED -> Unit
            }
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            errors += "Запись ${r.id.take(8)}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Возвращает число конфликтов. */
    private fun push(): Int {
        var c = 0
        for (p in store.pending(blobs)) {
            if (p.json == null) {
                if (p.remoteId.isEmpty()) { store.deletePushed(p); continue }
                when (val r = api.deleteRecord(p.remoteId, p.remoteRev)) {
                    is PutResult.Ok -> { store.deletePushed(p); deleted++ }
                    is PutResult.Conflict -> { c++; conflicts++; r.current?.let(::apply) }
                }
                continue
            }
            val id = p.remoteId.ifEmpty { VaultCrypto.newId() }
            when (val r = api.putRecord(id, p.remoteRev, VaultCrypto.encryptRecord(vaultKey, id, p.json))) {
                is PutResult.Ok -> { store.pushed(p, id, r.rev); sent++ }
                is PutResult.Conflict -> { c++; conflicts++; r.current?.let(::apply) }
            }
        }
        return c
    }
}
