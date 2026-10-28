package ru.mycheckin.passport.sim

/** Что удалось прочитать с SIM-карты или её пластика. */
data class SimCode(val kind: Kind, val value: String) {
    enum class Kind(val label: String) { PHONE("Номер телефона"), ICCID("Номер SIM (ICCID)") }
}

/**
 * Поиск номера телефона и ICCID в штрихкоде или в распознанном тексте.
 *
 * ICCID — 18–20 цифр, начинается с 89 (89 — телеком, 7 — Россия); на пластике печатается
 * группами через пробел, в штрихкоде иногда с буквой F в конце. Номер телефона — 11 цифр,
 * начинается с 7 или 8 (+7 XXX XXX-XX-XX), или 10 цифр, начинается с 9.
 */
object SimCodes {
    private val PHONE = Regex("(?<![\\d])(?:\\+\\s?7|8|7)[\\s(.-]*(\\d{3})[\\s).-]*(\\d{3})[\\s.-]*(\\d{2})[\\s.-]*(\\d{2})(?![\\d])")
    private val PHONE10 = Regex("(?<![\\d+])(9\\d{2})[\\s).-]*(\\d{3})[\\s.-]*(\\d{2})[\\s.-]*(\\d{2})(?![\\d])")

    /** Строка цифр — ICCID? */
    fun isIccid(digits: String): Boolean = digits.length in 18..20 && digits.startsWith("89")

    /** Значение штрихкода: ICCID (с отброшенной F в конце) или номер телефона. */
    fun fromBarcode(raw: String): SimCode? {
        val compact = raw.trim().trimEnd('F', 'f').filter { it.isLetterOrDigit() }
        if (compact.all { it.isDigit() } && isIccid(compact)) return SimCode(SimCode.Kind.ICCID, compact)
        return fromText(raw).firstOrNull()
    }

    /** Все номера и ICCID в тексте (по строкам; ICCID — с пробелами между группами). */
    fun fromText(text: String): List<SimCode> {
        val out = LinkedHashSet<SimCode>()
        for (line in text.lines()) {
            // ICCID: цифры строки подряд (группы «8970 1015 3885 …»), в т.ч. после подписи «ICCID».
            val digitRuns = Regex("[\\d\\s]{18,}").findAll(line).map { it.value.filter(Char::isDigit) }
            for (d in digitRuns) {
                val start = d.indexOf("89")
                if (start >= 0) {
                    val tail = d.substring(start)
                    val cut = when {
                        isIccid(tail) -> tail
                        tail.length > 20 && isIccid(tail.take(20)) -> tail.take(20)
                        else -> null
                    }
                    cut?.let { out += SimCode(SimCode.Kind.ICCID, it) }
                }
            }
            if (out.any { it.kind == SimCode.Kind.ICCID && line.filter(Char::isDigit).contains(it.value) }) continue
            PHONE.findAll(line).forEach { m ->
                out += SimCode(SimCode.Kind.PHONE, "7" + m.groupValues.drop(1).joinToString(""))
            }
            if (out.none { it.kind == SimCode.Kind.PHONE }) PHONE10.findAll(line).forEach { m ->
                out += SimCode(SimCode.Kind.PHONE, "7" + m.groupValues.drop(1).joinToString(""))
            }
        }
        return out.toList()
    }

    /** Совпадает ли прочитанный ICCID с сохранённым (с контрольной цифрой Луна или без неё). */
    fun sameIccid(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return long.length - short.length == 1 && long.startsWith(short)
    }
}
