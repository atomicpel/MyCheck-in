package ru.mycheckin.scanner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.MtsClient
import ru.mycheckin.passport.mts.MtsConfig
import ru.mycheckin.passport.mts.RateLimiter
import ru.mycheckin.passport.mts.field
import ru.mycheckin.passport.mts.list
import ru.mycheckin.passport.mts.str

/** Отправленная заявка: номер телефона, владелец (Фамилия И. О.), номер заявки МТС и последний известный статус. */
data class Submission(
    val phone: String,
    val holder: String,
    val messageId: String,
    val sentAt: Long,
    val status: String = "",
    val confirmation: String = "",
    val checkedAt: Long = 0,
)

/**
 * Настройки и клиент МТС Бизнес API. Логин, пароль и журнал заявок хранятся
 * в зашифрованном хранилище (Android Keystore). Клиент один на приложение: общий кеш
 * токена и общий ограничитель частоты (8/с, 280/мин, 12,5 млн/мес — с запасом к тарифу 10/с, 300/мин).
 */
object MtsService {
    // Ключи хранилища остались прежними, чтобы введённые ранее данные сохранились.
    private const val LOGIN = "consumer_key"
    private const val PASSWORD = "consumer_secret"
    private const val TOKEN_URL = "token_url"
    private const val API_BASE = "api_base_url"
    private const val LOG = "submissions"
    private const val LOG_MAX = 200

    private var client: MtsClient? = null
    private var limiter: RateLimiter? = null
    private var prefs: SharedPreferences? = null

    @Synchronized
    private fun securePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, "mts_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { prefs = it }
    }

    fun load(context: Context): MtsConfig {
        val p = securePrefs(context)
        return MtsConfig(
            login = p.getString(LOGIN, "").orEmpty(),
            password = p.getString(PASSWORD, "").orEmpty(),
            tokenUrl = p.getString(TOKEN_URL, null)?.ifBlank { null } ?: MtsConfig.DEFAULT_TOKEN_URL,
            apiBaseUrl = p.getString(API_BASE, null)?.ifBlank { null } ?: MtsConfig.DEFAULT_API_BASE,
        )
    }

    @Synchronized
    fun save(context: Context, c: MtsConfig) {
        securePrefs(context).edit()
            .putString(LOGIN, c.login.trim()).putString(PASSWORD, c.password.trim())
            .putString(TOKEN_URL, c.tokenUrl).putString(API_BASE, c.apiBaseUrl)
            .apply()
        client = null  // пересоздать с новыми настройками (токен тоже)
    }

    /** Счётчик вызовов за месяц переживает перезапуск приложения. */
    @Synchronized
    fun limiter(context: Context): RateLimiter {
        limiter?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("mts_usage", Context.MODE_PRIVATE)
        val store = object : RateLimiter.MonthlyStore {
            override fun get(month: String) = prefs.getLong(month, 0L)
            override fun set(month: String, value: Long) {
                prefs.edit().putLong(month, value).apply()
            }
        }
        return RateLimiter(perSecond = 8, perMinute = 280, perMonth = 12_500_000, monthlyStore = store).also { limiter = it }
    }

    @Synchronized
    fun client(context: Context): MtsClient {
        client?.let { return it }
        return MtsClient(load(context), limiter(context)).also { client = it }
    }

    // ---- Журнал отправленных заявок ----

    @Synchronized
    fun submissions(context: Context): List<Submission> {
        val raw = securePrefs(context).getString(LOG, null) ?: return emptyList()
        return Json.parseOrNull(raw).list().mapNotNull { e ->
            val id = e.field("messageId").str() ?: return@mapNotNull null
            Submission(
                phone = e.field("phone").str().orEmpty(),
                holder = e.field("holder").str().orEmpty(),
                messageId = id,
                sentAt = e.field("sentAt").str()?.toLongOrNull() ?: 0,
                status = e.field("status").str().orEmpty(),
                confirmation = e.field("confirmation").str().orEmpty(),
                checkedAt = e.field("checkedAt").str()?.toLongOrNull() ?: 0,
            )
        }
    }

    @Synchronized
    private fun write(context: Context, list: List<Submission>) {
        val json = Json.write(list.take(LOG_MAX).map {
            linkedMapOf(
                "phone" to it.phone, "holder" to it.holder, "messageId" to it.messageId, "sentAt" to it.sentAt,
                "status" to it.status, "confirmation" to it.confirmation, "checkedAt" to it.checkedAt,
            )
        })
        securePrefs(context).edit().putString(LOG, json).apply()
    }

    @Synchronized
    fun addSubmission(context: Context, s: Submission) = write(context, listOf(s) + submissions(context).filter { it.messageId != s.messageId })

    @Synchronized
    fun updateSubmission(context: Context, s: Submission) = write(context, submissions(context).map { if (it.messageId == s.messageId) s else it })

    @Synchronized
    fun removeSubmission(context: Context, messageId: String) = write(context, submissions(context).filter { it.messageId != messageId })
}
