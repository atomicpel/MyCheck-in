package ru.mycheckin.passport

import java.time.LocalDate

enum class Level { ERROR, WARNING, INFO }

data class Issue(val level: Level, val code: String, val message: String)

/** Результат распознавания паспорта гражданина РФ. */
class PassportData {
    var surname: String? = null
    var name: String? = null
    var patronymic: String? = null
    var sex: Char? = null                  // 'M' / 'F'
    var birthDate: LocalDate? = null
    var series: String? = null             // "4601"
    var number: String? = null             // "123456"
    var issueDate: LocalDate? = null
    var divisionCode: String? = null       // "770-120"
    var citizenship: String? = null        // "RUS"
    var birthPlace: String? = null         // из визуальной зоны
    var issuedBy: String? = null           // из визуальной зоны

    // Производные поля
    var region: String? = null
    var validUntil: LocalDate? = null      // null + status VALID = бессрочно (после 45 лет)
    var replaceDeadline: LocalDate? = null
    var status: Status? = null

    // Диагностика
    var source: String = "mrz"             // mrz / mrz+viz / viz / manual
    var mrzLines: Pair<String, String>? = null
    val checks = linkedMapOf<String, Boolean>()
    val corrections = mutableListOf<String>()
    val issues = mutableListOf<Issue>()
    var nameTruncated = false

    enum class Status { VALID, REPLACEMENT_PERIOD, EXPIRED }

    val seriesNumber: String?
        get() {
            val s = series ?: return null
            val n = number ?: return null
            return if (s.length == 4) "${s.take(2)} ${s.drop(2)} $n" else "$s $n"
        }

    val fullName: String
        get() = listOfNotNull(surname, name, patronymic).joinToString(" ")

    /** Нет ошибок: все контрольные цифры сошлись, документ не просрочен. */
    val isValid: Boolean get() = issues.none { it.level == Level.ERROR }

    val allChecksPassed: Boolean get() = checks.isNotEmpty() && checks.values.all { it }

    /** Грубая оценка уверенности 0..1 по контрольным суммам и исправлениям. */
    val confidence: Double
        get() {
            if (checks.isEmpty()) return 0.0
            var score = checks.values.count { it }.toDouble() / checks.size
            score -= 0.05 * corrections.size
            score -= 0.1 * issues.count { it.level == Level.ERROR }
            return (score.coerceIn(0.0, 1.0) * 1000).toInt() / 1000.0
        }

    fun add(level: Level, code: String, message: String) {
        issues += Issue(level, code, message)
    }

    fun toJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "surname" to surname, "name" to name, "patronymic" to patronymic,
            "sex" to sex?.toString(), "birth_date" to birthDate?.toString(),
            "series" to series, "number" to number, "series_number" to seriesNumber,
            "issue_date" to issueDate?.toString(), "division_code" to divisionCode,
            "citizenship" to citizenship, "birth_place" to birthPlace, "issued_by" to issuedBy,
            "region" to region, "source" to source,
            "valid_until" to validUntil?.toString(), "replace_deadline" to replaceDeadline?.toString(),
            "status" to status?.name?.lowercase(), "is_valid" to isValid, "confidence" to confidence,
            "mrz_lines" to mrzLines?.toList(), "checks" to checks, "corrections" to corrections,
            "issues" to issues.map { mapOf("level" to it.level.name.lowercase(), "code" to it.code, "message" to it.message) },
        )
        return Json.encode(fields)
    }
}

internal object Json {
    fun encode(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${quote(it.key.toString())}: ${encode(it.value)}" }
        is Iterable<*> -> v.joinToString(", ", "[", "]") { encode(it) }
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
