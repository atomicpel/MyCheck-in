package ru.mycheckin.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Краткий статус SIM для карточки: текст и цвет. */
enum class SimStatus(val label: String, val color: Int) {
    CONFIRMED("Подтверждено", R.color.ok),
    DONE("Внесено", R.color.ok),
    WAITING("Ждёт Госуслуг", R.color.waiting),
    SENT("Отправлено", R.color.sent),
    PROBLEM("Есть проблема", R.color.bad),
    IN_MTS("Есть в МТС", R.color.sent),
    NEEDED("Нужно внести", R.color.warn),
    READY("Не отправлено", R.color.neutral),
    NO_PASSPORT("Нет паспорта", R.color.neutral);

    companion object {
        private val PROBLEMS = setOf("MismatchOfData", "NotFoundInEsia", "Refusal", "RequestNotFoundInEsia")
        /** В МТС нет данных пользователя — их нужно внести. */
        private val NEED_DATA = setOf("Anonymous", "Migration", "ActivatedPortIn")

        fun of(s: SimRecord): SimStatus = when {
            s.confirmation == "Activated" || s.confirmation == "NotRequired" -> CONFIRMED
            s.opStatus == "Faulted" || s.confirmation in PROBLEMS -> PROBLEM
            s.confirmation == "WaitingForAcceptance" || s.confirmation == "WaitingForCheck" -> WAITING
            s.messageId.isNotEmpty() && s.opStatus == "Completed" -> DONE
            s.messageId.isNotEmpty() -> SENT
            // Depersonalized (44-ФЗ / 223-ФЗ): если МТС прислал паспорт — данные в МТС уже есть.
            s.confirmation == "Depersonalized" && s.hasPassport && s.fullName.isNotEmpty() -> IN_MTS
            s.confirmation in NEED_DATA || s.confirmation == "Depersonalized" -> NEEDED
            s.hasPassport -> READY
            else -> NO_PASSPORT
        }
    }
}

object SimFormat {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    fun phone(msisdn: String): String =
        if (msisdn.length == 11) "+7 ${msisdn.substring(1, 4)} ${msisdn.substring(4, 7)}-${msisdn.substring(7, 9)}-${msisdn.substring(9)}"
        else msisdn

    fun passport(s: SimRecord): String =
        if (s.hasPassport) "${s.series.take(2)} ${s.series.drop(2)} ${s.number}" else "Паспорт не указан"

    fun time(millis: Long): String = TIME.format(Date(millis))
}

/**
 * Поиск по списку: ФИО, номер телефона (в любом виде: 8…, +7…, без кода), ICCID, серия и номер паспорта,
 * лицевой счёт, примечание. Несколько слов — должны найтись все.
 */
object SimSearch {
    fun matches(s: SimRecord, query: String): Boolean {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = listOf(s.surname, s.name, s.patronymic, s.notes, s.region, s.issuedBy).joinToString(" ").lowercase()
        val digits = listOf(s.msisdn, s.iccid, s.series + s.number, s.accountNo, s.imsi)
        return words.all { w ->
            val d = w.filter { it.isDigit() }
            when {
                d.length == w.count { it.isLetterOrDigit() } && d.isNotEmpty() -> {
                    val alt = if (d.length == 11 && d[0] == '8') "7" + d.drop(1) else d
                    // ICCID со сканера может быть с контрольной цифрой или без неё.
                    digits.any { it.contains(d) || it.contains(alt) } ||
                        (d.length >= 19 && ru.mycheckin.passport.sim.SimCodes.sameIccid(s.iccid, d))
                }
                else -> text.contains(w)
            }
        }
    }
}

/** Фото паспорта и владельца — в закрытой папке приложения. */
object SimPhotos {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun save(context: Context, bitmap: Bitmap, kind: String): String {
        val f = File(dir(context), "${kind}_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f.absolutePath
    }

    fun load(path: String, maxSide: Int = 1600): Bitmap? {
        if (path.isBlank() || !File(path).exists()) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(path: String) {
        if (path.isNotBlank()) File(path).delete()
    }
}

/** Выгрузка списка в CSV для Excel (UTF-8 с BOM, разделитель «;»). */
object SimExport {
    fun csv(context: Context, sims: List<SimRecord>, contracts: Map<String, Contract>): Intent {
        val header = listOf(
            "Контракт", "Номер контракта", "Лицевой счёт", "Телефон", "ICCID", "Фамилия", "Имя", "Отчество", "Пол",
            "Дата рождения", "Место рождения", "Серия", "Номер", "Дата выдачи", "Код подразделения", "Кем выдан",
            "Статус", "Номер заявки", "Есть в МТС", "Примечание",
        )
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val rows = sims.map { s ->
            listOf(
                contracts[s.contractId]?.title.orEmpty(), s.contractId, s.accountNo, s.msisdn, s.iccid,
                s.surname, s.name, s.patronymic, when (s.sex) { 'M' -> "М"; 'F' -> "Ж"; else -> "" },
                s.birthDate?.format(SimFormat.DATE).orEmpty(), s.birthPlace, s.series, s.number,
                s.issueDate?.format(SimFormat.DATE).orEmpty(), s.divisionCode, s.issuedBy,
                SimStatus.of(s).label, s.messageId, if (s.inMts) "да" else "нет", s.notes,
            )
        }
        val dir = File(context.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "SIM-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        file.writeText("﻿" + (listOf(header) + rows).joinToString("\r\n") { r -> r.joinToString(";") { q(it) } }, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Выгрузить список SIM")
    }
}
