package ru.mycheckin.passport

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Разбор, проверка и исправление MRZ внутреннего паспорта РФ.
 *
 * Строка 1: PNRUS + ФАМИЛИЯ<<ИМЯ<ОТЧЕСТВО (транслитерация МВД), 44 символа.
 * Строка 2: [0:9] 3 первые цифры серии + номер, [9] контр., [10:13] RUS,
 *           [13:19] дата рождения ГГММДД, [19] контр., [20] пол, [21:28] "<<<<<<<",
 *           [28] последняя цифра серии, [29:35] дата выдачи, [35:41] код подразделения,
 *           [41] "<", [42] контр. [28:42], [43] итоговая контр. [0:10]+[13:20]+[21:43].
 */
object Mrz {
    const val LINE_LEN = 44
    private val WEIGHTS = intArrayOf(7, 3, 1)
    private val YYMMDD = DateTimeFormatter.ofPattern("yyMMdd")

    /** Кириллица, которую OCR подставляет вместо похожих латинских букв, и варианты "<". */
    private val HOMOGLYPHS = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
        'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'Х' to 'X', 'У' to 'Y', 'З' to '3', 'Ч' to '4',
        '«' to '<', '‹' to '<', '≪' to '<', '＜' to '<', '(' to '<', '[' to '<', '{' to '<',
    )

    /** Буквы, которые в цифровых полях почти наверняка являются цифрами. */
    private val TO_DIGIT = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0', 'I' to '1', 'L' to '1', 'J' to '1',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6', 'T' to '7', 'A' to '4', '<' to '0',
    )

    /** В поле ФИО цифры 0, 1, 5 невозможны (2-4, 6-9 — это Ё, Ч, Ш, Э, Ю, Я, Ь). */
    private val NAME_FIX = mapOf('0' to 'O', '1' to 'I', '5' to 'S')

    /** Визуально похожие цифры (симметрично) — кандидаты исправления по контрольной сумме. */
    internal val DIGIT_CONFUSIONS: Map<Char, String> = run {
        val base = mapOf(
            '0' to "869", '1' to "74", '2' to "73", '3' to "8592", '4' to "19", '5' to "638",
            '6' to "580", '7' to "12", '8' to "30695", '9' to "8034",
        )
        val sym: Map<Char, MutableSet<Char>> = base.mapValues { (_, v) -> v.toCollection(linkedSetOf()) }
        for ((d, alts) in base) for (a in alts) sym.getValue(a).add(d)
        sym.mapValues { (_, v) -> v.joinToString("") }
    }

    /** Контрольная цифра ICAO 9303: веса 7-3-1, A=10..Z=35, '<'=0. */
    fun checkDigit(data: String): Char {
        var total = 0
        data.forEachIndexed { i, ch ->
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                '<' -> 0
                else -> throw IllegalArgumentException("Недопустимый символ MRZ: '$ch'")
            }
            total += v * WEIGHTS[i % 3]
        }
        return '0' + total % 10
    }

    // ------------------------------------------------------------------
    // Поиск и нормализация строк
    // ------------------------------------------------------------------

    internal fun clean(line: String): String =
        line.uppercase().map { HOMOGLYPHS[it] ?: it }.filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
            .joinToString("")

    internal fun fit(line: String, anchor: String? = null): String {
        var l = line
        if (anchor != null && anchor in l) l = l.substring(l.indexOf(anchor))
        if (l.length > LINE_LEN) l = l.take(LINE_LEN)
        return l.padEnd(LINE_LEN, '<')
    }

    /**
     * Выровнять строку 2 по якорю: "RUS" стоит в позициях 11-13, пол и "<<<<<<<" — с 21-й.
     * Помогает, когда начало строки закрыто пальцем/бликом или к ней прилип мусор.
     */
    internal fun alignLine2(line: String): Pair<String, Int> {
        val i = line.indexOf("RUS")
        val shift = if (i in 0..20) 10 - i else {
            val m = Regex("[MF]<{4,}").find(line)
            if (m != null) 20 - m.range.first else 0
        }
        val l = when {
            shift > 0 -> "<".repeat(shift) + line
            shift < 0 -> line.drop(-shift)
            else -> line
        }
        return fit(l) to shift
    }

    /** Найти две строки MRZ в произвольном тексте OCR (в т.ч. склеенные в одну). */
    fun findLines(text: String): Pair<String, String>? {
        val rows = text.lines().map(::clean).filter { it.length >= 20 }
        val joined = rows.joinToString("")
        val m = Regex("P[NM]R[UV][S5]").find(joined)
        if (m == null) {
            val rows44 = rows.filter { it.length in 40..48 }
            return if (rows44.size >= 2) fit(rows44[rows44.size - 2]) to fit(rows44.last()) else null
        }
        val start = m.range.first
        var pos = 0
        for ((idx, r) in rows.withIndex()) {
            if (start >= pos && start < pos + r.length) {
                val l1 = r.substring(start - pos)
                val rest = rows.drop(idx + 1)
                if (l1.length > LINE_LEN + 10) return fit(l1.take(LINE_LEN)) to fit(l1.drop(LINE_LEN))
                if (rest.isEmpty()) return null
                val l2 = rest.take(2).maxBy { s -> s.count { it in '0'..'9' } }
                return fit(l1) to fit(l2)
            }
            pos += r.length
        }
        return null
    }

    /**
     * Отделить ФИО от заполнителя. Фамилия отделяется "<<", имя от отчества — "<",
     * значит следующее "<<" — начало заполнителя; однобуквенных частей имени не бывает.
     * Возвращает (ФИО, был ли заполнитель). Нет заполнителя = ФИО могло обрезаться.
     */
    internal fun stripFiller(field: String): Pair<String, Boolean> {
        val sep = field.indexOf("<<")
        val end = if (sep < 0) -1 else field.indexOf("<<", sep + 2)
        if (end < 0) {
            val content = field.trimEnd('<')
            return content to (content.length < field.length)
        }
        var content = field.substring(0, end)
        val noise = Regex("[^<]<[^<]$")
        while (noise.containsMatchIn(content) && content.lastIndexOf('<') > sep + 1) content = content.dropLast(2)
        return content to true
    }

    private fun normalizeLine2(l2: String, fixes: MutableList<String>): String {
        val s = l2.toCharArray()
        fun digits(a: Int, b: Int) {
            for (i in a until b) {
                val d = TO_DIGIT[s[i]] ?: continue
                fixes += "строка 2, поз. ${i + 1}: '${s[i]}' -> '$d'"
                s[i] = d
            }
        }
        digits(0, 10)
        if (String(s, 10, 3) != "RUS") {
            fixes += "строка 2: гражданство '${String(s, 10, 3)}' -> 'RUS'"
            "RUS".forEachIndexed { k, c -> s[10 + k] = c }
        }
        digits(13, 20)
        val sex = mapOf('H' to 'M', 'N' to 'M', 'W' to 'M', 'E' to 'F', 'P' to 'F')[s[20]]
        if (sex != null) {
            fixes += "строка 2: пол '${s[20]}' -> '$sex'"
            s[20] = sex
        }
        for (i in 21 until 28) if (s[i] != '<') {
            fixes += "строка 2, поз. ${i + 1}: '${s[i]}' -> '<'"
            s[i] = '<'
        }
        digits(28, 41)
        if (s[41] != '<') {
            fixes += "строка 2, поз. 42: '${s[41]}' -> '<'"
            s[41] = '<'
        }
        digits(42, 44)
        return String(s)
    }

    // ------------------------------------------------------------------
    // Исправление по контрольным цифрам
    // ------------------------------------------------------------------

    internal fun parseYymmdd(s: String): Triple<Int, Int, Int>? {
        if (!Regex("\\d{6}").matches(s)) return null
        val yy = s.substring(0, 2).toInt()
        val mm = s.substring(2, 4).toInt()
        val dd = s.substring(4, 6).toInt()
        return try {
            LocalDate.of(2000, mm, dd)  // 2000 — високосный, 29 февраля допустимо
            Triple(yy, mm, dd)
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun isDigits(v: String) = v.all { it in '0'..'9' }

    private fun optionalValid(v: String) =
        v[0] in '0'..'9' && parseYymmdd(v.substring(1, 7)) != null && isDigits(v.substring(7, 13))

    private fun safeCheck(v: String): Char? = try { checkDigit(v) } catch (e: IllegalArgumentException) { null }

    /**
     * Варианты (значение, контрольная), проходящие проверку. Если исходный корректен — только он.
     * Иначе пробуем заменить одну цифру на визуально похожую (в значении или в контрольной).
     */
    private fun candidates(value: String, check: Char, valid: (String) -> Boolean): List<Pair<String, Char>> {
        if (safeCheck(value) == check && valid(value)) return listOf(value to check)
        val found = mutableListOf<Pair<String, Char>>()
        value.forEachIndexed { i, ch ->
            for (alt in DIGIT_CONFUSIONS[ch].orEmpty()) {
                val v = value.substring(0, i) + alt + value.substring(i + 1)
                if (safeCheck(v) == check && valid(v)) found += v to check
            }
        }
        if (valid(value)) {
            val cd = safeCheck(value)
            if (cd != null && cd in DIGIT_CONFUSIONS[check].orEmpty()) found += value to cd
        }
        return found
    }

    class Repair(val line: String, val checks: Map<String, Boolean>, val fixes: List<String>)

    /** Подобрать исправление строки 2, при котором сходятся контрольные суммы (только однозначное). */
    fun repairLine2(raw: String): Repair {
        val fixes = mutableListOf<String>()
        val l2 = normalizeLine2(raw, fixes)
        val fields = listOf(
            Triple("number", l2.substring(0, 9) to l2[9], ::isDigits),
            Triple("birth_date", l2.substring(13, 19) to l2[19]) { v: String -> parseYymmdd(v) != null },
            Triple("optional", l2.substring(28, 42) to l2[42], ::optionalValid),
        )
        val options = fields.map { (_, vc, valid) -> candidates(vc.first, vc.second, valid).ifEmpty { listOf(vc) } }

        fun assemble(c: List<Pair<String, Char>>): String =
            c[0].first + c[0].second + l2.substring(10, 13) + c[1].first + c[1].second +
                l2.substring(20, 28) + c[2].first + c[2].second + l2[43]

        fun compositeOk(s: String) = safeCheck(s.substring(0, 10) + s.substring(13, 20) + s.substring(21, 43)) == s[43]

        val combos = options[0].flatMap { a -> options[1].flatMap { b -> options[2].map { c -> listOf(a, b, c) } } }
        val good = combos.filter { compositeOk(assemble(it)) }
        val chosen = when {
            good.size == 1 -> good[0]
            combos.size == 1 -> combos[0]
            else -> fields.map { it.second }  // неоднозначно — не угадываем
        }
        var fixed = assemble(chosen)
        val expected = safeCheck(fixed.substring(0, 10) + fixed.substring(13, 20) + fixed.substring(21, 43))
        if (expected != null && !compositeOk(fixed) && chosen.all { safeCheck(it.first) == it.second } &&
            expected in DIGIT_CONFUSIONS[fixed[43]].orEmpty()
        ) {
            fixes += "итоговая контрольная цифра: ${fixed[43]} -> $expected"
            fixed = fixed.substring(0, 43) + expected
        }
        fields.zip(chosen).forEach { (f, c) ->
            if (f.second != c) fixes += "поле ${f.first}: ${f.second.first}${f.second.second} -> ${c.first}${c.second} (по контрольной цифре)"
        }
        val checks = linkedMapOf(
            "number" to (safeCheck(fixed.substring(0, 9)) == fixed[9]),
            "birth_date" to (safeCheck(fixed.substring(13, 19)) == fixed[19]),
            "optional" to (safeCheck(fixed.substring(28, 42)) == fixed[42]),
            "composite" to compositeOk(fixed),
        )
        return Repair(fixed, checks, fixes)
    }

    // ------------------------------------------------------------------
    // Разбор
    // ------------------------------------------------------------------

    private fun resolveDate(yymmdd: String, today: LocalDate, notBefore: LocalDate? = null): LocalDate? {
        val (yy, mm, dd) = parseYymmdd(yymmdd) ?: return null
        for (century in intArrayOf(2000, 1900)) {
            val d = try { LocalDate.of(century + yy, mm, dd) } catch (e: DateTimeException) { continue }
            if (!d.isAfter(today) && (notBefore == null || !d.isBefore(notBefore))) return d
        }
        return null
    }

    private val CHECK_LABELS = mapOf(
        "number" to "серии/номера", "birth_date" to "даты рождения",
        "optional" to "серии/даты выдачи/кода подразделения", "composite" to "итоговая",
    )

    /** Разобрать две строки MRZ. Ошибки OCR исправляются там, где это однозначно. */
    fun parse(line1: String, line2: String, today: LocalDate = LocalDate.now()): PassportData {
        var l1 = fit(clean(line1), "PN")
        val (aligned, shift) = alignLine2(clean(line2))
        val data = PassportData()
        if (shift > 0) {
            data.add(Level.WARNING, "line2_cut", "Начало строки 2 не прочитано ($shift симв.) — серия/номер под вопросом")
        } else if (shift < 0) {
            data.corrections += "строка 2: отброшено ${-shift} лишних символов в начале"
        }

        if (!Regex("P[NM<]R[UV][S5]").matches(l1.take(5)) && l1.substring(2, 5) != "RUS") {
            data.add(Level.ERROR, "mrz_header", "Строка 1 не похожа на паспорт РФ: '${l1.take(5)}'")
        }
        if (l1.take(5) != "PNRUS") {
            data.corrections += "строка 1: заголовок '${l1.take(5)}' -> 'PNRUS'"
            l1 = "PNRUS" + l1.drop(5)
        }
        val namesRaw = l1.drop(5).map { NAME_FIX[it] ?: it }.joinToString("")
        if (namesRaw != l1.drop(5)) {
            data.corrections += "строка 1: ${l1.drop(5).trimEnd('<')} -> ${namesRaw.trimEnd('<')}"
            l1 = l1.take(5) + namesRaw
        }

        val repair = repairLine2(aligned)
        val l2 = repair.line
        data.corrections += repair.fixes
        data.checks.putAll(repair.checks)

        // --- ФИО ---
        val (names, hadFiller) = stripFiller(l1.drop(5))
        val clean1 = ("PNRUS" + names).padEnd(LINE_LEN, '<').take(LINE_LEN)
        if (clean1 != l1) {
            data.corrections += "строка 1: мусор в заполнителе '${l1.drop(5 + names.length)}' удалён"
            l1 = clean1
        }
        data.mrzLines = l1 to l2
        data.nameTruncated = !hadFiller
        if (data.nameTruncated) data.add(Level.WARNING, "name_truncated", "ФИО заполняет строку целиком — возможно, обрезано")
        val surname = names.substringBefore("<<")
        val given = if ("<<" in names) names.substringAfter("<<") else ""
        val parts = given.split('<').filter { it.isNotEmpty() }
        data.surname = Translit.pretty(Translit.decode(surname)).ifEmpty { null }
        data.name = parts.firstOrNull()?.let { Translit.pretty(Translit.decode(it)) }
        data.patronymic = if (parts.size > 1) Translit.pretty(Translit.decode(parts.drop(1).joinToString("<"))) else null
        if ('?' in data.fullName) data.add(Level.WARNING, "name_chars", "В ФИО есть символы вне таблицы транслитерации МВД")

        // --- Документ ---
        data.citizenship = l2.substring(10, 13)
        // Если начало строки 2 не прочитано, серию/номер из неё не берём (там заполнитель).
        if (shift > 0 && !repair.checks.getValue("number")) {
            data.series = null
            data.number = null
        } else {
            data.series = l2.substring(0, 3) + l2[28]
            data.number = l2.substring(3, 9)
        }
        data.sex = l2[20].takeIf { it == 'M' || it == 'F' }
        if (data.sex == null) data.add(Level.ERROR, "sex", "Не распознан пол: '${l2[20]}'")
        data.birthDate = resolveDate(l2.substring(13, 19), today)
        if (data.birthDate == null) data.add(Level.ERROR, "birth_date", "Некорректная дата рождения: ${l2.substring(13, 19)}")
        data.issueDate = resolveDate(l2.substring(29, 35), today, data.birthDate)
        if (data.issueDate == null) data.add(Level.ERROR, "issue_date", "Некорректная дата выдачи: ${l2.substring(29, 35)}")
        val code = l2.substring(35, 41)
        data.divisionCode = if (isDigits(code)) "${code.take(3)}-${code.drop(3)}" else null

        for ((key, ok) in repair.checks) {
            if (ok) continue
            val othersOk = repair.checks.filterKeys { it != "composite" }.values.all { it }
            val level = if (key == "composite" && othersOk) Level.WARNING else Level.ERROR
            data.add(level, "check_$key", "Не сходится контрольная цифра ${CHECK_LABELS[key]}")
        }
        return data
    }

    fun parseText(text: String, today: LocalDate = LocalDate.now()): PassportData? =
        findLines(text)?.let { parse(it.first, it.second, today) }

    // ------------------------------------------------------------------
    // Генерация (для тестов и образцов)
    // ------------------------------------------------------------------

    fun build(
        surname: String, name: String, patronymic: String, series: String, number: String,
        sex: Char, birthDate: LocalDate, issueDate: LocalDate, divisionCode: String,
    ): Pair<String, String> {
        var names = Translit.encode(surname) + "<<" + Translit.encode(name)
        if (patronymic.isNotEmpty()) names += "<" + Translit.encode(patronymic)
        val l1 = ("PNRUS$names").take(LINE_LEN).padEnd(LINE_LEN, '<')
        val doc = series.take(3) + number
        val dob = birthDate.format(YYMMDD)
        val opt = series[3] + issueDate.format(YYMMDD) + divisionCode.replace("-", "") + "<"
        var l2 = doc + checkDigit(doc) + "RUS" + dob + checkDigit(dob) + sex + "<".repeat(7) + opt + checkDigit(opt)
        l2 += checkDigit(l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43))
        return l1 to l2
    }
}

/**
 * Посимвольное голосование по кадрам видеопотока. Каждый кадр может содержать ошибки
 * OCR, но разные кадры редко ошибаются в одной и той же позиции.
 */
class MrzAccumulator(private val window: Int = 10) {
    private val frames = ArrayDeque<Pair<String, String>>()

    val size: Int get() = frames.size

    fun clear() = frames.clear()

    fun add(line1: String, line2: String) {
        frames.addLast(Mrz.fit(Mrz.clean(line1), "PN") to Mrz.alignLine2(Mrz.clean(line2)).first)
        while (frames.size > window) frames.removeFirst()
    }

    fun addText(text: String): Boolean {
        val lines = Mrz.findLines(text) ?: return false
        add(lines.first, lines.second)
        return true
    }

    private fun vote(rows: List<String>): String = (0 until Mrz.LINE_LEN).map { i ->
        rows.map { it[i] }.groupingBy { it }.eachCount().maxBy { it.value }.key
    }.joinToString("")

    fun result(today: LocalDate = LocalDate.now()): PassportData? {
        if (frames.isEmpty()) return null
        return Mrz.parse(vote(frames.map { it.first }), vote(frames.map { it.second }), today)
    }

    /** Сколько кадров по отдельности дают тот же результат, что и голосование. */
    fun agreeing(result: PassportData, today: LocalDate = LocalDate.now()): Int =
        frames.count { Mrz.parse(it.first, it.second, today).mrzLines == result.mrzLines }
}
