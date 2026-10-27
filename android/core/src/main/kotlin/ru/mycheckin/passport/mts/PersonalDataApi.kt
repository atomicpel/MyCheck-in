package ru.mycheckin.passport.mts

import ru.mycheckin.passport.PassportData
import java.time.LocalDate

/**
 * Адрес регистрации (необязательный блок `Addresses` в запросе МТС).
 * Страна всегда «Россия» — другие варианты МТС не принимает.
 */
data class RegAddress(
    val region: String = "",
    val city: String = "",
    val street: String = "",
    val house: String = "",
    val apartment: String = "",
    val office: String = "",
    val zip: String = "",
) {
    val isEmpty: Boolean get() = listOf(region, city, street, house, apartment, office, zip).all { it.isBlank() }
}

/** Ответ на внесение данных: заявка принята, если [errorCode] пустой и есть [messageId]. */
data class SubmitResult(val errorCode: String, val errorDescription: String, val messageId: String?) {
    val accepted: Boolean get() = errorCode.isBlank() && !messageId.isNullOrBlank()
}

/** Статус заявки: Completed / InProgress / Faulted. */
data class OperationResult(val state: String?, val objectStatus: String?, val errors: List<String>) {
    /** Итоговый статус: статус по номеру точнее общего. */
    val status: String? get() = objectStatus ?: state
    val title: String get() = OPERATION_TITLES[status] ?: (status ?: "Статус не найден")

    companion object {
        val OPERATION_TITLES = mapOf(
            "Completed" to "Выполнено",
            "InProgress" to "В работе",
            "Faulted" to "Отклонено",
        )
    }
}

/** Статус подтверждения данных через Госуслуги (characteristic PersonalDataConfirmation). */
data class ConfirmationStatus(val code: String?) {
    val description: String get() = code?.let { DESCRIPTIONS[it] ?: "Код статуса: $it" } ?: "Статус подтверждения не найден"
    val confirmed: Boolean get() = code == "Activated"

    companion object {
        val DESCRIPTIONS = mapOf(
            "Activated" to "Данные подтверждены.",
            "WaitingForAcceptance" to "Данные отправлены в Госуслуги и ждут подтверждения пользователем.",
            "WaitingForCheck" to "Идёт техническая проверка.",
            "MismatchOfData" to "Данные в МТС не совпадают с данными на Госуслугах. Сверьте и внесите заново.",
            "NotFoundInEsia" to "У пользователя нет учётной записи на Госуслугах (или данные не совпали с ней).",
            "Refusal" to "Пользователь отказался от подтверждения на Госуслугах.",
            "RequestNotFoundInEsia" to "Техническая ошибка на стороне Госуслуг.",
            "Anonymous" to "Верификация не пройдена: данных пользователя в МТС нет.",
            "Migration" to "Номер оформлен до 01.06.2021, верификация не пройдена — данные нужно внести.",
            "ActivatedPortIn" to "Номер перенесён от другого оператора, верификация не пройдена.",
            "NotRequired" to "Подтверждение не требуется.",
            "Depersonalized" to "Номер по 44-ФЗ / 223-ФЗ: данные вносятся и подтверждаются через Госуслуги.",
        )
    }
}

/**
 * Запросы раздела «Персональные данные пользователя номера» МТС Бизнес API
 * (support.mts.ru/mts_biznes_api/personalnie-dannie-polzovatelya-nomera/).
 * Только граждане РФ, документ — паспорт РФ.
 */
object PersonalDataApi {
    const val CHANGE_PATH = "/PersonalData/ChangePersonalData"
    const val CHANGE_SOAP_ACTION =
        "\"http://schemas.sitels.ru/FORIS/IL/JsonApi/IResourceOperations%ChangeUserPhysicalResourceBulk\""

    const val RESULT_PATH = "/Operations/GetOperationResult"
    const val RESULT_SOAP_ACTION =
        "\"http://schemas.sitels.ru/FORIS/IL/JsonApi/IGetOperationsResultService%GetOperationResult\""

    const val INFO_PATH = "/PersonalData/PersonalDataInfo"

    /** Номер в формате 7XXXXXXXXXX или null, если это не мобильный номер РФ. */
    fun normalizeMsisdn(phone: String): String? {
        val d = phone.filter { it.isDigit() }
        val n = when {
            d.length == 10 -> "7$d"
            d.length == 11 && (d[0] == '7' || d[0] == '8') -> "7" + d.drop(1)
            else -> return null
        }
        return n.takeIf { it[1] == '9' }
    }

    private fun dateTime(d: LocalDate) = "${d}T00:00:00"

    /** Что не даст МТС принять запрос — проверяется до отправки, чтобы не тратить вызовы. */
    fun problems(p: PassportData, msisdn: String?, address: RegAddress? = null): List<String> = buildList {
        if (msisdn == null) add("Номер телефона: нужен мобильный номер РФ, 7 9XX XXX-XX-XX")
        if (p.surname.isNullOrBlank()) add("Не указана фамилия")
        if (p.name.isNullOrBlank()) add("Не указано имя")
        if (p.sex != 'M' && p.sex != 'F') add("Не указан пол")
        if (p.birthDate == null) add("Не указана дата рождения")
        if (p.series?.matches(Regex("\\d{4}")) != true) add("Серия паспорта — 4 цифры")
        if (p.number?.matches(Regex("\\d{6}")) != true) add("Номер паспорта — 6 цифр")
        if (p.issueDate == null) add("Не указана дата выдачи")
        if (p.issuedBy.isNullOrBlank()) add("Не указано, кем выдан паспорт")
        if ((p.issuedBy?.length ?: 0) > 256) add("«Кем выдан» — не длиннее 256 знаков")
        if (p.divisionCode?.matches(Regex("\\d{3}-\\d{3}")) != true) add("Код подразделения — в формате 000-000")
        if (address != null && !address.isEmpty && address.zip.isNotBlank() && !address.zip.matches(Regex("\\d{6}"))) {
            add("Индекс — 6 цифр")
        }
    }

    /** Тело запроса ChangePersonalData для одного номера. */
    fun changeBody(p: PassportData, msisdn: String, messageId: String, address: RegAddress? = null): String {
        val userData = linkedMapOf<String, Any?>(
            "Action" to "Create",
            "Addresses" to address?.takeIf { !it.isEmpty }?.let {
                listOf(linkedMapOf(
                    "Country" to "Россия",
                    "Region" to it.region.trim().ifEmpty { null },
                    "City" to it.city.trim().ifEmpty { null },
                    "Street" to it.street.trim().ifEmpty { null },
                    "Home" to it.house.trim().ifEmpty { null },
                    "Apartment" to it.apartment.trim().ifEmpty { null },
                    "Estate" to it.office.trim().ifEmpty { null },
                    "Zip" to it.zip.trim().ifEmpty { null },
                    "AddressTypes" to "RegAddress",
                ))
            },
            "LegalCategory" to mapOf("Code" to "1"),
            "Birthday" to dateTime(p.birthDate!!),
            "BirthPlace" to p.birthPlace?.trim()?.ifEmpty { null },
            "Gender" to if (p.sex == 'F') "Female" else "Male",
            "Identifications" to listOf(linkedMapOf(
                "Action" to "Create",
                "DocumentType" to mapOf("Code" to "21"),
                "Country" to mapOf("Code" to "RU"),
                "DocumentSeries" to p.series,
                "DocumentNumber" to p.number,
                "DateIssued" to dateTime(p.issueDate!!),
                "Issuer" to p.issuedBy!!.trim(),
                "IssuerCode" to p.divisionCode,
            )),
            "IsEntrepreneur" to false,
            "Names" to listOf(linkedMapOf(
                "Action" to "Create",
                "FirstName" to p.name!!.trim(),
                "SecondName" to p.patronymic?.trim()?.ifEmpty { null },
                "SurName" to p.surname!!.trim(),
                "Language" to mapOf("Code" to "1"),
            )),
        )
        return Json.write(linkedMapOf(
            "request" to mapOf("Items" to listOf(linkedMapOf("Msisdn" to msisdn, "UserData" to userData))),
            "SubscriberInformation" to subscriberInfo(messageId),
        ))
    }

    private fun subscriberInfo(messageId: String) = linkedMapOf(
        "MessageId" to messageId,
        "ReplyToURL" to "DB:",
        "SubscriberName" to "MobileAPI",
        "OperatorType" to "MTSBusinessAPI",
    )

    /** Тело запроса GetOperationResult. */
    fun resultBody(messageId: String): String = Json.write(linkedMapOf(
        "request" to mapOf("Body" to linkedMapOf("SourceTypeCode" to "MTSBusinessAPI", "MessageId" to messageId)),
        "SubscriberInformation" to linkedMapOf("SubscriberName" to "MobileAPI", "OperatorType" to "MTSBusinessAPI"),
    ))

    fun parseSubmit(body: String): SubmitResult {
        val j = Json.parseOrNull(body) ?: return SubmitResult("parse", "Ответ МТС не в формате JSON: ${body.take(300)}", null)
        val r = j.field("response")
        val code = (r.field("ErrorCode") ?: j.field("ErrorCode")).str().orEmpty()
        val desc = (r.field("ErrorDescription") ?: j.field("ErrorDescription")).str().orEmpty()
        val id = (j.field("MessageId") ?: r.field("MessageId")).str()
        return SubmitResult(code, desc, id)
    }

    fun parseResult(body: String, msisdn: String?): OperationResult {
        val j = Json.parseOrNull(body) ?: return OperationResult(null, null, listOf("Ответ не в формате JSON: ${body.take(300)}"))
        val items = j.path("response", "Body", "GetOperationResultResult").list()
        val bp = items.lastOrNull().field("BusinessProcessCommonResult")
        val objects = bp.field("Objects").list()
        val mine = objects.firstOrNull { msisdn != null && it.path("Object", "Msisdn").str() == msisdn } ?: objects.firstOrNull()
        val errors = (bp.field("Errors").list() + mine.field("Errors").list()).mapNotNull(::errorText)
        return OperationResult(bp.field("State").str(), mine.field("OperationStatus").str(), errors)
    }

    fun parseConfirmation(body: String): ConfirmationStatus {
        val j = Json.parseOrNull(body) ?: return ConfirmationStatus(null)
        val persons = (j as? List<*>) ?: listOf(j)
        for (person in persons) {
            for (c in person.field("characteristic").list()) {
                if (c.field("name").str() == "PersonalDataConfirmation") return ConfirmationStatus(c.field("value").str())
            }
        }
        return ConfirmationStatus(null)
    }

    /** Текст ошибки из элемента массива Errors или из тела ответа с ошибкой. */
    fun errorText(e: Any?): String? = when (e) {
        null -> null
        is String -> e.ifBlank { null }
        is Map<*, *> -> {
            val keys = listOf("ErrorDescription", "Description", "Message", "errorMessage", "message", "description", "Text")
            val text = keys.firstNotNullOfOrNull { k -> e.field(k).str()?.ifBlank { null } }
            val code = listOf("ErrorCode", "Code", "errorCode", "code").firstNotNullOfOrNull { k -> e.field(k).str()?.ifBlank { null } }
            when {
                text != null && code != null -> "$text (код $code)"
                text != null -> text
                code != null -> "Код ошибки $code"
                else -> Json.write(e).take(300)
            }
        }
        else -> e.toString()
    }

    /** Понятное описание тела ответа с HTTP-ошибкой. */
    fun describeHttpError(body: String): String {
        val j = Json.parseOrNull(body)
        val nested = j.field("fault") ?: j.field("response") ?: j
        return errorText(nested) ?: errorText(j) ?: body.take(500).ifBlank { "Пустой ответ" }
    }
}
