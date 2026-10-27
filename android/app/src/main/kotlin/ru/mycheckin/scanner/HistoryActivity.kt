package ru.mycheckin.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mycheckin.passport.mts.ConfirmationStatus
import ru.mycheckin.passport.mts.OperationResult
import ru.mycheckin.passport.mts.RateLimiter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Проверка статуса заявки: сначала сама заявка, после выполнения — подтверждение через Госуслуги. */
object SubmissionChecker {
    data class Outcome(val submission: Submission, val title: String, val text: String)

    fun check(context: Context, s: Submission): Outcome {
        val client = MtsService.client(context)
        return try {
            val op = client.operationResult(s.phone, s.messageId)
            val lines = mutableListOf("Заявка: ${op.title}")
            lines += op.errors.map { "• $it" }
            var confirmation = s.confirmation
            if (op.status == "Completed") {
                val c = client.confirmationStatus(s.phone)
                confirmation = c.code.orEmpty()
                lines += "Подтверждение: ${c.description}"
            } else if (op.status == "InProgress") {
                lines += "МТС ещё обрабатывает заявку. Проверьте через несколько минут."
            }
            val updated = s.copy(status = op.status.orEmpty(), confirmation = confirmation, checkedAt = System.currentTimeMillis())
            MtsService.updateSubmission(context, updated)
            Outcome(updated, op.title, lines.joinToString("\n"))
        } catch (e: RateLimiter.MonthlyLimitExceeded) {
            Outcome(s, "Лимит тарифа", e.message.orEmpty())
        } catch (e: Exception) {
            Outcome(s, "Не удалось проверить", e.message ?: e.javaClass.simpleName)
        }
    }

    fun statusLine(s: Submission): String = buildString {
        append(if (s.status.isEmpty()) "Статус ещё не проверялся" else "Заявка: " + (OperationResult.OPERATION_TITLES[s.status] ?: s.status))
        if (s.confirmation.isNotEmpty()) {
            append("\nГосуслуги: ")
            append(ConfirmationStatus(s.confirmation).description)
        }
    }
}

/** Отправленные заявки: номер, владелец, статус. Нажмите на заявку, чтобы проверить статус. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var list: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = ui.column()
        root.addView(ui.text("Отправленные заявки", 24f, bold = true))
        root.addView(ui.text(
            "После отправки МТС присылает пользователю SMS: данные нужно подтвердить на Госуслугах. " +
                "Нажмите на заявку, чтобы проверить её статус и подтверждение.",
            14f, secondary = true,
        ).apply { setPadding(0, ui.px(6), 0, 0) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private fun render() {
        list.removeAllViews()
        val items = MtsService.submissions(this)
        if (items.isEmpty()) {
            list.addView(ui.text("Заявок пока нет.", 15f, secondary = true).apply { setPadding(0, ui.px(20), 0, 0) })
            return
        }
        for (s in items) list.addView(card(s))
    }

    private fun card(s: Submission) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
        background = GradientDrawable().apply {
            cornerRadius = ui.px(14).toFloat()
            setColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.px(10) }
        addView(TextView(context).apply {
            text = formatPhone(s.phone) + "  ·  " + s.holder
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        })
        addView(ui.text("Отправлено ${fmt.format(Date(s.sentAt))} · заявка ${s.messageId}", 12f, secondary = true))
        addView(ui.text(SubmissionChecker.statusLine(s), 14f).apply { setPadding(0, ui.px(4), 0, 0) })
        if (s.checkedAt > 0) addView(ui.text("Проверено ${fmt.format(Date(s.checkedAt))}", 12f, secondary = true))
        setOnClickListener { actions(s) }
    }

    private fun actions(s: Submission) {
        MaterialAlertDialogBuilder(this)
            .setTitle(formatPhone(s.phone))
            .setItems(arrayOf("Проверить статус", "Копировать номер заявки", "Удалить из списка")) { _, which ->
                when (which) {
                    0 -> check(s)
                    1 -> {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("messageId", s.messageId))
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    2 -> { MtsService.removeSubmission(this, s.messageId); render() }
                }
            }
            .show()
    }

    private fun check(s: Submission) {
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val o = SubmissionChecker.check(this, s)
            runOnUiThread {
                render()
                MaterialAlertDialogBuilder(this).setTitle(o.title).setMessage(o.text).setPositiveButton("OK", null).show()
            }
        }
    }

    companion object {
        fun formatPhone(msisdn: String): String =
            if (msisdn.length == 11) "+7 ${msisdn.substring(1, 4)} ${msisdn.substring(4, 7)}-${msisdn.substring(7, 9)}-${msisdn.substring(9)}"
            else msisdn
    }
}
