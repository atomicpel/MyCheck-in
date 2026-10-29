package ru.mycheckin.scanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import ru.mycheckin.passport.mts.BalanceState
import ru.mycheckin.passport.mts.Counter
import ru.mycheckin.passport.mts.LineSnapshot
import ru.mycheckin.passport.mts.Money
import ru.mycheckin.passport.mts.Tri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Подписи для сведений о номерах. */
object LineFormat {
    private val RU = Locale("ru")
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun money(v: Double?): String = v?.let { Money.format(it) } ?: "—"

    fun date(d: LocalDateTime?): String = d?.format(SimFormat.DATE) ?: "бессрочно"
    fun dateTime(d: LocalDateTime?): String = d?.format(DATE_TIME) ?: "—"

    /** «только что», «12 мин назад», «3 ч назад», «вчера», дата. */
    fun ago(t: Long?, now: Long = System.currentTimeMillis()): String {
        if (t == null || t <= 0) return "не загружалось"
        val m = (now - t) / 60_000
        return when {
            m < 1 -> "только что"
            m < 60 -> "$m мин назад"
            m < 24 * 60 -> "${m / 60} ч назад"
            m < 48 * 60 -> "вчера"
            else -> SimFormat.time(t)
        }
    }

    fun number(v: Double): String = if (v == Math.floor(v) && v < 1e9) String.format(RU, "%,.0f", v).replace(' ', ' ')
    else String.format(RU, "%,.1f", v).replace(' ', ' ')

    /** Объём пакета в удобных единицах: МБ → ГБ от 1024. */
    fun amount(v: Double?, kind: Counter.Kind): String {
        if (v == null) return "—"
        return when (kind) {
            Counter.Kind.INTERNET -> if (v >= 1024) number(v / 1024) + " ГБ" else number(v) + " МБ"
            Counter.Kind.MINUTES -> number(v) + " мин"
            Counter.Kind.SMS -> number(v) + " SMS"
            Counter.Kind.MONEY -> Money.format(v)
            Counter.Kind.OTHER -> number(v)
        }
    }

    fun counter(c: Counter): String = when {
        c.left != null && c.total != null -> "${amount(c.left, c.kind)} из ${amount(c.total, c.kind)}"
        c.left != null -> "осталось ${amount(c.left, c.kind)}"
        c.used != null && c.total != null -> "израсходовано ${amount(c.used, c.kind)} из ${amount(c.total, c.kind)}"
        else -> "израсходовано ${amount(c.used, c.kind)}"
    }

    fun shortKind(k: Counter.Kind) = when (k) {
        Counter.Kind.INTERNET -> "Интернет"
        Counter.Kind.MINUTES -> "Минуты"
        Counter.Kind.SMS -> "SMS"
        Counter.Kind.MONEY -> "Деньги"
        Counter.Kind.OTHER -> "Пакет"
    }

    /** Строка пакетов для списка: самый «тонкий» первым. */
    fun packages(s: LineSnapshot): String = s.packages.sortedBy { it.share ?: 1.0 }.take(3).joinToString(" · ") { c ->
        "${shortKind(c.kind)} ${amount(c.left, c.kind)}" + (c.total?.let { " / ${amount(it, c.kind)}" } ?: "")
    }

    fun balanceColor(state: BalanceState): Int = when (state) {
        BalanceState.OK -> R.color.ok
        BalanceState.LOW -> R.color.warn
        BalanceState.NEGATIVE -> R.color.bad
        BalanceState.UNKNOWN -> R.color.neutral
    }

    fun shareColor(share: Double?): Int = when {
        share == null -> R.color.neutral
        share < 0.1 -> R.color.bad
        share < 0.25 -> R.color.warn
        else -> R.color.ok
    }

    fun plural(n: Int, one: String, few: String, many: String) = SimListActivity.plural(n, one, few, many)
}

/** Небольшие элементы экранов «Номера МТС». */
class LineViews(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    fun color(res: Int) = ContextCompat.getColor(context, res)
    fun attr(a: Int) = MaterialColors.getColor(context, a, 0)

    /** Значок-«таблетка»: мягкая подложка цвета [colorRes] и текст того же цвета. */
    fun pill(text: String, colorRes: Int, solid: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(px(10), px(3), px(10), px(3))
        setBackgroundResource(R.drawable.bg_pill)
        val c = color(colorRes)
        backgroundTintList = ColorStateList.valueOf(if (solid) c else ColorUtils.setAlphaComponent(c, 40))
        setTextColor(if (solid) android.graphics.Color.WHITE else c)
        maxLines = 1
    }

    fun chip(text: String, checkable: Boolean = true, checked: Boolean = false): Chip = Chip(context).apply {
        this.text = text
        isCheckable = checkable
        isChecked = checked
        isCheckedIconVisible = checkable
        setEnsureMinTouchTargetSize(false)
    }

    fun chipGroup(single: Boolean, required: Boolean = false): ChipGroup = ChipGroup(context).apply {
        isSingleSelection = single
        isSelectionRequired = required
        chipSpacingHorizontal = px(6)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) }
    }

    fun label(text: String, top: Int = 16): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, px(top), 0, px(2))
    }

    fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, px(2), 0, 0)
    }

    /** «Все / Да / Нет» для условия-переключателя. */
    fun tri(group: ChipGroup, value: Tri, yes: String = "Да", no: String = "Нет", onChange: (Tri) -> Unit) {
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val items = listOf(Tri.ANY to "Все", Tri.YES to yes, Tri.NO to no)
        val ids = items.map { (t, title) -> chip(title, checked = t == value).also { it.id = View.generateViewId(); group.addView(it) }.id }
        group.setOnCheckedStateChangeListener { _, checked ->
            val i = ids.indexOf(checked.firstOrNull())
            if (i >= 0) onChange(items[i].first)
        }
    }

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0) = ViewGroup.MarginLayoutParams(w, h).apply { topMargin = px(top) }
}
