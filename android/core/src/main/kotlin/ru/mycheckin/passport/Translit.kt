package ru.mycheckin.passport

/**
 * Транслитерация ФИО для MRZ внутреннего паспорта РФ (таблица МВД, не ICAO).
 * Каждой букве соответствует ровно один символ, поэтому запись обратима.
 */
object Translit {
    val CYR_TO_MRZ: Map<Char, Char> = mapOf(
        'А' to 'A', 'Б' to 'B', 'В' to 'V', 'Г' to 'G', 'Д' to 'D', 'Е' to 'E', 'Ё' to '2',
        'Ж' to 'J', 'З' to 'Z', 'И' to 'I', 'Й' to 'Q', 'К' to 'K', 'Л' to 'L', 'М' to 'M',
        'Н' to 'N', 'О' to 'O', 'П' to 'P', 'Р' to 'R', 'С' to 'S', 'Т' to 'T', 'У' to 'U',
        'Ф' to 'F', 'Х' to 'H', 'Ц' to 'C', 'Ч' to '3', 'Ш' to '4', 'Щ' to 'W', 'Ъ' to 'X',
        'Ы' to 'Y', 'Ь' to '9', 'Э' to '6', 'Ю' to '7', 'Я' to '8',
    )
    val MRZ_TO_CYR: Map<Char, Char> = CYR_TO_MRZ.entries.associate { (k, v) -> v to k }

    /** Кириллица -> символы MRZ. Пробел и дефис записываются как '<'. */
    fun encode(text: String): String = buildString {
        for (ch in text.uppercase()) {
            when {
                ch in CYR_TO_MRZ -> append(CYR_TO_MRZ.getValue(ch))
                ch == ' ' || ch == '-' -> append('<')
                else -> throw IllegalArgumentException("Символ '$ch' не может быть записан в MRZ")
            }
        }
    }

    /** Символы MRZ -> кириллица (заглавными). '<' внутри части имени -> пробел. */
    fun decode(mrz: String): String = buildString {
        for (ch in mrz) append(if (ch == '<') ' ' else MRZ_TO_CYR[ch] ?: '?')
    }.trim()

    /** ИВАНОВ-ПЕТРОВ -> Иванов-Петров. */
    fun pretty(name: String): String = name.split("-").joinToString("-") { part ->
        part.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
    }
}
