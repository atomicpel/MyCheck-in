package ru.mycheckin.scanner

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

object OcrText {
    /**
     * ML Kit может разбить одну строку MRZ на несколько фрагментов. Собираем
     * фрагменты в строки по вертикальной координате и склеиваем слева направо.
     */
    fun rows(text: Text): String {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.centerY() }
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in lines) {
            val box = line.boundingBox!!
            val last = rows.lastOrNull()
            val lastBox = last?.first()?.boundingBox
            if (lastBox != null && abs(lastBox.centerY() - box.centerY()) < box.height() * 0.6f) {
                last.add(line)
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.boundingBox!!.left }.joinToString("") { it.text.replace(" ", "") }
        }
    }
}
