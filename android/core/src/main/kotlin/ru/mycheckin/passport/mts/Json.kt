package ru.mycheckin.passport.mts

/**
 * Небольшой JSON без внешних зависимостей (ядро — чистый Kotlin/JVM).
 * Запись: Map / List / String / Number / Boolean; ключи со значением null пропускаются.
 * Разбор: объекты → Map, массивы → List, числа → Long или Double.
 */
object Json {

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (x == null) continue
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); writeTo(sb, x) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Тип не поддерживается: ${v.javaClass}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    /** Текст с отступами для журнала; не-JSON возвращается как есть. */
    fun pretty(text: String): String {
        val v = parseOrNull(text.trim()) ?: return text
        if (v !is Map<*, *> && v !is List<*>) return text
        val sb = StringBuilder()
        fun pad(n: Int) { repeat(n) { sb.append("  ") } }
        fun w(x: Any?, d: Int) {
            when (x) {
                is Map<*, *> -> {
                    if (x.isEmpty()) { sb.append("{}"); return }
                    sb.append("{\n")
                    x.entries.forEachIndexed { i, (k, y) ->
                        pad(d + 1); quote(sb, k.toString()); sb.append(": "); w(y, d + 1)
                        if (i < x.size - 1) sb.append(','); sb.append('\n')
                    }
                    pad(d); sb.append('}')
                }
                is List<*> -> {
                    if (x.isEmpty()) { sb.append("[]"); return }
                    sb.append("[\n")
                    x.forEachIndexed { i, y ->
                        pad(d + 1); w(y, d + 1)
                        if (i < x.size - 1) sb.append(','); sb.append('\n')
                    }
                    pad(d); sb.append(']')
                }
                is Double -> sb.append(if (x % 1.0 == 0.0 && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString())
                null -> sb.append("null")
                is String -> quote(sb, x)
                else -> sb.append(x.toString())
            }
        }
        w(v, 0)
        return sb.toString()
    }

    /** Разобрать JSON. Бросает [IllegalArgumentException], если текст не JSON. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.pos == text.length) { "Лишние символы в JSON на позиции ${p.pos}" }
        return v
    }

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg (позиция $pos)")

        fun value(): Any? {
            if (pos >= s.length) fail("Неожиданный конец JSON")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Неожиданный символ '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Ожидалось $word")
            pos += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                ws()
                if (pos >= s.length || s[pos] != '"') fail("Ожидался ключ")
                val k = str()
                ws()
                if (pos >= s.length || s[pos] != ':') fail("Ожидалось ':'")
                pos++
                ws()
                m[k] = value()
                ws()
                if (pos >= s.length) fail("Незакрытый объект")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("Ожидалось ',' или '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                ws()
                l += value()
                ws()
                if (pos >= s.length) fail("Незакрытый массив")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("Ожидалось ',' или ']'")
                }
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                if (pos >= s.length) fail("Незакрытая строка")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Обрыв escape-последовательности")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Обрыв \\u")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("Неверный \\u"))
                                pos += 4
                            }
                            else -> fail("Неизвестная escape-последовательность \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Неверное число '$t'")
        }
    }
}

/**
 * Поле объекта без учёта регистра: в справке МТС одни и те же поля встречаются
 * как `OperationStatus` и `operationStatus`.
 */
fun Any?.field(name: String): Any? {
    val m = this as? Map<*, *> ?: return null
    if (m.containsKey(name)) return m[name]
    return m.entries.firstOrNull { (it.key as? String)?.equals(name, ignoreCase = true) == true }?.value
}

fun Any?.path(vararg names: String): Any? = names.fold(this) { cur, n -> cur.field(n) }

fun Any?.str(): String? = when (this) {
    null -> null
    is String -> this
    is Number, is Boolean -> toString()
    else -> null
}

@Suppress("UNCHECKED_CAST")
fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
