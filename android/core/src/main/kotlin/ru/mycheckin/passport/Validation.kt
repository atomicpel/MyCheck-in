package ru.mycheckin.passport

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Логические проверки данных паспорта поверх контрольных цифр MRZ. */
object Validation {
    /** Паспорт нового образца выдаётся с 1 октября 1997 г. */
    val FIRST_ISSUE: LocalDate = LocalDate.of(1997, 10, 1)
    const val MIN_AGE = 14
    val REPLACE_AGES = intArrayOf(20, 45)
    /** Срок подачи документов на замену после 20/45 лет. */
    const val REPLACE_GRACE_DAYS = 90L
    private val RU = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** LocalDate.plusYears для 29 февраля даёт 28 февраля; по закону — 1 марта. */
    private fun birthday(born: LocalDate, age: Int): LocalDate =
        if (born.monthValue == 2 && born.dayOfMonth == 29 && !born.plusYears(age.toLong()).isLeapYear)
            LocalDate.of(born.year + age, 3, 1)
        else born.plusYears(age.toLong())

    fun ageOn(born: LocalDate, day: LocalDate): Int = ChronoUnit.YEARS.between(born, day).toInt()

    /** Дата, до которой действует паспорт (день 20/45-летия), или null — бессрочно. */
    fun validity(born: LocalDate, issued: LocalDate): LocalDate? =
        REPLACE_AGES.map { birthday(born, it) }.firstOrNull { issued.isBefore(it) }

    fun validate(data: PassportData, today: LocalDate = LocalDate.now()): PassportData {
        data.citizenship?.let { if (it != "RUS") data.add(Level.ERROR, "citizenship", "Гражданство $it, ожидалось RUS") }

        data.series?.let { s ->
            if (!Regex("\\d{4}").matches(s)) {
                data.add(Level.ERROR, "series_format", "Серия должна состоять из 4 цифр: $s")
            } else {
                data.region = Regions.SERIES[s.take(2)]
                if (data.region == null) data.add(Level.WARNING, "series_region", "Неизвестный код региона в серии: ${s.take(2)}")
            }
        }
        data.number?.let { if (!Regex("\\d{6}").matches(it)) data.add(Level.ERROR, "number_format", "Номер должен состоять из 6 цифр: $it") }
        data.divisionCode?.let {
            if (!Regex("\\d{3}-\\d{3}").matches(it)) data.add(Level.ERROR, "division_format", "Код подразделения должен быть вида 000-000: $it")
        }

        val born = data.birthDate
        val issued = data.issueDate
        if (born != null && born.isAfter(today)) data.add(Level.ERROR, "birth_future", "Дата рождения в будущем")
        if (issued != null) {
            if (issued.isAfter(today)) data.add(Level.ERROR, "issue_future", "Дата выдачи в будущем")
            if (issued.isBefore(FIRST_ISSUE)) data.add(Level.ERROR, "issue_too_early", "Паспорта нового образца выдаются с 01.10.1997")
            val s = data.series
            if (s != null && s.length == 4 && s.drop(2).all { it in '0'..'9' }) {
                val yy = s.drop(2).toInt()
                val blankYear = if (yy >= 97) 1900 + yy else 2000 + yy
                if (issued.year < blankYear - 1) {
                    data.add(Level.WARNING, "series_year", "Бланк $blankYear г. выдан раньше (${issued.year} г.) — проверьте серию")
                }
            }
        }

        if (born != null && issued != null) {
            val age = ageOn(born, issued)
            if (age < MIN_AGE) data.add(Level.ERROR, "issue_age", "Паспорт выдан в $age лет (с $MIN_AGE лет)")
            val until = validity(born, issued)
            data.validUntil = until
            if (until == null) {
                data.status = PassportData.Status.VALID
            } else {
                val deadline = until.plusDays(REPLACE_GRACE_DAYS)
                data.replaceDeadline = deadline
                when {
                    today.isBefore(until) -> data.status = PassportData.Status.VALID
                    !today.isAfter(deadline) -> {
                        data.status = PassportData.Status.REPLACEMENT_PERIOD
                        data.add(Level.WARNING, "replacement", "Паспорт подлежит замене (до ${deadline.format(RU)})")
                    }
                    else -> {
                        data.status = PassportData.Status.EXPIRED
                        data.add(Level.ERROR, "expired", "Паспорт недействителен с ${until.format(RU)}")
                    }
                }
            }
        }
        return data
    }
}
