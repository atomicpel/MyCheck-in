package ru.mycheckin.passport

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VizTest {
    private val today = LocalDate.of(2026, 10, 27)
    private val lines = Mrz.build(
        "Смирнова", "Ольга", "Сергеевна", "4512", "345678", 'F',
        LocalDate.of(1985, 7, 9), LocalDate.of(2012, 8, 14), "770-095",
    )
    private val mrz get() = Mrz.parse(lines.first, lines.second, today)

    // Так выглядит OCR фото с камеры: подписи полей потеряны, вокруг шум.
    private val noisyOcr = """
        Росси йсклЯа ФЕАЕРАЦИЯ
        в пью ОТДЕПОМ УФМС РОССИИ
        ПО ГОР. МОСКВЕ =
        14.08.2012..........., 770-095 у
        ь =
        СМИРНОВА
        =: ОЛЬГА -
        = СЕРГЕЕВНА -
        >. ЖЕН. к, 09.07.1985
        >>. ГОР. ТВЕРЬ ут
        ТВЕРСКАЯ ОБЛАСТЬ в
        РNRUSSMIRNOVA<<OL9GA<SERGEEVNA<<<<<<<<
        4513456781RUS8507099F<<<<<<<2120814770095<4
    """.trimIndent()

    @Test fun cleanLineDropsNoiseAndFixesWords() {
        assertEquals("ОТДЕЛОМ УФМС РОССИИ", Viz.cleanLine("в пью ОТДЕПОМ УФМС РОССИИ"))
        assertEquals("ГОР. ТВЕРЬ", Viz.cleanLine(">>. ГОР. ТВЕРЬ ут"))
        assertEquals("ПО ГОР. МОСКВЕ", Viz.cleanLine("ПО ГОР. МОСКВЕ ="))
    }

    @Test fun parseFullTextUsesMrzDatesAsAnchors() {
        val f = Viz.parseFullText(noisyOcr, mrz)
        assertEquals("ОТДЕЛОМ УФМС РОССИИ ПО ГОР. МОСКВЕ", f.issuedBy)
        assertEquals("ГОР. ТВЕРЬ ТВЕРСКАЯ ОБЛАСТЬ", f.birthPlace)
        assertEquals("770-095", f.divisionCode)
        assertEquals(LocalDate.of(2012, 8, 14), f.issueDate)
        assertEquals(LocalDate.of(1985, 7, 9), f.birthDate)
        assertEquals('F', f.sex)
        assertEquals(listOf("Смирнова", "Ольга", "Сергеевна"), listOf(f.surname, f.name, f.patronymic))
    }

    @Test fun parseFullTextWorksWithoutMrz() {
        val f = Viz.parseFullText(noisyOcr, null)
        assertEquals("ОТДЕЛОМ УФМС РОССИИ ПО ГОР. МОСКВЕ", f.issuedBy)
        assertEquals("ГОР. ТВЕРЬ ТВЕРСКАЯ ОБЛАСТЬ", f.birthPlace)
    }

    @Test fun mergeTakesIssuerAndBirthplaceFromViz() {
        val d = Viz.merge(mrz, Viz.parseFullText(noisyOcr, mrz), today)
        assertEquals("mrz+viz", d.source)
        assertEquals("ОТДЕЛОМ УФМС РОССИИ ПО ГОР. МОСКВЕ", d.issuedBy)
        assertEquals("ГОР. ТВЕРЬ ТВЕРСКАЯ ОБЛАСТЬ", d.birthPlace)
        assertTrue(d.issues.none { it.code.startsWith("mismatch") }, d.issues.toString())
    }

    @Test fun mergeRestoresCoveredNumberFromViz() {
        val covered = Mrz.parse(lines.first, lines.second.drop(5), today)
        val viz = VizFields(series = "4512", number = "345678")
        val d = Viz.merge(covered, viz, today)
        assertEquals(lines.second, d.mrzLines!!.second)
        assertTrue(d.allChecksPassed)
    }

    @Test fun mergeRejectsVizValueThatFailsCheckDigit() {
        val covered = Mrz.parse(lines.first, lines.second.drop(5), today)
        val d = Viz.merge(covered, VizFields(series = "4512", number = "345670"), today)
        assertTrue(d.checks["number"] == false)
    }

    @Test fun hyphenatedSurnameFromViz() {
        val l = Mrz.build("Римский-Корсаков", "Николай", "Андреевич", "4512", "345678", 'M',
            LocalDate.of(1985, 7, 9), LocalDate.of(2012, 8, 14), "770-095")
        val d = Viz.merge(Mrz.parse(l.first, l.second, today), VizFields(surname = "Римский-Корсаков"), today)
        assertEquals("Римский-Корсаков", d.surname)
    }

    @Test fun vizOnlyFallback() {
        val d = Viz.merge(null, Viz.parseFullText(noisyOcr), today)
        assertEquals("viz", d.source)
        assertTrue(d.issues.any { it.code == "no_mrz" })
        assertNull(d.mrzLines)
    }
}
