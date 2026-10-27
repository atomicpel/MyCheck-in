package ru.mycheckin.scanner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Построение экранов кодом: поля ввода, заголовки, кнопки, плашки статуса. */
class Ui(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun px(v: Int) = (v * density).toInt()

    fun column(padH: Int = 20, padTop: Int = 36) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(padH), px(padTop), px(padH), px(28))
    }

    fun text(value: String, size: Float, secondary: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        val attr = if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant
        else com.google.android.material.R.attr.colorOnSurface
        setTextColor(MaterialColors.getColor(context, attr, Color.GRAY))
    }

    fun section(title: String) = text(title, 17f, bold = true).apply { setPadding(0, px(22), 0, px(4)) }

    enum class Kind { TEXT, NAME, MULTILINE, DIGITS, DATE, PHONE, PASSWORD, URL, CODE }

    class Field(val layout: TextInputLayout, val edit: TextInputEditText) {
        var value: String
            get() = edit.text?.toString()?.trim().orEmpty()
            set(v) = edit.setText(v)
        var error: String?
            get() = layout.error?.toString()
            set(v) { layout.error = v }
    }

    fun field(hint: String, kind: Kind = Kind.TEXT, helper: String? = null): Field {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            helper?.let { helperText = it }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) }
            if (kind == Kind.PASSWORD) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = when (kind) {
                Kind.NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                Kind.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                Kind.DIGITS -> InputType.TYPE_CLASS_NUMBER
                Kind.DATE -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                Kind.PHONE -> InputType.TYPE_CLASS_PHONE
                Kind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                Kind.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                Kind.CODE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                Kind.TEXT -> InputType.TYPE_CLASS_TEXT
            }
            if (kind == Kind.CODE) {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                minLines = 6
            }
            if (kind == Kind.MULTILINE) minLines = 2
        }
        layout.addView(edit)
        return Field(layout, edit)
    }

    fun button(label: String, filled: Boolean = false, onClick: () -> Unit) =
        MaterialButton(context, null, if (filled) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) }
        }

    /** Цветная плашка: заголовок + подробности. */
    fun banner(colorRes: Int, title: String, details: List<String>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(ContextCompat.getColor(context, colorRes))
        }
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        details.forEach { line ->
            addView(TextView(context).apply {
                text = line
                textSize = 14f
                setTextColor(Color.argb(235, 255, 255, 255))
                setPadding(0, px(3), 0, 0)
            })
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10); bottomMargin = px(4) }
    }
}
