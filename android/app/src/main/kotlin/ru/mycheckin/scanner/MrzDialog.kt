package ru.mycheckin.scanner

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Ручной ввод или исправление двух строк MRZ. */
object MrzDialog {
    fun show(
        context: Context, line1: String, line2: String,
        onDismiss: () -> Unit = {}, onDone: (String, String) -> Unit,
    ) {
        val pad = (context.resources.displayMetrics.density * 20).toInt()
        fun field(value: String) = EditText(context).apply {
            setText(value)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "PNRUS…"
        }
        val f1 = field(line1)
        val f2 = field(line2)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(f1)
            addView(f2)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.manual_title)
            .setMessage(R.string.manual_hint)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onDone(f1.text.toString(), f2.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }
}
