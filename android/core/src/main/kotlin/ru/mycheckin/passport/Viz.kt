package ru.mycheckin.passport

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Поля визуальной зоны (печатный текст на кириллице). */
data class VizFields(
    var surname: String? = null,
    var name: String? = null,
    var patronymic: String? = null,
    var sex: Char? = null,
    var birthDate: LocalDate? = null,
    var birthPlace: String? = null,
    var issueDate: LocalDate? = null,
    var issuedBy: String? = null,
    var divisionCode: String? = null,
    var series: String? = null,
    var number: String? = null,
)

/**
 * Разбор текста визуальной зоны.
 *
 * На фото с камеры мелкие подписи полей («Фамилия», «Место рождения») почти
 * всегда теряются, поэтому поля ищутся не по подписям, а по якорям: даты
 * рождения и выдачи известны из MRZ, «Кем выдан» стоит над датой выдачи,
 * место рождения — между датой рождения и MRZ.
 */
object Viz {
    private val DATE = Regex("(?<!\\d)(\\d{2})\\s?[.,:/]\\s?(\\d{2})\\s?[.,:/]\\s?(\\d{4})(?!\\d)")
    private val DIVISION = Regex("(?<!\\d)(\\d{3})\\s?[-–—]\\s?(\\d{3})(?!\\d)")
    private val SERIES_NUMBER = Regex("(?<!\\d)(\\d{2})\\s?(\\d{2})\\s?(\\d{6})(?!\\d)")
    private val RU = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Частые слова в «Кем выдан» и месте рождения — для исправления ошибок OCR. */
    private val VOCAB = listOf(
        "ОТДЕЛОМ", "ОТДЕЛЕНИЕМ", "ОТДЕЛЕНИЕ", "ОТДЕЛ", "УФМС", "ОУФМС", "РОССИИ", "МВД", "УМВД", "ГУВД", "ОВД",
        "РАЙОНЕ", "РАЙОНА", "РАЙОНОВ", "ОБЛАСТИ", "ОБЛАСТЬ", "КРАЮ", "КРАЯ", "КРАЙ", "РЕСПУБЛИКЕ", "РЕСПУБЛИКИ",
        "РЕСПУБЛИКА", "АВТОНОМНОМУ", "АВТОНОМНОГО", "ОКРУГУ", "ОКРУГА", "ОКРУГЕ", "ГОРОДЕ", "ГОРОДА", "ГОРОДУ",
        "МИГРАЦИИ", "ВОПРОСАМ", "ВОПРОСОВ", "ПОЛИЦИИ", "ГЛАВНЫМ", "УПРАВЛЕНИЕМ", "УПРАВЛЕНИЯ", "ФЕДЕРАЛЬНОЙ",
        "МИГРАЦИОННОЙ", "СЛУЖБЫ", "ТЕРРИТОРИАЛЬНЫМ", "ПУНКТОМ", "МУНИЦИПАЛЬНОМ", "МОСКВЕ", "МОСКВА",
        "МОСКОВСКОЙ", "МОСКОВСКАЯ", "САНКТ-ПЕТЕРБУРГУ", "САНКТ-ПЕТЕРБУРГ", "ЛЕНИНГРАДСКОЙ", "СЕЛО", "ПОСЕЛОК",
        "ДЕРЕВНЯ", "СТАНИЦА", "ХАНТЫ-МАНСИЙСКОМУ", "-ЮГРЕ", "ЮГРЕ", "ЯМАЛО-НЕНЕЦКОМУ", "ГОР.", "ОКР.", "ОБЛ.", "Р-НА", "Р-НЕ", "АВТОНОМ.", "СССР", "РСФСР", "АССР",
    )
    /** Короткие токены, которые законно встречаются в тексте. */
    private val SHORT_OK = setOf("В", "ПО", "Г.", "С.", "П.", "Д.", "Р.", "СТ.", "ПОС.", "ДЕР.", "МО", "ГУ", "ТП", "И", "НА")

    fun formatDate(d: LocalDate): String = d.format(RU)

    fun dates(text: String): List<LocalDate> = DATE.findAll(text).mapNotNull {
        try {
            LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        } catch (e: DateTimeException) {
            null
        }
    }.toList()

    fun divisionCode(text: String): String? = DIVISION.find(text)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

    fun seriesNumber(text: String): Pair<String, String>? =
        SERIES_NUMBER.find(text)?.let { it.groupValues[1] + it.groupValues[2] to it.groupValues[3] }

    fun sex(text: String): Char? {
        val up = text.uppercase()
        return when {
            Regex("МУЖ").containsMatchIn(up) -> 'M'
            Regex("ЖЕН|ЖКН|ЖЁН").containsMatchIn(up) -> 'F'
            else -> null
        }
    }

    /** Строка MRZ или её обломки: символы '<' или длинные латинские/цифровые цепочки. */
    fun isMrzLine(line: String): Boolean =
        line.count { it == '<' || it == '«' } >= 2 || Regex("[A-Z0-9]{12,}").containsMatchIn(line.replace(" ", ""))

    internal fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Исправить слово по словарю, если оно достаточно похоже (ОТДЕПОМ -> ОТДЕЛОМ). */
    internal fun correct(word: String): String {
        if (word in VOCAB || word.length < 4) return word
        val maxDist = if (word.length >= 7) 2 else 1
        val best = VOCAB.minBy { levenshtein(word, it) }
        return if (levenshtein(word, best) <= maxDist) best else word
    }

    /**
     * Очистить строку OCR: оставить токены, похожие на слова печатного текста
     * (заглавная кириллица, сокращения с точкой, дефисы), выкинуть шум фона и печатей.
     */
    fun cleanLine(line: String): String {
        val tokens = line.replace('—', '-').replace('–', '-').split(Regex("\\s+"))
            .map { it.trim(',', ';', ':', '=', '*', '>', '<', '»', '«', '"', '\'', '|', '_', '~', '`', '“', '”', '„', '(', ')') }
            .filter { it.isNotEmpty() }
        val kept = tokens.mapNotNull { raw ->
            val t = raw.uppercase().replace('Ё', 'Е')
            val letters = t.count { it in 'А'..'Я' }
            val bad = t.count { !(it in 'А'..'Я' || it in '0'..'9' || it == '.' || it == '-' || it == ',') }
            val lowerShare = raw.count { it in 'а'..'я' }.toDouble() / maxOf(1, raw.count { it.isLetter() })
            when {
                bad > 0 -> null
                t in SHORT_OK -> t.takeIf { raw.none { c -> c in 'а'..'я' } }  // строчная «в» — шум
                letters >= 3 && lowerShare < 0.5 -> correct(t)
                letters >= 2 && t.endsWith('.') && lowerShare < 0.5 -> correct(t)
                t.length >= 2 && t.all { it in '0'..'9' || it == '-' } && letters == 0 -> null  // номера/коды здесь не нужны
                else -> null
            }
        }
        return kept.joinToString(" ").trim()
    }

    /** Склеить очищенные строки в одно значение поля. */
    fun joinLines(lines: List<String>): String? =
        lines.map(::cleanLine).filter { it.length >= 2 }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            .ifEmpty { null }

    /** Одно слово ФИО из строки (самый длинный кириллический токен). */
    fun nameWord(line: String): String? =
        cleanLine(line).split(" ").filter { w -> w.count { it in 'А'..'Я' } >= 2 && '.' !in w }
            .maxByOrNull { it.length }?.let { Translit.pretty(it) }

    private fun lineHasDate(line: String, date: LocalDate?): Boolean {
        val found = dates(line)
        return if (date != null) date in found || fuzzyDate(line, date) else found.isNotEmpty()
    }

    /** Дата, прочитанная с одной ошибкой (16.01.1995 / 16.01.1996). */
    private fun fuzzyDate(line: String, date: LocalDate): Boolean {
        val target = formatDate(date).replace(".", "")
        return Regex("\\d{2}\\D?\\d{2}\\D?\\d{4}").findAll(line).any {
            val digits = it.value.filter(Char::isDigit)
            digits.length == 8 && levenshtein(digits, target) <= 1
        }
    }

    /**
     * Разбор всего текста страницы по якорям (когда нет координат областей).
     * [mrz] — данные MRZ, если уже распознаны: их даты служат якорями.
     */
    fun parseFullText(text: String, mrz: PassportData? = null): VizFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val f = VizFields()
        val flat = lines.filterNot(::isMrzLine).joinToString(" ")
        f.divisionCode = divisionCode(flat)
        seriesNumber(flat)?.let { (s, n) -> f.series = s; f.number = n }
        f.sex = sex(flat)

        val issueIdx = lines.indexOfFirst { !isMrzLine(it) && (lineHasDate(it, mrz?.issueDate) || DIVISION.containsMatchIn(it)) }
        val birthIdx = lines.indexOfLast { !isMrzLine(it) && lineHasDate(it, mrz?.birthDate) && it != lines.getOrNull(issueIdx) }
        // Первая строка MRZ ниже даты рождения (шум выше по странице не в счёт).
        val mrzIdx = (birthIdx + 1 until lines.size).firstOrNull { isMrzLine(lines[it]) } ?: lines.size

        if (issueIdx >= 0) {
            f.issueDate = dates(lines[issueIdx]).firstOrNull()
            val header = lines.subList(0, issueIdx).indexOfLast { Regex("ФЕД|ЕРАЦ|РАЦИ").containsMatchIn(it.uppercase()) }
            val from = maxOf(header + 1, issueIdx - 4)
            f.issuedBy = joinLines(lines.subList(from, issueIdx))
        }
        if (birthIdx >= 0) {
            f.birthDate = dates(lines[birthIdx]).lastOrNull()
            f.birthPlace = joinLines(lines.subList(birthIdx + 1, maxOf(birthIdx + 1, minOf(mrzIdx, birthIdx + 4))))
            // ФИО — до трёх строк над датой рождения (ниже даты выдачи).
            val names = lines.subList(maxOf(issueIdx + 1, birthIdx - 7), birthIdx).mapNotNull(::nameWord).takeLast(3)
            if (names.size == 3) {
                f.surname = names[0]; f.name = names[1]; f.patronymic = names[2]
            }
        }
        return f
    }

    /**
     * Свести MRZ и визуальную зону. MRZ надёжнее (контрольные цифры), из визуальной
     * зоны берутся «Кем выдан» и «Место рождения», остальное сверяется.
     */
    fun merge(mrz: PassportData?, viz: VizFields, today: LocalDate = LocalDate.now()): PassportData {
        if (mrz == null) {
            return PassportData().apply {
                source = "viz"
                surname = viz.surname; name = viz.name; patronymic = viz.patronymic; sex = viz.sex
                birthDate = viz.birthDate; birthPlace = viz.birthPlace; issueDate = viz.issueDate
                issuedBy = viz.issuedBy; divisionCode = viz.divisionCode; series = viz.series; number = viz.number
                citizenship = "RUS"
                add(Level.WARNING, "no_mrz", "MRZ не найдена — данные только из визуальной зоны, без контрольных сумм")
            }
        }
        val data = if (!mrz.allChecksPassed) resolveWithViz(mrz, viz, today) else mrz
        data.source = "mrz+viz"
        data.birthPlace = viz.birthPlace
        data.issuedBy = viz.issuedBy

        fun compare(title: String, code: String, a: Any?, b: Any?) {
            if (a != null && b != null && a != b) data.add(Level.WARNING, "mismatch_$code", "Расхождение MRZ и визуальной зоны ($title): $a / $b")
        }
        compare("серия", "series", data.series, viz.series)
        compare("номер", "number", data.number, viz.number)
        compare("код подразделения", "division_code", data.divisionCode, viz.divisionCode)
        compare("дата рождения", "birth_date", data.birthDate, viz.birthDate)
        compare("дата выдачи", "issue_date", data.issueDate, viz.issueDate)
        compare("пол", "sex", data.sex, viz.sex)

        // Дефис и необрезанное ФИО берём из визуальной зоны, если оно совпадает с MRZ.
        fun pick(mrzName: String?, vizName: String?): String? {
            if (mrzName == null || vizName == null) return mrzName
            val a = mrzName.uppercase().replace('Ё', 'Е')
            val b = vizName.uppercase().replace('Ё', 'Е')
            return when {
                a.replace(' ', '-') == b -> vizName
                data.nameTruncated && b.startsWith(a) -> vizName
                else -> mrzName
            }
        }
        data.surname = pick(data.surname, viz.surname)
        data.name = pick(data.name, viz.name)
        data.patronymic = pick(data.patronymic, viz.patronymic)
        return data
    }

    /** Неоднозначную ошибку MRZ исправить по визуальной зоне — только если сходится контрольная цифра MRZ. */
    private fun resolveWithViz(mrz: PassportData, viz: VizFields, today: LocalDate): PassportData {
        val (l1, l2) = mrz.mrzLines ?: return mrz
        var fixed = l2
        val used = mutableListOf<String>()
        val yymmdd = DateTimeFormatter.ofPattern("yyMMdd")
        if (mrz.checks["number"] == false && viz.series != null && viz.number != null) {
            val doc = viz.series!!.take(3) + viz.number
            if (Mrz.checkDigit(doc) == l2[9]) { fixed = doc + fixed.drop(9); used += "серия/номер" }
        }
        if (mrz.checks["birth_date"] == false && viz.birthDate != null) {
            val dob = viz.birthDate!!.format(yymmdd)
            if (Mrz.checkDigit(dob) == l2[19]) { fixed = fixed.take(13) + dob + fixed.drop(19); used += "дата рождения" }
        }
        val s = viz.series
        if (mrz.checks["optional"] == false && s != null && s.length == 4 && viz.issueDate != null && viz.divisionCode != null) {
            val opt = s[3] + viz.issueDate!!.format(yymmdd) + viz.divisionCode!!.replace("-", "") + "<"
            if (Mrz.checkDigit(opt) == l2[42]) { fixed = fixed.take(28) + opt + fixed.drop(42); used += "дата выдачи/код подразделения" }
        }
        if (used.isEmpty()) return mrz
        return Mrz.parse(l1, fixed, today).also {
            it.corrections.addAll(0, mrz.corrections)
            it.corrections += "по визуальной зоне исправлено: ${used.joinToString(", ")}"
        }
    }
}
