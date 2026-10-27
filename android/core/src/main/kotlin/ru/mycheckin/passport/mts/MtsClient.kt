package ru.mycheckin.passport.mts

import ru.mycheckin.passport.PassportData
import java.net.HttpURLConnection
import java.net.URI
import java.time.format.DateTimeFormatter
import java.util.Base64

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** Транспорт HTTP (подменяется в тестах). */
fun interface Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

class UrlConnectionHttp(private val timeoutMs: Int = 20_000) : Http {
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
 * Настройки МТС Бизнес API. Токен выдаётся по Consumer Key / Consumer Secret
 * из личного кабинета (раздел «API для бизнеса»). Адрес и тело запроса внесения
 * персональных данных задаются в настройках — по статье справки
 * «Как внести персональные данные пользователя номера с помощью МТС Бизнес API».
 */
data class MtsConfig(
    val consumerKey: String,
    val consumerSecret: String,
    val submitUrl: String,
    val submitMethod: String = "POST",
    val bodyTemplate: String = DEFAULT_TEMPLATE,
    val tokenUrl: String = "https://api.mts.ru/token",
) {
    companion object {
        /** Шаблон-заготовка: названия полей нужно сверить со справкой МТС. */
        val DEFAULT_TEMPLATE = """
            {
              "msisdn": "{{phone}}",
              "lastName": "{{surname}}",
              "firstName": "{{name}}",
              "middleName": "{{patronymic}}",
              "gender": "{{sex}}",
              "birthDate": "{{birth_date}}",
              "birthPlace": "{{birth_place}}",
              "citizenship": "{{citizenship}}",
              "document": {
                "type": "PASSPORT_RF",
                "series": "{{series}}",
                "number": "{{number}}",
                "issueDate": "{{issue_date}}",
                "issuedBy": "{{issued_by}}",
                "divisionCode": "{{division_code}}"
              }
            }
        """.trimIndent()
    }
}

class MtsApiException(message: String, val response: HttpResponse? = null) : Exception(message)

/** Подстановка значений в шаблон {{ключ}} с экранированием для JSON. */
object Template {
    private val PLACEHOLDER = Regex("\\{\\{\\s*([a-z_]+)\\s*}}")

    fun placeholders(template: String): Set<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    fun render(template: String, values: Map<String, String?>): String = PLACEHOLDER.replace(template) { m ->
        val key = m.groupValues[1]
        require(key in values) { "Неизвестное поле в шаблоне: {{$key}}" }
        jsonEscape(values[key].orEmpty())
    }

    private fun jsonEscape(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    val KEYS = listOf(
        "phone", "surname", "name", "patronymic", "sex", "sex_ru", "birth_date", "birth_date_ru", "birth_place",
        "citizenship", "series", "number", "series_number", "issue_date", "issue_date_ru", "issued_by", "division_code",
    )

    /** Значения для шаблона: даты в ISO (birth_date) и по-русски (birth_date_ru). */
    fun values(p: PassportData, phone: String): Map<String, String?> {
        val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        return mapOf(
            "phone" to phone.filter { it.isDigit() }.let { if (it.length == 10) "7$it" else it.replaceFirst(Regex("^8(?=\\d{10}$)"), "7") },
            "surname" to p.surname, "name" to p.name, "patronymic" to p.patronymic,
            "sex" to p.sex?.toString(), "sex_ru" to when (p.sex) { 'M' -> "МУЖ"; 'F' -> "ЖЕН"; else -> null },
            "birth_date" to p.birthDate?.toString(), "birth_date_ru" to p.birthDate?.format(ru),
            "birth_place" to p.birthPlace, "citizenship" to (p.citizenship ?: "RUS"),
            "series" to p.series, "number" to p.number, "series_number" to p.series.orEmpty() + p.number.orEmpty(),
            "issue_date" to p.issueDate?.toString(), "issue_date_ru" to p.issueDate?.format(ru),
            "issued_by" to p.issuedBy, "division_code" to p.divisionCode,
        )
    }
}

/**
 * Клиент МТС Бизнес API. Все вызовы (включая получение токена) проходят через
 * [limiter]; токен кешируется до истечения, чтобы не тратить лишние вызовы.
 */
class MtsClient(
    private val config: MtsConfig,
    private val limiter: RateLimiter,
    private val http: Http = UrlConnectionHttp(),
    private val clock: RateLimiter.Clock = RateLimiter.Clock.System,
    private val maxRetries: Int = 3,
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
        require(config.consumerKey.isNotBlank() && config.consumerSecret.isNotBlank()) { "Не заданы Consumer Key / Consumer Secret" }
        val basic = Base64.getEncoder().encodeToString("${config.consumerKey}:${config.consumerSecret}".toByteArray())
        val r = call(
            "POST", config.tokenUrl,
            mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=client_credentials",
        )
        if (r.code != 200) throw MtsApiException("Не удалось получить токен: HTTP ${r.code}", r)
        val access = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(r.body)?.groupValues?.get(1)
            ?: throw MtsApiException("В ответе нет access_token", r)
        val expiresIn = Regex("\"expires_in\"\\s*:\\s*(\\d+)").find(r.body)?.groupValues?.get(1)?.toLong() ?: 3600
        token = access
        // Обновляем за минуту до истечения.
        tokenExpiresAt = clock.nowMillis() + maxOf(0, expiresIn - 60) * 1000
        return access
    }

    /** Отправить данные паспорта. При 401 токен обновляется и запрос повторяется один раз. */
    fun submit(values: Map<String, String?>): HttpResponse {
        require(config.submitUrl.isNotBlank()) { "Не задан адрес метода внесения персональных данных" }
        requireHttps(config.submitUrl)
        val body = Template.render(config.bodyTemplate, values)
        val url = Template.render(config.submitUrl, values)  // в адресе тоже можно {{phone}}
        fun send(t: String) = call(
            config.submitMethod, url,
            mapOf("Authorization" to "Bearer $t", "Content-Type" to "application/json; charset=utf-8", "Accept" to "application/json"),
            body,
        )
        var r = send(token())
        if (r.code == 401) r = send(token(force = true))
        return r
    }
}
