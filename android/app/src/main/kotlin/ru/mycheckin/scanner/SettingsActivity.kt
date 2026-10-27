package ru.mycheckin.scanner

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mycheckin.passport.mts.MtsConfig
import java.util.concurrent.Executors

/** Доступ к МТС Бизнес API: логин и пароль для интеграции, проверка подключения, расход лимита. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var login: Ui.Field
    private lateinit var password: Ui.Field
    private lateinit var tokenUrl: Ui.Field
    private lateinit var apiBase: Ui.Field
    private lateinit var usage: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val c = MtsService.load(this)
        val root = ui.column()

        root.addView(ui.text("Настройки МТС Бизнес API", 24f, bold = true))

        root.addView(ui.section("Логин и пароль для доступа к API"))
        root.addView(ui.text(
            "Где взять: личный кабинет МТС Бизнес → «API для бизнеса» → у подключённого пакета запросов " +
                "нажмите «Логин и пароль для интеграции». Это не логин от личного кабинета. " +
                "В документации МТС они называются Consumer Key и Consumer Secret.",
            14f, secondary = true,
        ))
        login = ui.field("Логин для интеграции (Consumer Key)").also { root.addView(it.layout) }
        password = ui.field("Пароль для интеграции (Consumer Secret)", Ui.Kind.PASSWORD).also { root.addView(it.layout) }
        root.addView(ui.text("Хранятся на телефоне в зашифрованном виде и передаются только на api.mts.ru.", 12f, secondary = true)
            .apply { setPadding(0, ui.px(4), 0, 0) })

        root.addView(ui.button("Сохранить", filled = true) {
            if (save()) Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(14) })
        root.addView(ui.button("Сохранить и проверить подключение") { check() })

        root.addView(ui.section("Заявки"))
        root.addView(ui.button("Отправленные заявки и статусы") { startActivity(Intent(this, HistoryActivity::class.java)) })

        root.addView(ui.section("Лимиты тарифа"))
        usage = ui.text("", 14f)
        root.addView(usage)
        root.addView(ui.text(
            "Тариф: до 10 вызовов в секунду, 300 в минуту, ~12,5 млн в месяц. Приложение ограничивает себя " +
                "8 вызовами в секунду и 280 в минуту, токен хранит час, при ответе 429 ждёт и повторяет. " +
                "Одна отправка паспорта — 1–2 вызова (плюс получение токена раз в час). " +
                "Ограничение действует на этом телефоне: если логин используют несколько устройств, лимит у них общий.",
            12f, secondary = true,
        ))

        root.addView(ui.section("Дополнительно"))
        root.addView(ui.text("Менять не нужно, если МТС не сообщил новые адреса.", 12f, secondary = true))
        tokenUrl = ui.field("Адрес получения токена", Ui.Kind.URL).also { root.addView(it.layout) }
        apiBase = ui.field("Адрес МТС Бизнес API", Ui.Kind.URL).also { root.addView(it.layout) }
        root.addView(ui.button("Вернуть адреса по умолчанию") {
            tokenUrl.value = MtsConfig.DEFAULT_TOKEN_URL
            apiBase.value = MtsConfig.DEFAULT_API_BASE
        })

        login.value = c.login
        password.value = c.password
        tokenUrl.value = c.tokenUrl
        apiBase.value = c.apiBaseUrl
        updateUsage()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
            addView(root)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun updateUsage() {
        val l = MtsService.limiter(this)
        usage.text = "Вызовов в этом месяце: %,d из %,d".format(l.usedThisMonth(), l.monthlyLimit)
    }

    private fun current() = MtsConfig(
        login = login.value,
        password = password.value,
        tokenUrl = tokenUrl.value.ifBlank { MtsConfig.DEFAULT_TOKEN_URL },
        apiBaseUrl = apiBase.value.ifBlank { MtsConfig.DEFAULT_API_BASE }.trimEnd('/'),
    )

    private fun save(): Boolean {
        val c = current()
        listOf(login, password, tokenUrl, apiBase).forEach { it.error = null }
        if (c.login.isBlank()) login.error = "Введите логин для интеграции"
        if (c.password.isBlank()) password.error = "Введите пароль для интеграции"
        if (!c.tokenUrl.startsWith("https://")) tokenUrl.error = "Только https://"
        if (!c.apiBaseUrl.startsWith("https://")) apiBase.error = "Только https://"
        if (listOf(login, password, tokenUrl, apiBase).any { it.error != null }) return false
        MtsService.save(this, c)
        return true
    }

    private fun check() {
        if (!save()) return
        executor.execute {
            val msg = try {
                MtsService.client(this).token(force = true)
                "Логин и пароль приняты, токен получен. Подключение работает."
            } catch (e: Exception) {
                "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                updateUsage()
                MaterialAlertDialogBuilder(this).setTitle("Проверка подключения").setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
        }
    }
}
