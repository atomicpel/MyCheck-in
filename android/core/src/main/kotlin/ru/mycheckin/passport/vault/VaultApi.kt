package ru.mycheckin.passport.vault

import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.field
import ru.mycheckin.passport.mts.list
import ru.mycheckin.passport.mts.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class BinResponse(val code: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Транспорт HTTP с двоичным телом (фото). Подменяется в тестах. */
fun interface BinHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinResponse
}

class UrlConnectionBinHttp(private val timeoutMs: Int = 30_000) : BinHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false // токен не должен уйти по перенаправлению на другой адрес
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return BinResponse(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }
}

/** Ошибка сервера хранилища: [code] — HTTP-код (0 — нет связи). Текст — для человека. */
class VaultException(val code: Int, message: String) : Exception(message)

data class RemoteRecord(val id: String, val rev: Long, val deleted: Boolean, val data: String)

data class LoginResult(val token: String, val wrappedKey: String?, val deviceToken: String?, val deviceId: String?)

sealed class PutResult {
    data class Ok(val rev: Long) : PutResult()
    /** Запись изменили на другом устройстве; [current] — её текущая версия на сервере. */
    data class Conflict(val current: RemoteRecord?) : PutResult()
}

/**
 * Клиент сервера «Учёт SIM» (simvault). Сервер видит только зашифрованные данные:
 * шифрование и расшифровка — в [VaultCrypto] на телефоне.
 *
 * Сессия живёт 30 минут без активности. Если задан [deviceToken], при ответе 401 клиент
 * один раз входит заново как доверенное устройство и повторяет запрос.
 */
class VaultApi(baseUrl: String, private val http: BinHttp = UrlConnectionBinHttp()) {
    val base: String = normalizeUrl(baseUrl)
    @Volatile var token: String? = null
    @Volatile var deviceToken: String? = null

    companion object {
        /** Адрес сервера: только https (http — лишь для локальной проверки на этом же устройстве). */
        fun normalizeUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.contains("://")) u = "https://$u"
            val uri = try { URI(u) } catch (e: Exception) { throw IllegalArgumentException("Неверный адрес сервера") }
            val host = uri.host ?: throw IllegalArgumentException("Неверный адрес сервера")
            val local = host == "127.0.0.1" || host == "localhost"
            require(uri.scheme == "https" || (uri.scheme == "http" && local)) { "Нужен адрес https://" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Неверный адрес сервера" }
            return u.removeSuffix("/api/v1")
        }
    }

    // ---- вход ----

    fun initialized(): Boolean = obj(call("GET", "/status", null, auth = false)).field("initialized") == true

    fun prelogin(): Kdf {
        val k = obj(call("GET", "/prelogin", null, auth = false)).field("kdf")
        val iter = (k.field("iter") as? Number)?.toInt() ?: 0
        val kdf = Kdf(k.field("alg").str().orEmpty(), iter, k.field("salt").str().orEmpty())
        // Сервер не должен навязать слабые параметры: тогда пароль было бы легче подобрать.
        if (kdf.alg != "PBKDF2-SHA256" || kdf.iter < 600_000 || kdf.iter > 10_000_000) throw VaultException(0, "Сервер прислал небезопасные параметры ключа")
        if (runCatching { VaultCrypto.unb64(kdf.salt).size }.getOrNull() != 16) throw VaultException(0, "Сервер прислал неверные параметры ключа")
        return kdf
    }

    fun login(authKey: String, totp: String, trustDevice: Boolean, deviceName: String): LoginResult {
        val body = Json.write(mapOf("authKey" to authKey, "totp" to totp, "trustDevice" to trustDevice, "deviceName" to deviceName))
        val r = obj(call("POST", "/login", body.toByteArray(), auth = false))
        val res = LoginResult(r.field("token").str().orEmpty(), r.field("wrappedKey").str(), r.field("deviceToken").str(), r.field("deviceId").str())
        token = res.token
        res.deviceToken?.let { deviceToken = it }
        return res
    }

    fun deviceLogin(): String {
        val dt = deviceToken ?: throw VaultException(401, "Устройство не подключено")
        val r = obj(call("POST", "/device-login", Json.write(mapOf("deviceToken" to dt)).toByteArray(), auth = false))
        return r.field("token").str().orEmpty().also { token = it }
    }

    fun logout() {
        runCatching { call("POST", "/logout", ByteArray(0), auth = true, retryAuth = false) }
        token = null
    }

    // ---- записи ----

    fun records(since: Long): Pair<Long, List<RemoteRecord>> {
        val r = obj(call("GET", "/records?since=$since", null))
        val rev = (r.field("rev") as? Number)?.toLong() ?: since
        return rev to r.field("records").list().mapNotNull(::record)
    }

    fun putRecord(id: String, baseRev: Long, data: String): PutResult {
        val body = Json.write(mapOf("baseRev" to baseRev, "data" to data)).toByteArray()
        return try {
            PutResult.Ok((obj(call("PUT", "/records/${checkId(id)}", body)).field("rev") as Number).toLong())
        } catch (e: ConflictException) {
            PutResult.Conflict(record(e.body.field("record")))
        }
    }

    /** Удалить запись. Если её уже нет — тоже успех. */
    fun deleteRecord(id: String, baseRev: Long): PutResult = try {
        PutResult.Ok((obj(call("DELETE", "/records/${checkId(id)}?baseRev=$baseRev", null)).field("rev") as? Number)?.toLong() ?: 0)
    } catch (e: ConflictException) {
        PutResult.Conflict(record(e.body.field("record")))
    } catch (e: VaultException) {
        if (e.code == 404) PutResult.Ok(0) else throw e
    }

    fun putBlob(id: String, sealed: ByteArray) { call("PUT", "/blobs/${checkId(id)}", sealed, contentType = "application/octet-stream") }

    fun getBlob(id: String): ByteArray = call("GET", "/blobs/${checkId(id)}", null).body

    fun deleteBlob(id: String) {
        try { call("DELETE", "/blobs/${checkId(id)}", null) } catch (e: VaultException) { if (e.code != 404) throw e }
    }

    // ---- служебное ----

    private class ConflictException(val body: Any?) : Exception()

    private fun checkId(id: String): String {
        require(Regex("^[A-Za-z0-9-]{8,64}$").matches(id)) { "Неверный идентификатор записи" }
        return id
    }

    private fun record(o: Any?): RemoteRecord? {
        val id = o.field("id").str() ?: return null
        val rev = (o.field("rev") as? Number)?.toLong() ?: return null
        return RemoteRecord(id, rev, o.field("deleted") == true, o.field("data").str().orEmpty())
    }

    private fun obj(r: BinResponse): Any? = if (r.body.isEmpty()) null else Json.parseOrNull(r.text)

    private fun call(
        method: String, path: String, body: ByteArray?, auth: Boolean = true, retryAuth: Boolean = true,
        contentType: String = "application/json",
    ): BinResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", contentType)
            if (auth) token?.let { put("Authorization", "Bearer $it") }
        }
        val r = try {
            http.request(method, "$base/api/v1$path", headers, body)
        } catch (e: IOException) {
            throw VaultException(0, "Нет связи с сервером: ${e.message ?: e.javaClass.simpleName}")
        }
        if (r.code in 200..299) return r
        if (r.code == 401 && auth && retryAuth && deviceToken != null) {
            deviceLogin()
            return call(method, path, body, auth, retryAuth = false, contentType = contentType)
        }
        val parsed = obj(r)
        if (r.code == 409 && (path.startsWith("/records/"))) throw ConflictException(parsed)
        val msg = parsed.field("error").str() ?: when (r.code) {
            401 -> "Нужно войти снова"
            413 -> "Слишком большой файл"
            429 -> "Слишком много попыток — подождите"
            in 500..599 -> "Ошибка сервера (${r.code})"
            else -> "Ошибка ${r.code}"
        }
        throw VaultException(r.code, msg)
    }
}
