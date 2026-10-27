package ru.mycheckin.passport

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrzTest {
    private val today = LocalDate.of(2026, 10, 27)

    private fun sample(
        surname: String = "Иванов", name: String = "Иван", patronymic: String = "Иванович",
        series: String = "4601", sex: Char = 'M',
        born: LocalDate = LocalDate.of(1990, 3, 15), issued: LocalDate = LocalDate.of(2010, 6, 20),
    ) = Mrz.build(surname, name, patronymic, series, "123456", sex, born, issued, "770-120")

    private fun parse(l1: String, l2: String) = Validation.validate(Mrz.parse(l1, l2, today), today)

    private fun parse(lines: Pair<String, String>) = parse(lines.first, lines.second)

    private fun replace(s: String, pos: Int, c: Char) = s.substring(0, pos) + c + s.substring(pos + 1)

    @Test fun checkDigitIcaoExamples() {
        assertEquals('6', Mrz.checkDigit("L898902C3"))
        assertEquals('2', Mrz.checkDigit("740812"))
        assertEquals('9', Mrz.checkDigit("120415"))
    }

    @Test fun consultantExample() {
        // Серия 4601, выдан 20.06.2010, код 770-120 -> "1100620770120"
        assertEquals("1100620770120", sample().second.substring(28, 41))
    }

    @Test fun roundTrip() {
        val (l1, l2) = sample()
        assertEquals("PNRUSIVANOV<<IVAN<IVANOVI3<<<<<<<<<<<<<<<<<<", l1)
        val d = parse(l1, l2)
        assertEquals(listOf("Иванов", "Иван", "Иванович"), listOf(d.surname, d.name, d.patronymic))
        assertEquals("46 01 123456", d.seriesNumber)
        assertEquals(LocalDate.of(1990, 3, 15), d.birthDate)
        assertEquals(LocalDate.of(2010, 6, 20), d.issueDate)
        assertEquals("770-120", d.divisionCode)
        assertEquals('M', d.sex)
        assertTrue(d.allChecksPassed && d.corrections.isEmpty() && d.isValid)
        assertEquals(1.0, d.confidence)
        assertEquals("Московская область", d.region)
        assertEquals(PassportData.Status.VALID, d.status)
    }

    @Test fun translitIsReversible() {
        val alphabet = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
        val enc = Translit.encode(alphabet)
        assertEquals(alphabet.length, enc.toSet().size)
        assertEquals(alphabet, Translit.decode(enc))
    }

    @Test fun specialLetters() {
        val (l1, l2) = sample("Щёголева", "Юлия", "Эдуардовна", sex = 'F')
        assertTrue(l1.startsWith("PNRUSW2GOLEVA<<7LI8<6DUARDOVNA<"))
        val d = parse(l1, l2)
        assertEquals(listOf("Щёголева", "Юлия", "Эдуардовна"), listOf(d.surname, d.name, d.patronymic))
    }

    @Test fun realWorldTransliterationShape() {
        // Й -> Q, Ч -> 3: КОРНЕЙЧУК / ДМИТРИЕВИЧ
        assertEquals("KORNEQ3UK", Translit.encode("Корнейчук"))
        assertEquals("DMITRIEVI3", Translit.encode("Дмитриевич"))
    }

    @Test fun ocrNoiseNormalized() {
        val (l1, l2) = sample()
        val noisy1 = "РNRUS" + l1.drop(5).replaceFirst("<<<<", "<K<<").replaceFirst("<", "«")
        val noisy2 = l2.replaceFirst('0', 'O')
        val d = parse(noisy1, noisy2)
        assertEquals("Иванович", d.patronymic)
        assertEquals("123456", d.number)
        assertTrue(d.allChecksPassed)
        assertEquals(l1 to l2, d.mrzLines)
    }

    @Test fun zeroInNameBecomesO() {
        val (l1, l2) = sample("Ольга", "Ольга", "")
        val d = parse(l1.replace("OL9GA", "0L9GA"), l2)
        assertEquals("Ольга", d.surname)
    }

    @Test fun singleMisreadRepaired() {
        val (l1, l2) = sample()
        for ((pos, wrong) in listOf(15 to '6', 31 to '8', 9 to '7')) {
            val d = parse(l1, replace(l2, pos, wrong))
            assertEquals(l2, d.mrzLines!!.second, "pos $pos")
            assertTrue(d.allChecksPassed)
        }
    }

    @Test fun ambiguousMisreadNotGuessed() {
        val (l1, l2) = sample()
        for ((pos, wrong) in listOf(4 to '7', 37 to '8')) {
            val bad = replace(l2, pos, wrong)
            val d = parse(l1, bad)
            assertEquals(bad, d.mrzLines!!.second)
            assertFalse(d.isValid)
        }
    }

    @Test fun compositeDigitFixed() {
        val (l1, l2) = sample()
        val wrong = ('0'..'9').first { it != l2[43] && l2[43] in Mrz.DIGIT_CONFUSIONS.getValue(it) }
        val d = parse(l1, l2.dropLast(1) + wrong)
        assertEquals(l2, d.mrzLines!!.second)
    }

    @Test fun findLinesInGarbage() {
        val (l1, l2) = sample()
        assertEquals(l1 to l2, Mrz.findLines("РОССИЙСКАЯ ФЕДЕРАЦИЯ\nмусор 123\n ${l1.take(20)} ${l1.drop(20)} \n$l2\n"))
        assertEquals(l1 to l2, Mrz.findLines("xx$l1${l2}yy"))
        assertNull(Mrz.findLines("ничего похожего"))
    }

    @Test fun coveredStartOfLine2IsAligned() {
        val (l1, l2) = sample()
        val d = parse(l1, l2.drop(5))
        assertEquals(LocalDate.of(1990, 3, 15), d.birthDate)
        assertEquals("770-120", d.divisionCode)
        assertFalse(d.checks.getValue("number"))
        assertTrue(d.issues.any { it.code == "line2_cut" })
        assertNull(d.seriesNumber)  // не выдаём заполнитель за номер
    }

    @Test fun truncatedNameFlagged() {
        val d = parse(sample("Константинопольский", "Александр", "Константинович"))
        assertTrue(d.nameTruncated)
    }

    @Test fun accumulatorVotes() {
        val (l1, l2) = sample()
        val acc = MrzAccumulator()
        for (pos in listOf(2, 15, 33)) acc.add(l1, replace(l2, pos, 'X'))
        acc.add(l1, l2)
        val d = acc.result(today)!!
        assertEquals(l1 to l2, d.mrzLines)
        assertTrue(d.corrections.isEmpty())
    }

    @Test fun validityRules() {
        assertEquals(LocalDate.of(2020, 1, 10), Validation.validity(LocalDate.of(2000, 1, 10), LocalDate.of(2014, 2, 1)))
        assertEquals(LocalDate.of(2045, 1, 10), Validation.validity(LocalDate.of(2000, 1, 10), LocalDate.of(2020, 2, 1)))
        assertNull(Validation.validity(LocalDate.of(1970, 1, 10), LocalDate.of(2015, 2, 1)))
        assertEquals(LocalDate.of(2024, 2, 29), Validation.validity(LocalDate.of(2004, 2, 29), LocalDate.of(2018, 3, 1)))

        val expired = parse(sample(series = "4519", born = LocalDate.of(2005, 1, 1), issued = LocalDate.of(2019, 2, 1)))
        assertEquals(PassportData.Status.EXPIRED, expired.status)
        assertFalse(expired.isValid)
        val repl = parse(sample(series = "4520", born = LocalDate.of(2006, 9, 1), issued = LocalDate.of(2020, 9, 15)))
        assertEquals(PassportData.Status.REPLACEMENT_PERIOD, repl.status)
        assertTrue(repl.isValid)
    }

    @Test fun jsonIsWellFormed() {
        val d = parse(sample().first, sample().second)
        val json = d.toJson()
        assertTrue(json.startsWith("{\"surname\": \"Иванов\""))
        assertTrue("\"series_number\": \"46 01 123456\"" in json)
    }
}
