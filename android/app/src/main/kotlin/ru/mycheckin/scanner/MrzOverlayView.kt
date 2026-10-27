package ru.mycheckin.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Затемнение вокруг рамки, в которую нужно поместить строки MRZ. */
class MrzOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
    }
    private val window = RectF()
    private val path = Path()

    /** Рамка зеленеет, когда контрольные суммы начинают сходиться. */
    var progress: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val boxW = w * 0.92f
        val boxH = minOf(boxW * 0.26f, h * 0.3f)
        val cy = h * 0.62f
        window.set((w - boxW) / 2, cy - boxH / 2, (w + boxW) / 2, cy + boxH / 2)
        val r = resources.displayMetrics.density * 14

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRoundRect(window, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)

        frame.color = when {
            progress >= 4 -> Color.rgb(102, 187, 106)
            progress >= 2 -> Color.rgb(255, 202, 40)
            else -> Color.WHITE
        }
        canvas.drawRoundRect(window, r, r, frame)
    }
}
