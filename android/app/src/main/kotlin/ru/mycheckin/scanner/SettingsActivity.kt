package ru.mycheckin.scanner

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mycheckin.passport.mts.MtsConfig
import ru.mycheckin.passport.mts.Template
import java.util.concurrent.Executors

/** Подключение к МТС Бизнес API: ключи, адрес метода, шаблон тела запроса, расход лимита. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var key: Ui.Field
    private lateinit var secret: Ui.Field
    private lateinit var tokenUrl: Ui.Field
    private lateinit var submitUrl: Ui.Field
    private lateinit var template: Ui.Field
    private lateinit var methodGroup: MaterialButtonToggleGroup
    private lateinit var usage: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val c = MtsService.load(this)
        val root = ui.column()

        root.addView(ui.text("МТС Бизнес API", 24f, bold = true))
        root.addView(ui.text(
            "Consumer Key и Consumer Secret выдаются в личном кабинете МТС Бизнес, раздел «API для бизнеса». " +
                "Адрес метода и названия полей возьмите из статьи справки «Как внести персональные данные " +
                "пользователя номера с помощью МТС Бизнес API».", 14f, secondary = true,
        ).apply { setPadding(0, ui.px(6), 0, 0) })

        root.addView(ui.section("Доступ"))
        key = ui.field("Consumer Key").also { root.addView(it.layout) }
        secret = ui.field("Consumer Secret", Ui.Kind.PASSWORD).also { root.addView(it.layout) }
        tokenUrl = ui.field("Адрес получения токена", Ui.Kind.URL).also { root.addView(it.layout) }

        root.addView(ui.section("Метод внесения персональных данных"))
        submitUrl = ui.field("Адрес метода (https://api.mts.ru/…)", Ui.Kind.URL, "Можно использовать {{phone}} в адресе").also { root.addView(it.layout) }
        methodGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            for (m in listOf("POST", "PUT", "PATCH")) {
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId(); text = m; tag = m
                })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(8) }
        }
        root.addView(methodGroup)
        template = ui.field("Тело запроса (JSON-шаблон)", Ui.Kind.CODE).also { root.addView(it.layout) }
        root.addView(ui.text("Поля для подстановки: " + Template.KEYS.joinToString(", ") { "{{$it}}" } +
            ". Даты: birth_date / issue_date — ГГГГ-ММ-ДД, *_ru — ДД.ММ.ГГГГ. Телефон — 7XXXXXXXXXX.", 12f, secondary = true))
        root.addView(ui.button("Вернуть шаблон по умолчанию") { template.value = MtsConfig.DEFAULT_TEMPLATE })

        root.addView(ui.section("Лимиты тарифа"))
        usage = ui.text("", 14f)
        root.addView(usage)
        root.addView(ui.text(
            "Тариф: до 10 вызовов в секунду, 300 в минуту, ~12,5 млн в месяц. Приложение ограничивает себя " +
                "8 вызовами в секунду и 280 в минуту, токен кеширует, при ответе 429 ждёт и повторяет. " +
                "Ограничение действует на этом устройстве: если ключ используют несколько устройств, лимит у них общий.",
            12f, secondary = true,
        ))

        root.addView(ui.button("Сохранить", filled = true) { save(); Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show() }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(20) })
        root.addView(ui.button("Проверить подключение (получить токен)") { check() })

        key.value = c.consumerKey
        secret.value = c.consumerSecret
        tokenUrl.value = c.tokenUrl
        submitUrl.value = c.submitUrl
        template.value = c.bodyTemplate
        (0 until methodGroup.childCount).map { methodGroup.getChildAt(it) }
            .firstOrNull { it.tag == c.submitMethod }?.let { methodGroup.check(it.id) }
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

    private fun current(): MtsConfig = MtsConfig(
        consumerKey = key.value,
        consumerSecret = secret.value,
        submitUrl = submitUrl.value,
        submitMethod = findViewById<MaterialButton>(methodGroup.checkedButtonId)?.tag as? String ?: "POST",
        bodyTemplate = template.value.ifBlank { MtsConfig.DEFAULT_TEMPLATE },
        tokenUrl = tokenUrl.value.ifBlank { "https://api.mts.ru/token" },
    )

    private fun save(): Boolean {
        val c = current()
        submitUrl.error = null
        tokenUrl.error = null
        template.error = null
        if (c.submitUrl.isNotEmpty() && !c.submitUrl.startsWith("https://")) submitUrl.error = "Только https://"
        if (!c.tokenUrl.startsWith("https://")) tokenUrl.error = "Только https://"
        val unknown = Template.placeholders(c.bodyTemplate) - Template.KEYS.toSet()
        if (unknown.isNotEmpty()) template.error = "Неизвестные поля: " + unknown.joinToString { "{{$it}}" }
        if (submitUrl.error != null || tokenUrl.error != null || template.error != null) return false
        MtsService.save(this, c)
        return true
    }

    private fun check() {
        if (!save()) return
        executor.execute {
            val msg = try {
                MtsService.client(this).token(force = true)
                "Токен получен, подключение работает."
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
