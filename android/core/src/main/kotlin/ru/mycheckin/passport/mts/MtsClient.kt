package ru.mycheckin.passport.mts

import ru.mycheckin.passport.PassportData
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** Транспорт HTTP (подменяется в тестах). */
fun interface Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

class UrlConnectionHttp(private val timeoutMs: Int = 30_000) : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val hdrs = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return HttpResponse(code, text, hdrs)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Доступ к МТС Бизнес API. [login] и [password] — «Логин и пароль для интеграции»
 * из личного кабинета МТС Бизнес (раздел «API для бизнеса», рядом с подключённым пакетом).
 * В документации МТС их также называют Consumer Key и Consumer Secret.
 */
data class MtsConfig(
    val login: String,
    val password: String,
    val tokenUrl: String = DEFAULT_TOKEN_URL,
    val apiBaseUrl: String = DEFAULT_API_BASE,
) {
    val isConfigured: Boolean get() = login.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_TOKEN_URL = "https://api.mts.ru/token"
        const val DEFAULT_API_BASE = "https://api.mts.ru/b2b/v1"
    }
}

class MtsApiException(message: String, val response: HttpResponse? = null) : Exception(message)

/**
 * Клиент МТС Бизнес API. Все вызовы (включая получение токена) проходят через
 * [limiter]; токен кешируется до истечения (живёт 1 час), чтобы не тратить лишние вызовы.
 */
class MtsClient(
    private val config: MtsConfig,
    private val limiter: RateLimiter,
    private val http: Http = UrlConnectionHttp(),
    private val clock: RateLimiter.Clock = RateLimiter.Clock.System,
    private val maxRetries: Int = 3,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) {
    private var token: String? = null
    private var tokenExpiresAt = 0L

    private fun requireHttps(url: String) {
        require(url.startsWith("https://")) { "Адрес должен начинаться с https://: $url" }
    }

    /** Вызов с учётом лимитов; при 429/503 — повтор с паузой (Retry-After или 1-2-4 с). */
    private fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        var attempt = 0
        while (true) {
            limiter.acquire()
            val r = http.request(method, url, headers, body)
            if ((r.code == 429 || r.code == 503) && attempt < maxRetries) {
                val retryAfter = r.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim()?.toLongOrNull()
                clock.sleep((retryAfter?.times(1000)) ?: (1000L shl attempt))
                attempt++
                continue
            }
            return r
        }
    }

    @Synchronized
    fun token(force: Boolean = false): String {
        val cached = token
        if (!force && cached != null && clock.nowMillis() < tokenExpiresAt) return cached
        requireHttps(config.tokenUrl)
        require(config.isConfigured) { "Не заданы логин и пароль для интеграции МТС Бизнес API" }
        val basic = Base64.getEncoder().encodeToString("${config.login.trim()}:${config.password.trim()}".toByteArray())
        val r = call(
            "POST", config.tokenUrl,
            mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
            ),
            "grant_type=client_credentials",
        )
        if (r.code == 400 || r.code == 401) {
            throw MtsApiException("МТС не принял логин и пароль для интеграции (HTTP ${r.code}). Проверьте их в ЛК МТС Бизнес → API для бизнеса.", r)
        }
        if (r.code != 200) throw MtsApiException("Не удалось получить токен: HTTP ${r.code}. ${PersonalDataApi.describeHttpError(r.body)}", r)
        val j = Json.parseOrNull(r.body)
        val access = j.field("access_token").str()?.ifBlank { null } ?: throw MtsApiException("В ответе нет access_token", r)
        val expiresIn = j.field("expires_in").str()?.toLongOrNull() ?: 3600
        token = access
        // Обновляем за минуту до истечения.
        tokenExpiresAt = clock.nowMillis() + maxOf(0, expiresIn - 60) * 1000
        return access
    }

    /** Авторизованный вызов метода API. При 401 токен обновляется и запрос повторяется один раз. */
    private fun api(method: String, url: String, extraHeaders: Map<String, String>, body: String?): HttpResponse {
        requireHttps(url)
        fun send(t: String) = call(
            method, url,
            linkedMapOf(
                "Authorization" to "Bearer $t",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ) + extraHeaders,
            body,
        )
        var r = send(token())
        if (r.code == 401) r = send(token(force = true))
        if (r.code !in 200..299) {
            throw MtsApiException("МТС ответил HTTP ${r.code}: ${PersonalDataApi.describeHttpError(r.body)}", r)
        }
        return r
    }

    private fun url(path: String) = config.apiBaseUrl.trimEnd('/') + path

    /**
     * Внести персональные данные пользователя номера (гражданин РФ, паспорт РФ).
     * Возвращает номер заявки (MessageId) — по нему проверяется статус [operationResult].
     */
    fun submitPersonalData(p: PassportData, phone: String, address: RegAddress? = null): SubmitResult {
        val msisdn = PersonalDataApi.normalizeMsisdn(phone)
        val problems = PersonalDataApi.problems(p, msisdn, address)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        val body = PersonalDataApi.changeBody(p, msisdn!!, newMessageId(), address)
        val r = api(
            "POST", url(PersonalDataApi.CHANGE_PATH),
            mapOf("x-soap-action" to PersonalDataApi.CHANGE_SOAP_ACTION, "X-MTS-MSISDN" to msisdn),
            body,
        )
        return PersonalDataApi.parseSubmit(r.body)
    }

    /** Статус заявки на внесение данных: Completed / InProgress / Faulted. */
    fun operationResult(phone: String, messageId: String): OperationResult {
        val msisdn = PersonalDataApi.normalizeMsisdn(phone) ?: throw IllegalArgumentException("Неверный номер телефона")
        val r = api(
            "POST", url(PersonalDataApi.RESULT_PATH),
            mapOf("x-soap-action" to PersonalDataApi.RESULT_SOAP_ACTION, "X-MTS-MSISDN" to msisdn),
            PersonalDataApi.resultBody(messageId),
        )
        return PersonalDataApi.parseResult(r.body, msisdn)
    }

    /** Статус подтверждения данных на номере через Госуслуги. */
    fun confirmationStatus(phone: String): ConfirmationStatus {
        val msisdn = PersonalDataApi.normalizeMsisdn(phone) ?: throw IllegalArgumentException("Неверный номер телефона")
        val q = URLEncoder.encode(msisdn, "UTF-8")
        val r = api("GET", url(PersonalDataApi.INFO_PATH) + "?contactMedium.phoneNumber=$q", emptyMap(), null)
        return PersonalDataApi.parseConfirmation(r.body)
    }
}
