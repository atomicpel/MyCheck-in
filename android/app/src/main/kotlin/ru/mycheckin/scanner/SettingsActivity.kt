package ru.mycheckin.scanner

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.transition.TransitionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mycheckin.passport.mts.MtsConfig
import java.util.concurrent.Executors

/**
 * Доступы к МТС Бизнес API: у каждого контракта свои «Логин и пароль для интеграции».
 * Здесь же проверка подключения и расход лимитов.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var accountsBox: LinearLayout
    private lateinit var tokenUrl: Ui.Field
    private lateinit var apiBase: Ui.Field
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = ui.column(padTop = 4)

        root.addView(ui.text(
            "У каждого контракта МТС свой логин и пароль для интеграции. Где взять: личный кабинет МТС Бизнес → " +
                "«API для бизнеса» → у пакета запросов нажмите «Логин и пароль для интеграции». " +
                "Это не логин от личного кабинета.",
            14f, secondary = true,
        ))

        root.addView(ui.section("Доступы к МТС"))
        accountsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(accountsBox)
        root.addView(ui.button("Добавить доступ (логин и пароль)", filled = true) { editAccount(null) }
            .apply { setIconResource(R.drawable.ic_add); (layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(12) })

        root.addView(ui.section("Лимиты тарифа"))
        root.addView(ui.text(
            "Тариф: до 10 вызовов в секунду, 300 в минуту, ~12,5 млн в месяц — у каждого доступа свои. " +
                "Приложение держит запас: 8 в секунду и 280 в минуту, токен хранит час, при ответе 429 ждёт и повторяет. " +
                "Загрузка номеров — 1 вызов на 1000 номеров, данные владельца — 1 вызов на номер, отправка паспорта — 1 вызов.",
            12f, secondary = true,
        ))

        root.addView(ui.section("Дополнительно"))
        root.addView(ui.text("Менять не нужно, если МТС не сообщил новые адреса.", 12f, secondary = true))
        tokenUrl = ui.field("Адрес получения токена", Ui.Kind.URL).also { root.addView(it.layout) }
        apiBase = ui.field("Адрес МТС Бизнес API", Ui.Kind.URL).also { root.addView(it.layout) }
        tokenUrl.value = MtsService.tokenUrl(this)
        apiBase.value = MtsService.apiBase(this)
        root.addView(ui.button("Сохранить адреса") { saveAddresses() })
        root.addView(ui.button("Вернуть адреса по умолчанию") {
            tokenUrl.value = MtsConfig.DEFAULT_TOKEN_URL
            apiBase.value = MtsConfig.DEFAULT_API_BASE
            saveAddresses()
        })

        val toolbar = MaterialToolbar(this).apply {
            title = "Настройки МТС"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
            addView(toolbar)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        renderAccounts()
    }

    override fun finish() {
        super.finish()
        closeScreenAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun renderAccounts() {
        TransitionManager.beginDelayedTransition(accountsBox)
        accountsBox.removeAllViews()
        val accounts = MtsService.accounts(this)
        if (accounts.isEmpty()) {
            accountsBox.addView(ui.text("Доступов пока нет. Добавьте логин и пароль для каждого контракта.", 14f, secondary = true)
                .apply { setPadding(0, ui.px(6), 0, 0) })
            return
        }
        val contracts = SimDb.get(this).contracts().groupBy { it.accountId }
        for ((i, a) in accounts.withIndex()) accountsBox.addView(accountCard(a, i, contracts[a.id].orEmpty()))
    }

    private fun accountCard(a: MtsAccount, index: Int, contracts: List<Contract>) = MaterialCardView(this).apply {
        radius = ui.px(20).toFloat()
        cardElevation = 0f
        strokeWidth = ui.px(1)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(14))
        }
        box.addView(ui.text(a.title.ifBlank { "Доступ ${index + 1}" }, 17f, bold = true))
        box.addView(ui.text("Логин: " + a.login.ifBlank { "не задан" }.let { if (it.length > 18) it.take(8) + "…" + it.takeLast(6) else it }, 13f, secondary = true))
        box.addView(ui.text(
            if (contracts.isEmpty()) "Контракты появятся после загрузки из МТС"
            else "Контракты: " + contracts.joinToString { it.id },
            13f, secondary = true,
        ))
        val l = MtsService.limiter(context, a.id)
        box.addView(ui.text("Вызовов в этом месяце: %,d из %,d".format(l.usedThisMonth(), l.monthlyLimit), 13f, secondary = true))
        val status = TextView(context).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, ui.px(6), 0, 0)
        }
        box.addView(status)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.px(6), 0, 0) }
        fun rowButton(label: String, onClick: () -> Unit) = ui.button(label) { onClick() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.px(6) }
        }
        row.addView(rowButton("Проверить") { check(a, status) })
        row.addView(rowButton("Изменить") { editAccount(a) })
        box.addView(row)
        addView(box)
        setOnClickListener { editAccount(a) }
    }

    private fun editAccount(existing: MtsAccount?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(24), ui.px(4), ui.px(24), 0) }
        val title = ui.field("Название (например, Ростовский)", Ui.Kind.TEXT).also { box.addView(it.layout) }
        val login = ui.field("Логин для интеграции (Consumer Key)").also { box.addView(it.layout) }
        val password = ui.field("Пароль для интеграции (Consumer Secret)", Ui.Kind.PASSWORD).also { box.addView(it.layout) }
        box.addView(ui.text("Хранятся на телефоне в зашифрованном виде и передаются только в МТС.", 12f, secondary = true)
            .apply { setPadding(0, ui.px(6), 0, 0) })
        existing?.let { title.value = it.title; login.value = it.login; password.value = it.password }
        if (existing == null) title.value = "Контракт ${MtsService.accounts(this).size + 1}"

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Новый доступ" else "Доступ к МТС")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
        if (existing != null) builder.setNeutralButton("Удалить") { _, _ -> confirmDelete(existing) }
        val dialog = builder.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            login.error = if (login.value.isBlank()) "Введите логин" else null
            password.error = if (password.value.isBlank()) "Введите пароль" else null
            if (login.error != null || password.error != null) return@setOnClickListener
            val a = MtsAccount(existing?.id ?: MtsService.newAccountId(), title.value.trim(), login.value.trim(), password.value.trim())
            MtsService.saveAccount(this, a)
            dialog.dismiss()
            renderAccounts()
            Toast.makeText(this, "Сохранено. Проверяю подключение…", Toast.LENGTH_SHORT).show()
            accountsBox.post {
                val idx = MtsService.accounts(this).indexOfFirst { it.id == a.id }
                val card = accountsBox.getChildAt(idx) as? MaterialCardView ?: return@post
                val status = ((card.getChildAt(0) as LinearLayout).getChildAt(4)) as TextView
                check(a, status)
            }
        }
    }

    private fun confirmDelete(a: MtsAccount) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить доступ «${a.title}»?")
            .setMessage("Карточки SIM останутся. Чтобы снова работать с номерами этого контракта, доступ нужно будет добавить заново.")
            .setPositiveButton("Удалить") { _, _ -> MtsService.deleteAccount(this, a.id); renderAccounts() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun check(a: MtsAccount, status: TextView) {
        status.visibility = View.VISIBLE
        status.setTextColor(MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant))
        status.text = "Проверяю…"
        executor.execute {
            val (ok, msg) = try {
                MtsService.client(this, a).token(force = true)
                true to "✓ Логин и пароль приняты, подключение работает"
            } catch (e: Exception) {
                false to "✕ " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                TransitionManager.beginDelayedTransition(accountsBox)
                status.text = msg
                status.setTextColor(getColor(if (ok) R.color.ok else R.color.bad))
            }
        }
    }

    private fun saveAddresses() {
        tokenUrl.error = if (tokenUrl.value.startsWith("https://")) null else "Только https://"
        apiBase.error = if (apiBase.value.startsWith("https://")) null else "Только https://"
        if (tokenUrl.error != null || apiBase.error != null) return
        MtsService.saveAddresses(this, tokenUrl.value, apiBase.value.trimEnd('/'))
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
    }
}
