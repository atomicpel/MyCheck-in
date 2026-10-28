package ru.mycheckin.passport.mts

import ru.mycheckin.passport.PassportData
import java.time.LocalDate

/** Контракт из структуры абонента. */
data class ContractInfo(val id: String, val orgName: String, val inn: String?)

/** Номер из структуры абонента: контракт, лицевой счёт, регион, SIM (ICCID) и IMSI. */
data class NumberInfo(
    val msisdn: String,
    val contractId: String,
    val accountNo: String,
    val region: String?,
    val iccid: String?,
    val imsi: String?,
)

data class HierarchyPage(val contracts: List<ContractInfo>, val numbers: List<NumberInfo>, val hasMore: Boolean)

/**
 * Разбор ответа `GET /b2b/v1/Service/HierarchyStructure` («Как посмотреть структуру абонента»):
 * организация → partyRole (контракты) → customerAccount (лицевые счета) → productRelationship (номера).
 */
object Hierarchy {
    const val PATH = "/Service/HierarchyStructure"
    const val MAX_PAGE_SIZE = 1000

    fun parse(body: String): HierarchyPage {
        val j = Json.parse(body)
        val orgs = (j as? List<*>) ?: listOf(j)
        val contracts = LinkedHashMap<String, ContractInfo>()
        val numbers = LinkedHashMap<String, NumberInfo>()
        for (org in orgs) {
            val orgName = org.field("name").str().orEmpty()
            for (role in org.field("partyRole").list()) {
                val contractId = role.field("id").str() ?: continue
                val inn = role.field("characteristic").list().firstOrNull { it.field("name").str() == "INN" }.field("value").str()
                contracts[contractId] = ContractInfo(contractId, orgName, inn)
                for (acc in role.field("customerAccount").list()) {
                    val accountNo = acc.field("accountNo").str().orEmpty()
                    for (rel in acc.field("productRelationship").list()) {
                        val product = rel.field("product")
                        val msisdn = product.field("productSerialNumber").str()?.filter { it.isDigit() } ?: continue
                        if (msisdn.isEmpty()) continue
                        val ch = product.field("productCharacteristic").list()
                        fun char(name: String) = ch.firstOrNull { it.field("name").str().equals(name, true) }.field("value").str()?.ifBlank { null }
                        numbers[msisdn] = NumberInfo(
                            msisdn = msisdn,
                            contractId = contractId,
                            accountNo = accountNo,
                            region = product.field("description").str()?.ifBlank { null },
                            iccid = char("SIM")?.filter { it.isLetterOrDigit() },
                            imsi = char("IMSI"),
                        )
                    }
                }
            }
        }
        return HierarchyPage(contracts.values.toList(), numbers.values.toList(), findHasMore(j))
    }

    /** Признак `hasMore` может стоять на любом уровне ответа — ищем везде. */
    private fun findHasMore(v: Any?): Boolean = when (v) {
        is Map<*, *> -> v.entries.any { (k, x) ->
            ((k as? String)?.equals("hasMore", true) == true && (x == true || x.str().equals("true", true))) || findHasMore(x)
        }
        is List<*> -> v.any(::findHasMore)
        else -> false
    }
}

/** Данные владельца номера, уже внесённые в МТС, и статус их подтверждения на Госуслугах. */
data class OwnerInfo(
    val confirmation: ConfirmationStatus,
    val surname: String?,
    val name: String?,
    val patronymic: String?,
    val sex: Char?,
    val birthDate: LocalDate?,
    val birthPlace: String?,
    val series: String?,
    val number: String?,
    val issueDate: LocalDate?,
    val issuedBy: String?,
    val divisionCode: String?,
    val address: RegAddress?,
) {
    val hasPassport: Boolean get() = !series.isNullOrBlank() && !number.isNullOrBlank()
    val hasName: Boolean get() = !surname.isNullOrBlank()

    fun toPassport(): PassportData = PassportData().also {
        it.surname = surname; it.name = name; it.patronymic = patronymic; it.sex = sex
        it.birthDate = birthDate; it.birthPlace = birthPlace; it.series = series; it.number = number
        it.issueDate = issueDate; it.issuedBy = issuedBy; it.divisionCode = divisionCode
        it.citizenship = "RUS"; it.source = "mts"
    }

    companion object {
        /** Разбор ответа `GET /b2b/v1/PersonalData/PersonalDataInfo`. */
        fun parse(body: String): OwnerInfo {
            val j = Json.parseOrNull(body)
            val person = ((j as? List<*>)?.firstOrNull() ?: j)
            val confirmation = person.field("characteristic").list()
                .firstOrNull { it.field("name").str() == "PersonalDataConfirmation" }.field("value").str()
            val parts = person.field("name").str()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val doc = person.field("identifiedBy").list().firstOrNull()
            val series = doc.field("documentSeries").str()?.filter { it.isDigit() }?.ifEmpty { null }
            val number = doc.field("documentNo").str()?.filter { it.isDigit() }?.ifEmpty { null }
            return OwnerInfo(
                confirmation = ConfirmationStatus(confirmation),
                surname = parts.getOrNull(0),
                name = parts.getOrNull(1),
                patronymic = parts.drop(2).joinToString(" ").ifEmpty { null },
                sex = parseSex(person.field("gender").str()),
                birthDate = parseDate(person.field("birthDate").str()),
                birthPlace = person.field("placeOfBirth").str()?.trim()?.ifEmpty { null },
                series = series,
                number = number,
                issueDate = parseDate(doc.field("issuedDate").str()),
                issuedBy = doc.field("issuedBy").str()?.trim()?.ifEmpty { null },
                divisionCode = doc.field("issuingAuthorityID").str()?.filter { it.isDigit() }
                    ?.takeIf { it.length == 6 }?.let { "${it.take(3)}-${it.drop(3)}" },
                address = parseAddress(person.field("contactMedium").list()),
            )
        }

        private fun parseSex(s: String?): Char? = when (s?.trim()?.lowercase()) {
            null, "" -> null
            "male", "m", "м", "муж", "мужской", "муж." -> 'M'
            "female", "f", "ж", "жен", "женский", "жен." -> 'F'
            else -> null
        }

        /** Даты МТС: «YYYY-MM-DDT00:00:00» (на всякий случай понимаем и «ДД.ММ.ГГГГ»). */
        fun parseDate(s: String?): LocalDate? {
            if (s.isNullOrBlank()) return null
            Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { m ->
                return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            }
            Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})").find(s)?.let { m ->
                return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
            }
            return null
        }

        /** Адрес регистрации — вложенные addressObject: District (регион), Area, City, Street, дом, квартира. */
        private fun parseAddress(media: List<Any?>): RegAddress? {
            val reg = media.firstOrNull { it.field("addressType").str() == "RegAddress" || it.field("type").str() == "RegisteredAddress" }
                ?: return null
            var region = ""; var city = ""; var street = ""; var house = ""; var apartment = ""
            var node: Any? = reg.field("address").field("addressObject")
            var guard = 0
            while (node != null && guard++ < 12) {
                val name = node.field("name").str().orEmpty()
                when (node.field("objectType").str()) {
                    "District" -> region = name
                    "City" -> city = name
                    "Street" -> street = name
                }
                if (node.field("type").str() == "RussianBasicHouseObject") house = node.field("number").str().orEmpty()
                node.field("apartment").field("number").str()?.let { apartment = it }
                node = node.field("addressObject")
            }
            return RegAddress(region = region, city = city, street = street, house = house, apartment = apartment)
                .takeIf { !it.isEmpty }
        }
    }
}
