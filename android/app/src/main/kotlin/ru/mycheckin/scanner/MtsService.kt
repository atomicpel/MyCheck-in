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
import java.util.UUID

/**
 * Доступ к МТС Бизнес API: у каждого контракта (пакета запросов) свои «Логин и пароль для интеграции».
 * [title] — название для себя, например «Ростовский».
 */
data class MtsAccount(val id: String, val title: String, val login: String, val password: String) {
    val isConfigured: Boolean get() = login.isNotBlank() && password.isNotBlank()
}

/**
 * Доступы и клиенты МТС Бизнес API. Логины и пароли хранятся в зашифрованном хранилище (Android Keystore).
 * На каждый доступ — свой клиент (кеш токена) и свой ограничитель частоты
 * (8/с, 280/мин, 12,5 млн/мес — с запасом к тарифу 10/с, 300/мин).
 */
object MtsService {
    private const val ACCOUNTS = "accounts"
    private const val TOKEN_URL = "token_url"
    private const val API_BASE = "api_base_url"
    // Прежний одиночный доступ (сборки 1–5) — переносится в список при первом запуске.
    private const val OLD_LOGIN = "consumer_key"
    private const val OLD_PASSWORD = "consumer_secret"

    private val clients = HashMap<String, MtsClient>()
    private val quietClients = HashMap<String, MtsClient>()
    private val limiters = HashMap<String, RateLimiter>()
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
        ).also { prefs = it; migrate(it) }
    }

    private fun migrate(p: SharedPreferences) {
        val login = p.getString(OLD_LOGIN, null)
        if (p.contains(ACCOUNTS) || login.isNullOrBlank()) return
        val acc = MtsAccount(UUID.randomUUID().toString(), "Контракт 1", login, p.getString(OLD_PASSWORD, "").orEmpty())
        p.edit().putString(ACCOUNTS, encode(listOf(acc))).remove(OLD_LOGIN).remove(OLD_PASSWORD).apply()
    }

    private fun encode(list: List<MtsAccount>) = Json.write(list.map {
        linkedMapOf("id" to it.id, "title" to it.title, "login" to it.login, "password" to it.password)
    })

    @Synchronized
    fun accounts(context: Context): List<MtsAccount> {
        val raw = securePrefs(context).getString(ACCOUNTS, null) ?: return emptyList()
        return Json.parseOrNull(raw).list().mapNotNull { e ->
            MtsAccount(
                id = e.field("id").str() ?: return@mapNotNull null,
                title = e.field("title").str().orEmpty(),
                login = e.field("login").str().orEmpty(),
                password = e.field("password").str().orEmpty(),
            )
        }
    }

    fun account(context: Context, id: String?): MtsAccount? = id?.let { i -> accounts(context).firstOrNull { it.id == i } }

    fun hasAccounts(context: Context): Boolean = accounts(context).any { it.isConfigured }

    @Synchronized
    fun saveAccount(context: Context, a: MtsAccount) {
        val list = accounts(context)
        val updated = if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a
        securePrefs(context).edit().putString(ACCOUNTS, encode(updated)).apply()
        clients.remove(a.id)   // новый логин — новый токен
        quietClients.remove(a.id)
    }

    @Synchronized
    fun deleteAccount(context: Context, id: String) {
        securePrefs(context).edit().putString(ACCOUNTS, encode(accounts(context).filter { it.id != id })).apply()
        clients.remove(id)
        quietClients.remove(id)
    }

    fun newAccountId(): String = UUID.randomUUID().toString()

    fun tokenUrl(context: Context): String =
        securePrefs(context).getString(TOKEN_URL, null)?.ifBlank { null } ?: MtsConfig.DEFAULT_TOKEN_URL

    fun apiBase(context: Context): String =
        securePrefs(context).getString(API_BASE, null)?.ifBlank { null } ?: MtsConfig.DEFAULT_API_BASE

    @Synchronized
    fun saveAddresses(context: Context, tokenUrl: String, apiBase: String) {
        securePrefs(context).edit().putString(TOKEN_URL, tokenUrl).putString(API_BASE, apiBase).apply()
        clients.clear()
        quietClients.clear()
    }

    /** Счётчик вызовов за месяц — свой у каждого доступа, переживает перезапуск приложения. */
    @Synchronized
    fun limiter(context: Context, accountId: String): RateLimiter {
        limiters[accountId]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("mts_usage", Context.MODE_PRIVATE)
        val store = object : RateLimiter.MonthlyStore {
            override fun get(month: String) = prefs.getLong("$accountId:$month", 0L)
            override fun set(month: String, value: Long) {
                prefs.edit().putLong("$accountId:$month", value).apply()
            }
        }
        return RateLimiter(perSecond = 8, perMinute = 280, perMonth = 12_500_000, monthlyStore = store).also { limiters[accountId] = it }
    }

    @Synchronized
    fun client(context: Context, account: MtsAccount): MtsClient {
        clients[account.id]?.let { return it }
        val config = MtsConfig(account.login, account.password, tokenUrl(context), apiBase(context))
        val app = context.applicationContext
        return MtsClient(config, limiter(context, account.id), log = { Journal.http(app, it) }).also { clients[account.id] = it }
    }

    /**
     * Клиент для фонового обновления сведений: тот же ограничитель доступа (общий счёт вызовов
     * с экранами), но в журнал попадают только неудачные обращения — сотни успешных чтений
     * баланса и услуг журнал не засоряют.
     */
    @Synchronized
    fun quietClient(context: Context, account: MtsAccount): MtsClient {
        quietClients[account.id]?.let { return it }
        val config = MtsConfig(account.login, account.password, tokenUrl(context), apiBase(context))
        val app = context.applicationContext
        return MtsClient(config, limiter(context, account.id), log = { if (!it.ok) Journal.http(app, it) })
            .also { quietClients[account.id] = it }
    }
}
