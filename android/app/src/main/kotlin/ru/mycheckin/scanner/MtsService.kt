package ru.mycheckin.scanner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.mycheckin.passport.mts.MtsClient
import ru.mycheckin.passport.mts.MtsConfig
import ru.mycheckin.passport.mts.RateLimiter

/**
 * Настройки и клиент МТС Бизнес API. Ключи хранятся в зашифрованном хранилище
 * (Android Keystore). Клиент один на приложение: общий кеш токена и общий
 * ограничитель частоты (8/с, 280/мин, 12,5 млн/мес — с запасом к тарифу 10/с, 300/мин).
 */
object MtsService {
    private const val KEY = "consumer_key"
    private const val SECRET = "consumer_secret"
    private const val URL = "submit_url"
    private const val METHOD = "submit_method"
    private const val TEMPLATE = "body_template"
    private const val TOKEN_URL = "token_url"

    private var client: MtsClient? = null
    private var limiter: RateLimiter? = null

    private fun securePrefs(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "mts_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): MtsConfig {
        val p = securePrefs(context)
        return MtsConfig(
            consumerKey = p.getString(KEY, "").orEmpty(),
            consumerSecret = p.getString(SECRET, "").orEmpty(),
            submitUrl = p.getString(URL, "").orEmpty(),
            submitMethod = p.getString(METHOD, "POST").orEmpty(),
            bodyTemplate = p.getString(TEMPLATE, null) ?: MtsConfig.DEFAULT_TEMPLATE,
            tokenUrl = p.getString(TOKEN_URL, null) ?: "https://api.mts.ru/token",
        )
    }

    @Synchronized
    fun save(context: Context, c: MtsConfig) {
        securePrefs(context).edit()
            .putString(KEY, c.consumerKey).putString(SECRET, c.consumerSecret)
            .putString(URL, c.submitUrl).putString(METHOD, c.submitMethod)
            .putString(TEMPLATE, c.bodyTemplate).putString(TOKEN_URL, c.tokenUrl)
            .apply()
        client = null  // пересоздать с новыми настройками (токен тоже)
    }

    fun isConfigured(c: MtsConfig) = c.consumerKey.isNotBlank() && c.consumerSecret.isNotBlank() && c.submitUrl.isNotBlank()

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
}
