package ru.mycheckin.passport.mts

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/*
 * Сведения о номерах и лицевых счетах из МТС Бизнес API (support.mts.ru/mts_biznes_api/):
 * баланс, начисления, услуги и блокировки, тариф, остатки пакетов, переадресация, роуминг,
 * тип SIM, комментарии, платежи, Обещанный платёж, корпоративный бюджет, документы и заявки.
 *
 * Разбор ответов терпим к расхождениям справки и реальных ответов: поля ищутся без учёта
 * регистра, числа понимаются и строкой, и числом, массив и одиночный объект равноценны.
 */

/** Число из ответа МТС: 12.5, "12.5", "12,5". */
fun Any?.num(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
    else -> null
}

private fun Any?.items(): List<Any?> = when (this) {
    null -> emptyList()
    is List<*> -> this
    else -> listOf(this)
}

/** Пары name/value (characteristic, productCharacteristic) в словарь. */
private fun Any?.characteristics(): Map<String, String?> =
    list().mapNotNull { c -> c.field("name").str()?.let { it to c.field("value").str() } }.toMap()

object MtsDate {
    /** «2021-10-26T14:55:42», «…+0300», «…Z», «2021-10-26». Дата «0001-01-01» у МТС значит «бессрочно» — null. */
    fun parse(s: String?): LocalDateTime? {
        val t = s?.trim().orEmpty()
        if (t.length < 10) return null
        val dt = runCatching {
            if (t.length >= 19 && t[10] == 'T') LocalDateTime.parse(t.take(19)) else LocalDate.parse(t.take(10)).atStartOfDay()
        }.getOrNull() ?: return null
        return dt.takeIf { it.year > 1900 }
    }

    /** Начало дня в формате, который МТС ждёт в периодах: 2026-10-01T00:00:00Z. */
    fun dayStart(d: LocalDate) = "${d}T00:00:00Z"
    fun dayEnd(d: LocalDate) = "${d}T23:59:59Z"
}

// ---------------------------------------------------------------------------------------------
// Модели
// ---------------------------------------------------------------------------------------------

/** Баланс лицевого счёта; [creditLimit] — порог полной/принудительной блокировки. */
data class Balance(
    val accountNo: String,
    val amount: Double?,
    val creditLimit: Double?,
    val currency: String?,
    val validTo: LocalDateTime?,
    val msisdns: List<String> = emptyList(),
)

/** Услуга, блокировка или тариф из каталога МТС (ProductInfo). */
data class ProductItem(
    val code: String,
    val name: String,
    val status: String? = null,
    val since: LocalDateTime? = null,
    val until: LocalDateTime? = null,
    /** Абонентская плата с налогом (если МТС прислал цены) и её период: MONTH / DAY. */
    val fee: Double? = null,
    val feeUnit: String? = null,
    val activated: Boolean? = null,
    val blocked: Boolean? = null,
    val actions: List<String> = emptyList(),
) {
    val isBlock: Boolean get() = code.startsWith("BL", ignoreCase = true)
    val feeText: String? get() = fee?.let { Money.format(it) + (Money.unit(feeUnit)?.let { u -> " $u" } ?: "") }
}

/** Действующий тариф. */
data class Tariff(val code: String, val name: String, val regionalName: String?, val since: LocalDateTime?)

/** Абонентская плата по тарифу: текущая и со следующего периода (если меняется). */
data class TariffFee(val current: Double?, val currentUnit: String?, val next: Double?, val nextUnit: String?, val discount: Double?)

/** Тариф, на который можно перейти. */
data class AvailableTariff(val id: String, val name: String, val price: Double?, val unit: String?)

/** Остаток пакета (минуты, SMS, интернет) или накопительный счётчик. */
data class Counter(
    val title: String,
    val kind: Kind,
    val left: Double?,
    val total: Double?,
    val used: Double?,
    val until: LocalDateTime?,
) {
    enum class Kind(val unit: String) { INTERNET("МБ"), MINUTES("мин"), SMS("SMS"), MONEY("₽"), OTHER("") }

    /** Доля остатка 0..1 (если известен объём пакета). */
    val share: Double? get() = if (left != null && total != null && total > 0) (left / total).coerceIn(0.0, 1.0) else null
}

data class Forwarding(val type: String, val address: String, val timer: Int?, val numType: String?) {
    val title: String get() = TYPES[type] ?: type

    companion object {
        val TYPES = linkedMapOf(
            "CFU" to "Всегда",
            "CFB" to "Если занято",
            "CFNRY" to "Если нет ответа",
            "CFNRC" to "Если недоступен",
        )
    }
}

/** Где SIM-карта: [countryName] == null — в России (или событий не было). */
data class Location(val countryName: String?, val alpha2: String?, val date: LocalDateTime?) {
    val abroad: Boolean get() = !countryName.isNullOrBlank() || !alpha2.isNullOrBlank()
}

data class Payment(
    val date: LocalDateTime?,
    val amount: Double?,
    val form: String?,
    val type: String?,
    val document: String?,
    val note: String?,
    val target: String?,
)

data class PromisedPayment(
    val amount: Double?,
    val dateTo: LocalDateTime?,
    val maxQuantity: Int?,
    val quantity: Int?,
    val prices: List<Price>,
    val possibleWithNegative: Boolean?,
    val minBalance: Double?,
    val msisdn: String?,
) {
    data class Price(val from: Double?, val to: Double?, val price: Double?)

    val available: Boolean get() = (maxQuantity ?: 0) > (quantity ?: 0)
    fun priceFor(sum: Double): Double? = prices.firstOrNull { p -> sum >= (p.from ?: 0.0) && sum <= (p.to ?: Double.MAX_VALUE) }?.price
    val maxAmount: Double? get() = prices.mapNotNull { it.to }.maxOrNull()
}

/** Правило списания корпоративного бюджета. */
data class BudgetRule(
    val code: String?,
    val title: String,
    val subTitle: String?,
    val limit: String?,
    val remainder: String?,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
)

/** Ответ на запрос изменения: номер заявки (EventID / messageId). */
data class EventAck(val id: String?, val time: String?, val message: String? = null)

/** Статус заявки по EventID / messageId. */
data class RequestStatus(
    val id: String,
    val status: String?,
    val category: String?,
    val errorCode: String?,
    val errorDescription: String?,
    val updated: LocalDateTime?,
) {
    val done: Boolean get() = status == "Completed"
    val failed: Boolean get() = status == "Faulted"
    val title: String get() = OperationResult.OPERATION_TITLES[status] ?: (status ?: "Нет сведений")
}

/** Событие детализации: звонок, SMS, интернет, абонплата, платёж. */
data class StatementEvent(
    val date: LocalDateTime?,
    val type: String?,
    val label: String?,
    val amount: Double?,
    val party: String?,
    val direction: String?,
    val units: String?,
)

object Money {
    fun format(v: Double): String {
        val s = String.format(Locale("ru"), "%,.2f", v).replace('\u202F', '\u00A0')
        return s.removeSuffix(",00") + " ₽"
    }

    fun unit(u: String?): String? = when (u?.uppercase()) {
        "MONTH" -> "в мес."
        "DAY" -> "в сутки"
        null, "" -> null
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------
// Разбор ответов
// ---------------------------------------------------------------------------------------------

object LineParse {

    private fun json(body: String): Any? {
        val j = Json.parseOrNull(body.trim())
        // Иногда массив приходит строкой внутри JSON — разбираем второй раз.
        val t = (j as? String)?.trim()
        return if (t != null && (t.startsWith("[") || t.startsWith("{"))) Json.parseOrNull(t) ?: j else j
    }

    fun balances(body: String): List<Balance> = json(body).items().mapNotNull { b ->
        val acc = b.field("accountNo").str() ?: return@mapNotNull null
        val cab = b.field("customerAccountBalance").list().firstOrNull()
        val numbers = b.field("productRelationship").list().mapNotNull { r ->
            r.path("product", "productCharacteristic").characteristics()["MSISDN"]
        }
        Balance(
            accountNo = acc,
            amount = cab.path("remainedAmount", "amount").num(),
            creditLimit = cab.path("customerAccount", "creditLimit").num(),
            currency = cab.path("remainedAmount", "unitOfMeasure").str(),
            validTo = MtsDate.parse((cab.path("validFor", "endDateTime") ?: cab.path("customerAccount", "validFor", "endDateTime")).str()),
            msisdns = numbers,
        )
    }

    fun products(body: String): List<ProductItem> = json(body).items().mapNotNull { p ->
        val code = p.field("externalID").str()?.ifBlank { null } ?: return@mapNotNull null
        val ch = p.field("product").list().firstOrNull().field("productCharacteristic").characteristics()
        val prices = p.field("productOfferingPrice").list().filter { it.path("price", "taxIncludedAmount").num() != null }
        val price = prices.firstOrNull { "discount" !in it.field("name").str().orEmpty().lowercase() } ?: prices.firstOrNull()
        ProductItem(
            code = code,
            name = p.field("name").str().orEmpty().ifBlank { code },
            status = p.field("status").str(),
            since = MtsDate.parse(p.path("validFor", "startDateTime").str()),
            until = MtsDate.parse(p.path("validFor", "endDateTime").str()),
            fee = price.path("price", "taxIncludedAmount").num(),
            feeUnit = price.field("unitOfMeasure").str(),
            activated = ch["IsActivated"]?.toBooleanStrictOrNull(),
            blocked = ch["IsBlocked"]?.toBooleanStrictOrNull(),
            actions = p.field("actionAllowed").list().mapNotNull { it.str() },
        )
    }

    fun tariff(body: String): Tariff? {
        val o = json(body).items().firstOrNull().field("productOffering") ?: return null
        val code = o.field("externalID").str().orEmpty()
        val name = o.field("name").str().orEmpty()
        if (code.isBlank() && name.isBlank()) return null
        return Tariff(
            code = code,
            name = name.ifBlank { code },
            regionalName = o.field("bundledProductOffering").list().firstOrNull().field("name").str(),
            since = MtsDate.parse(o.path("validFor", "startDateTime").str()),
        )
    }

    fun tariffFee(body: String): TariffFee? {
        val prices = json(body).items().firstOrNull().field("productOfferingPrice").list()
        if (prices.isEmpty()) return null
        fun p(name: String) = prices.firstOrNull { it.field("name").str() == name }
        val cur = p("CurrentPrimaryFee")
        val next = p("NextPrimaryFee")
        return TariffFee(
            current = cur.path("price", "taxIncludedAmount").num(),
            currentUnit = cur.field("unitOfMeasure").str(),
            next = next.path("price", "taxIncludedAmount").num(),
            nextUnit = next.field("unitOfMeasure").str(),
            discount = p("CurrentPrimaryFee.Discount").path("price", "dutyFreeAmount").num(),
        )
    }

    fun availableTariffs(body: String): List<AvailableTariff> = json(body).items().mapNotNull { t ->
        val id = (t.field("id") ?: t.field("externalID") ?: t.field("externalId")).str()?.ifBlank { null } ?: return@mapNotNull null
        val price = t.field("productOfferingPrice").list()
            .firstOrNull { it.field("type").str() != "DiscountProdOfferPriceAlteration" && it.path("price", "taxIncludedAmount").num() != null }
        AvailableTariff(id, t.field("name").str().orEmpty().ifBlank { id }, price.path("price", "taxIncludedAmount").num(), price.field("unitOfMeasure").str())
    }

    /**
     * Остатки пакетов (`Bills/ValidityInfo`). Блоки ForisCounters (минуты, SMS, деньги) описаны через
     * productPrice.unitOfMeasure и значения CurrentValue / *InitialValue; блоки интернета (h2oProfile) —
     * через квоту в КБ: reminder (остаток), BTQ/BQ (объём), Consumption (израсходовано).
     */
    fun counters(body: String): List<Counter> {
        val out = mutableListOf<Counter>()

        fun values(c: Any?): Map<String, Any?> {
            val m = HashMap<String, Any?>()
            for (v in c.field("prodSpecCharacteristicValue").list()) {
                val t = v.field("valueType").str() ?: continue
                m[t] = v.field("value")
            }
            return m
        }

        fun handle(title: String, unit: String?, chars: List<Any?>, until: String?) {
            for (c in chars) {
                val v = values(c)
                val end = MtsDate.parse(c.path("validFor", "endDateTime").str() ?: until)
                val cTitle = title.ifBlank { c.field("name").str().orEmpty() }.ifBlank { "Пакет" }
                when {
                    "reminder" in v || "BTQ" in v || "BQ" in v -> {
                        val kb = 1024.0
                        out += Counter(cTitle, Counter.Kind.INTERNET,
                            left = v["reminder"].num()?.div(kb), total = (v["BTQ"].num() ?: v["BQ"].num())?.div(kb),
                            used = v["Consumption"].num()?.div(kb), until = MtsDate.parse(v["Rotate.At"].str()) ?: end)
                    }
                    "CurrentValue" in v -> {
                        val kind = when (unit?.uppercase()) {
                            "ITEM" -> Counter.Kind.SMS
                            "SECOND", "MINUTE" -> Counter.Kind.MINUTES
                            "BYTE" -> Counter.Kind.INTERNET
                            "MONEY" -> Counter.Kind.MONEY
                            else -> Counter.Kind.OTHER
                        }
                        val k = when (unit?.uppercase()) { "SECOND" -> 60.0; "BYTE" -> 1024.0 * 1024.0; else -> 1.0 }
                        val current = v["CurrentValue"].num()?.div(k)
                        val initial = (v["CurrentInitialValue"].num() ?: v["PeriodInitialValue"].num() ?: v["BaseInitialValue"].num())?.div(k)
                            ?.takeIf { it > 0 }
                        val accumulation = v["CounterValueType"].str() == "Accumulation"
                        out += if (accumulation) Counter(cTitle, kind, left = null, total = initial, used = current, until = end)
                        else Counter(cTitle, kind, left = current, total = initial,
                            used = if (current != null && initial != null) (initial - current).coerceAtLeast(0.0) else null, until = end)
                    }
                }
            }
        }

        fun walk(n: Any?) {
            when (n) {
                is Map<*, *> -> {
                    val spec = n.field("productSpecification")
                    if (spec is Map<*, *>) {
                        val unit = n.field("productPrice").list().firstOrNull().field("unitOfMeasure").str()
                        handle(spec.field("name").str().orEmpty(), unit, spec.field("productSpecCharacteristic").list(),
                            n.path("validFor", "endDateTime").str())
                        return
                    }
                    val chars = n.field("productSpecCharacteristic")
                    if (chars is List<*>) {
                        handle(n.field("name").str().orEmpty().let { if (it == "generic") "Интернет" else it }, null, chars.list(), null)
                        return
                    }
                    n.values.forEach(::walk)
                }
                is List<*> -> n.forEach(::walk)
            }
        }
        walk(json(body))
        // Счётчики без названия и без значений не показываем.
        return out.filter { it.left != null || it.used != null }
    }

    /** Начисления за текущий месяц: номер → сумма. */
    fun charges(body: String): Map<String, Double?> = json(body).items().mapNotNull { c ->
        val id = c.field("id").str() ?: return@mapNotNull null
        id to c.path("remainedAmount", "amount").num()
    }.toMap()

    /** Комментарии: номер (или лицевой счёт) → текст. */
    fun comments(body: String): Map<String, String> = json(body).items().mapNotNull { c ->
        val key = (c.field("msisdn") ?: c.field("account")).str() ?: return@mapNotNull null
        key to c.field("comment").str().orEmpty()
    }.toMap()

    fun unpaid(body: String): Double? = json(body).path("data", "amount").num()

    fun forwarding(body: String): List<Forwarding> = json(body).items().mapNotNull { f ->
        val ch = f.field("productCharacteristic").characteristics()
        val address = ch["ForwardingAddress"]?.ifBlank { null } ?: return@mapNotNull null
        Forwarding(ch["ForwardingType"].orEmpty(), address, ch["NoReplyTimer"]?.toIntOrNull(), ch["NumType"])
    }

    fun location(body: String): Location {
        val j = json(body)
        val loc = j.field("Locations").list().firstOrNull()
        val countryId = loc.field("countryId").str()?.trim()?.ifBlank { null }
        val country = j.path("references", "Countries").list().firstOrNull { countryId == null || it.field("id").str()?.trim() == countryId }
        return Location(
            countryName = country.field("name").str()?.trim()?.ifBlank { null } ?: countryId?.let { "Страна $it" },
            alpha2 = country.field("alpha2").str()?.trim()?.ifBlank { null },
            date = MtsDate.parse((loc.field("date") ?: j.field("date")).str()),
        )
    }

    fun simType(body: String): String? = json(body).field("simType").str()

    fun payments(body: String): List<Payment> = json(body).field("payments").list().map { p ->
        val common = p.path("paymentParameters", "commonParameter")
        Payment(
            date = MtsDate.parse((p.field("dateOfPayment") ?: p.field("dateOfTransaction")).str()),
            amount = (p.field("amount") ?: p.field("internalAmount")).num(),
            form = common.field("paymentForm").str(),
            type = p.field("paymentType").str(),
            document = p.field("paymentDocumentNumber").str(),
            note = common.field("note").str(),
            target = common.field("customerDetails").str(),
        )
    }.sortedByDescending { it.date }

    fun promised(body: String): PromisedPayment {
        val j = json(body)
        return PromisedPayment(
            amount = j.field("amount").num(),
            dateTo = MtsDate.parse(j.field("dateTo").str()),
            maxQuantity = j.field("maxQuantity").num()?.toInt(),
            quantity = j.field("quantity").num()?.toInt(),
            prices = j.field("price").list().map { PromisedPayment.Price(it.field("amountFrom").num(), it.field("amountTo").num(), it.field("price").num()) },
            possibleWithNegative = j.field("isPossibleNegativeBalance").str()?.toBooleanStrictOrNull(),
            minBalance = j.field("minBalance").num(),
            msisdn = j.field("msisdn").str(),
        )
    }

    fun budgetRules(body: String): List<BudgetRule> = json(body).items().mapNotNull { r ->
        val info = r.field("ruleInformation")
        val title = (info.field("title") ?: info.field("productName")).str() ?: return@mapNotNull null
        BudgetRule(
            code = info.field("productCode").str(),
            title = title,
            subTitle = info.field("subTitle").str(),
            limit = r.path("valueAndCurrency", "formattedValue").str(),
            remainder = r.path("remainder", "formattedValue").str(),
            from = MtsDate.parse(r.field("activeFrom").str()),
            to = MtsDate.parse(r.field("activeTo").str()),
        )
    }

    /**
     * Пакетный ответ (начисления, комментарии) — по ключам: каждому номеру или счёту свой
     * фрагмент в том же виде, что и ответ МТС, чтобы хранить его и разбирать как обычный ответ.
     * Ключа нет в ответе — пустой массив (например, комментарий не задан).
     */
    fun splitBulk(section: LineSection, body: String, keys: List<String>): Map<String, String> {
        if (keys.size == 1) return mapOf(keys[0] to body)
        val idField = when (section) {
            LineSection.CHARGES -> "id"
            LineSection.COMMENT -> "msisdn"
            LineSection.ACCOUNT_COMMENT -> "account"
            else -> return keys.associateWith { body }
        }
        val byKey = json(body).items().groupBy { it.field(idField).str() }
        return keys.associateWith { k -> Json.write(byKey[k].orEmpty()) }
    }

    /** Номер заявки из ответа на изменение: {"eventID": …}, просто строка, messageId/requestId или заголовок x-trace-id. */
    fun eventAck(r: HttpResponse): EventAck {
        val j = Json.parseOrNull(r.body.trim()) ?: r.body.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }
        val header = r.headers.entries.firstOrNull { it.key.equals("x-trace-id", true) }?.value?.trim()?.ifBlank { null }
        if (j is String) return EventAck(j.trim().ifBlank { header }, null)
        if (j is Number) return EventAck(r.body.trim(), null)
        val first = j.items().firstOrNull()
        val id = listOf(
            first.field("eventID"), first.field("eventId"), first.field("messageId"),
            first.path("request", "messageId"), first.path("response", "requestId"), first.path("response", "messageId"),
        ).firstNotNullOfOrNull { it.str()?.ifBlank { null } } ?: header
        val message = (first.field("message") ?: first.path("response", "errorCode")).str()
        return EventAck(id, first.field("eventTime").str(), message)
    }

    fun requestStatus(body: String, id: String?): List<RequestStatus> = json(body).field("relatedParty").list().mapNotNull { p ->
        val pid = (p.field("id") ?: p.field("externalID")).str() ?: return@mapNotNull null
        val ch = p.field("characteristic").characteristics()
        RequestStatus(
            id = pid,
            status = p.field("status").str(),
            category = ch["category"],
            errorCode = ch["error_code"]?.ifBlank { null },
            errorDescription = ch["error_description"]?.ifBlank { null },
            updated = MtsDate.parse(ch["db_last_upd"]),
        )
    }.let { all -> if (id == null) all else all.filter { it.id.equals(id, true) }.ifEmpty { all } }

    /** Детализация: ищется первый массив событий (объектов с полем date) на любой глубине ответа. */
    fun statement(body: String): List<StatementEvent> {
        fun find(n: Any?): List<Any?>? = when (n) {
            is List<*> -> if (n.isNotEmpty() && n.all { it is Map<*, *> && it.field("date") != null }) n else n.firstNotNullOfOrNull(::find)
            is Map<*, *> -> n.values.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(json(body)).orEmpty().map { e ->
            val ch = e.field("Characteristics") ?: e.field("characteristics")
            StatementEvent(
                date = MtsDate.parse(e.field("date").str()),
                type = e.field("type").str(),
                label = e.field("label").str(),
                amount = e.field("amount").num(),
                party = ch.field("calledMsisdn").str()?.ifBlank { null },
                direction = ch.field("direction").str(),
                units = listOfNotNull(ch.field("factUnits").str() ?: ch.field("numberOfUnits").str(),
                    ch.field("factUnitsCode").str() ?: ch.field("unitOfMeasureCode").str()).joinToString(" ").ifBlank { null },
            )
        }.sortedByDescending { it.date }
    }
}

// ---------------------------------------------------------------------------------------------
// Вызовы
// ---------------------------------------------------------------------------------------------

/**
 * Методы МТС Бизнес API для сведений о номерах и управления ими. Все вызовы идут через [MtsClient]
 * (лимитер, токен, повторы при 429/503 и обрыве связи для чтения).
 */
class LineApi(private val c: MtsClient) {

    private fun msisdnQ(msisdn: String) = listOf("msisdn" to msisdn)

    private fun productQuery(msisdn: String, type: String, available: Boolean, prices: Boolean) = buildList {
        add("category.name" to "MobileConnectivity")
        add("marketSegment.characteristic.name" to "MSISDN")
        add("marketSegment.characteristic.value" to msisdn)
        add("productOffering.actionAllowed" to if (available) "create" else "none")
        add("productOffering.productSpecification.productSpecificationType.name" to type)
        add("applyTimeZone" to "true")
        if (prices) add("fields" to "CalculatePrices")
    }

    private fun get(path: String, query: List<Pair<String, String>>) = c.request("GET", path, query).body

    private fun post(path: String, body: Any, query: List<Pair<String, String>> = emptyList()) =
        c.request("POST", path, query, Json.write(body), safeToRepeat = true).body

    private fun countersQuery(msisdn: String) = listOf(
        "fields" to "MOAF,forisCounters,h2oProfile",
        "customerAccount.accountNo" to msisdn,
        "customerAccount.productRelationship.product.productLine.name" to "Counters",
    )

    private fun tariffQuery(msisdn: String) = listOf(
        "productCharacteristic.name" to "MSISDN",
        "productCharacteristic.value" to msisdn,
        "fields" to "productCharacteristic,place.role,place.externalID,productOffering.name,productOffering.href,productOffering.externalID,productOffering.validFor,TariffProductWithRegionalTariff",
        "productLine.name" to "MobileConnectivity",
    )

    private fun forwardingQuery(msisdn: String) = listOf(
        "productCharacteristic.name" to "MSISDN", "productCharacteristic.value" to msisdn, "productLine.name" to "CallForwarding",
    )

    // ---- Разделы для хранения: тело ответа как есть ----

    /**
     * Тело ответа МТС для раздела [s] по номеру или лицевому счёту [key]. Хранится как есть и
     * разбирается [LineParse] при чтении (см. [LineSnapshot.build]). Пакетные разделы — [rawBulk].
     */
    fun raw(s: LineSection, key: String): String = when (s) {
        LineSection.BALANCE -> get(PATH_BALANCE_ACCOUNT, listOf("fields" to "MOAF", "accountNo" to key))
        LineSection.UNPAID -> post(PATH_UNPAID, listOf(key))
        LineSection.PROMISED -> get(PATH_PROMISED_PARAMS, listOf("account" to key))
        LineSection.ACCOUNT_BUDGET -> post(PATH_BUDGET_ACCOUNT, linkedMapOf("personalAccountNumber" to key, "language" to "Ru"))
        LineSection.BLOCKS -> get(PATH_PRODUCTS, productQuery(key, "block", false, false))
        LineSection.SERVICES -> get(PATH_PRODUCTS, productQuery(key, "service", false, true))
        LineSection.LOCATION -> get(PATH_LOCATION, msisdnQ(key))
        LineSection.COUNTERS -> get(PATH_COUNTERS, countersQuery(key))
        LineSection.TARIFF -> get(PATH_TARIFF, tariffQuery(key))
        LineSection.TARIFF_FEE -> get(PATH_TARIFF_FEE, msisdnQ(key))
        LineSection.FORWARDING -> get(PATH_FORWARDING, forwardingQuery(key))
        LineSection.BUDGET -> post(PATH_BUDGET_MSISDN, linkedMapOf("msisdn" to key, "language" to "Ru"))
        LineSection.SIM_TYPE -> get(PATH_SIM_TYPE, msisdnQ(key))
        LineSection.CHARGES, LineSection.COMMENT, LineSection.ACCOUNT_COMMENT -> rawBulk(s, listOf(key)).getValue(key)
    }

    /** Пакетный раздел для нескольких номеров или счетов (до [LineSection.bulkSize]) — одним вызовом. */
    fun rawBulk(s: LineSection, keys: List<String>): Map<String, String> {
        require(keys.size in 1..s.bulkSize) { "${s.title}: за один вызов не больше ${s.bulkSize}" }
        val body = when (s) {
            LineSection.CHARGES -> post(PATH_CHARGES, keys.map { mapOf("id" to it) }, if (keys.size > 1) listOf("isBulk" to "true") else emptyList())
            LineSection.COMMENT -> post(PATH_COMMENTS_GET, mapOf("msisdns" to keys))
            LineSection.ACCOUNT_COMMENT -> post(PATH_ACC_COMMENTS_GET, mapOf("accounts" to keys))
            else -> return keys.associateWith { raw(s, it) }
        }
        return LineParse.splitBulk(s, body, keys)
    }

    // ---- Баланс и начисления ----

    fun balanceByAccount(accountNo: String): Balance? = LineParse.balances(raw(LineSection.BALANCE, accountNo)).firstOrNull()

    fun balanceByMsisdn(msisdn: String): Balance? = LineParse.balances(c.request("GET", PATH_BALANCE_MSISDN,
        listOf("fields" to "MOAF", "characteristic.name" to "MSISDN", "characteristic.value" to msisdn)).body).firstOrNull()

    /** Начисления за текущий месяц, до [BULK_CHARGES] номеров за вызов. */
    fun charges(msisdns: List<String>): Map<String, Double?> {
        require(msisdns.size in 1..BULK_CHARGES)
        val body = Json.write(msisdns.map { mapOf("id" to it) })
        val q = if (msisdns.size > 1) listOf("isBulk" to "true") else emptyList()
        return LineParse.charges(c.request("POST", PATH_CHARGES, q, body, safeToRepeat = true).body)
    }

    /** Сумма неоплаченных счетов по лицевым счетам (общая по всем переданным). */
    fun unpaid(accounts: List<String>): Double? =
        LineParse.unpaid(c.request("POST", PATH_UNPAID, body = Json.write(accounts), safeToRepeat = true).body)

    fun counters(msisdn: String): List<Counter> = LineParse.counters(raw(LineSection.COUNTERS, msisdn))

    fun paymentsByAccount(accountNo: String, from: LocalDate, to: LocalDate): List<Payment> = LineParse.payments(c.request("GET", PATH_PAYMENTS_ACCOUNT,
        listOf("accountNumber" to accountNo, "dateFrom" to MtsDate.dayStart(from), "dateTo" to MtsDate.dayEnd(to))).body)

    fun paymentsByMsisdn(msisdn: String, from: LocalDate, to: LocalDate): List<Payment> = LineParse.payments(c.request("GET", PATH_PAYMENTS_MSISDN,
        listOf("msisdn" to msisdn, "dateFrom" to MtsDate.dayStart(from), "dateTo" to MtsDate.dayEnd(to))).body)

    fun statement(msisdn: String, from: LocalDate, to: LocalDate): List<StatementEvent> = LineParse.statement(c.request("GET", PATH_STATEMENT,
        listOf("msisdn" to msisdn, "startDateTime" to MtsDate.dayStart(from), "endDateTime" to MtsDate.dayEnd(to))).body)

    // ---- Услуги, блокировки, тариф ----

    fun services(msisdn: String, prices: Boolean = true) = LineParse.products(c.request("GET", PATH_PRODUCTS, productQuery(msisdn, "service", false, prices)).body)
    fun blocks(msisdn: String) = LineParse.products(raw(LineSection.BLOCKS, msisdn))
    fun availableServices(msisdn: String) = LineParse.products(c.request("GET", PATH_PRODUCTS, productQuery(msisdn, "service", true, false)).body)
    fun availableBlocks(msisdn: String) = LineParse.products(c.request("GET", PATH_PRODUCTS, productQuery(msisdn, "block", true, false)).body)

    fun tariff(msisdn: String): Tariff? = LineParse.tariff(raw(LineSection.TARIFF, msisdn))

    fun tariffFee(msisdn: String): TariffFee? = LineParse.tariffFee(raw(LineSection.TARIFF_FEE, msisdn))

    fun availableTariffs(msisdn: String): List<AvailableTariff> = LineParse.availableTariffs(c.request("GET", PATH_PRODUCTS, listOf(
        "marketSegment.characteristic.name" to "MSISDN",
        "marketSegment.characteristic.value" to msisdn,
        "fields" to "productOffering.externalId,productOffering.productOfferingPrice.price",
        "category.name" to "AvailibleTariffPlann",   // так в справке МТС
    )).body)

    private fun productBody(validate: Boolean, action: String, code: String) = Json.write(linkedMapOf(
        "characteristic" to listOf(mapOf("name" to if (validate) "ValidateMobileConnectivity" else "MobileConnectivity")),
        "item" to listOf(linkedMapOf(
            "action" to action,
            "product" to linkedMapOf(
                "externalID" to code,
                "productCharacteristic" to listOf(linkedMapOf("name" to "ResourceServiceRequestItemType", "value" to "ResourceServiceRequestItem")),
            ),
        )),
    ))

    /** Проверить, можно ли подключить услугу: МТС регистрирует заявку-проверку, итог — по её статусу. */
    fun validateService(msisdn: String, code: String): EventAck =
        LineParse.eventAck(c.request("PATCH", PATH_MODIFY, msisdnQ(msisdn), productBody(true, "create", code), safeToRepeat = true))

    /** Подключить ([add] = true) или отключить услугу либо блокировку (код BLxxxx). */
    fun modifyProduct(msisdn: String, code: String, add: Boolean): EventAck =
        LineParse.eventAck(c.request("POST", PATH_MODIFY, msisdnQ(msisdn), productBody(false, if (add) "create" else "delete", code)))

    private fun tariffBody(code: String) = Json.write(mapOf("item" to listOf(mapOf("product" to linkedMapOf(
        "externalID" to code,
        "productCharacteristic" to listOf(linkedMapOf("name" to "productType", "value" to "tariffPlan")),
    )))))

    fun validateTariff(msisdn: String, code: String): EventAck =
        LineParse.eventAck(c.request("POST", PATH_TARIFF_VALIDATE, msisdnQ(msisdn), tariffBody(code), safeToRepeat = true))

    fun changeTariff(msisdn: String, code: String): EventAck =
        LineParse.eventAck(c.request("POST", PATH_TARIFF_CHANGE, msisdnQ(msisdn), tariffBody(code)))

    // ---- Переадресация, роуминг, SIM ----

    fun forwarding(msisdn: String): List<Forwarding> = LineParse.forwarding(raw(LineSection.FORWARDING, msisdn))

    /** Включить ([add]) или выключить переадресацию. */
    fun changeForwarding(msisdn: String, rule: Forwarding, add: Boolean): EventAck = LineParse.eventAck(c.request("POST", PATH_FORWARDING_CHANGE,
        body = Json.write(linkedMapOf(
            "characteristic" to listOf(linkedMapOf("name" to "MSISDN", "value" to msisdn)),
            "item" to listOf(linkedMapOf(
                "action" to if (add) "create" else "delete",
                "product" to linkedMapOf(
                    "productLine" to mapOf("name" to "CallForwarding"),
                    "productCharacteristic" to listOf(
                        linkedMapOf("name" to "ForwardingAddress", "value" to rule.address),
                        linkedMapOf("name" to "ForwardingType", "value" to rule.type),
                        linkedMapOf("name" to "NoReplyTimer", "value" to (rule.timer ?: 0).toString()),
                        linkedMapOf("name" to "NumType", "value" to (rule.numType ?: "Regular")),
                    ),
                ),
            )),
            "relatedParty" to linkedMapOf("type" to "Individual", "location" to linkedMapOf("id" to "000000000", "role" to "point-of-sale")),
        ))))

    fun location(msisdn: String): Location = LineParse.location(raw(LineSection.LOCATION, msisdn))

    fun simType(msisdn: String): String? = LineParse.simType(raw(LineSection.SIM_TYPE, msisdn))

    // ---- Комментарии ----

    fun comments(msisdns: List<String>): Map<String, String> {
        require(msisdns.size in 1..BULK_COMMENTS)
        return LineParse.comments(c.request("POST", PATH_COMMENTS_GET, body = Json.write(mapOf("msisdns" to msisdns)), safeToRepeat = true).body)
    }

    fun accountComments(accounts: List<String>): Map<String, String> {
        require(accounts.size in 1..BULK_COMMENTS)
        return LineParse.comments(c.request("POST", PATH_ACC_COMMENTS_GET, body = Json.write(mapOf("accounts" to accounts)), safeToRepeat = true).body)
    }

    /** Комментарий на номерах (до 200 знаков; пустая строка — удалить). */
    fun setComment(msisdns: List<String>, comment: String) {
        require(comment.length <= MAX_COMMENT) { "Комментарий на номере — не длиннее $MAX_COMMENT знаков" }
        c.request("POST", PATH_COMMENTS_SET, body = Json.write(linkedMapOf("comment" to comment, "msisdns" to msisdns)), safeToRepeat = true)
    }

    /** Комментарий на лицевых счетах (до 100 знаков; пустая строка — удалить). */
    fun setAccountComment(accounts: List<String>, comment: String) {
        require(comment.length <= MAX_ACCOUNT_COMMENT) { "Комментарий на лицевом счёте — не длиннее $MAX_ACCOUNT_COMMENT знаков" }
        c.request("POST", PATH_ACC_COMMENTS_SET, body = Json.write(linkedMapOf("comment" to comment, "accounts" to accounts)), safeToRepeat = true)
    }

    // ---- Обещанный платёж, корпоративный бюджет ----

    fun promised(accountNo: String): PromisedPayment = LineParse.promised(raw(LineSection.PROMISED, accountNo))

    fun setPromised(accountNo: String, amount: Int): EventAck =
        LineParse.eventAck(c.requestOnHost("POST", PATH_PROMISED_SET, Json.write(linkedMapOf("amount" to amount, "accountNumber" to accountNo))))

    fun budgetRules(msisdn: String): List<BudgetRule> = LineParse.budgetRules(raw(LineSection.BUDGET, msisdn))

    fun budgetRulesByAccount(accountNo: String): List<BudgetRule> = LineParse.budgetRules(raw(LineSection.ACCOUNT_BUDGET, accountNo))

    // ---- Заявки ----

    /** Статус заявки по EventID или messageId. */
    fun requestStatus(msisdn: String, id: String): RequestStatus? = LineParse.requestStatus(c.request("POST", PATH_REQUEST_STATUS,
        body = Json.write(mapOf("relatedParty" to listOf(
            mapOf("characteristic" to listOf(linkedMapOf("name" to "MSISDN", "value" to msisdn))),
            mapOf("id" to id),
        ))), safeToRepeat = true).body, id).firstOrNull()

    /** Все заявки по номеру за сутки [day]. */
    fun requests(msisdn: String, day: LocalDate): List<RequestStatus> = LineParse.requestStatus(c.request("POST", PATH_REQUEST_STATUS,
        body = Json.write(linkedMapOf(
            "relatedParty" to listOf(mapOf("characteristic" to listOf(linkedMapOf("name" to "MSISDN", "value" to msisdn)))),
            "validFor" to linkedMapOf("startDateTime" to "${day}T00:00:00", "endDateTime" to "${day.plusDays(1)}T00:00:00"),
        )), safeToRepeat = true).body, null)

    // ---- Документы на email ----

    fun orderCallHistory(msisdns: List<String>, from: LocalDate, to: LocalDate, format: String, email: String): EventAck =
        LineParse.eventAck(c.request("POST", PATH_DOC_CALLS, body = Json.write(linkedMapOf(
            "dateFrom" to MtsDate.dayStart(from), "dateTo" to MtsDate.dayEnd(to),
            "documentFormat" to format, "deliveryAddress" to email, "msisdns" to msisdns,
        ))))

    fun orderClosedBill(msisdn: String, from: LocalDate, to: LocalDate, format: String, email: String): EventAck =
        LineParse.eventAck(c.request("POST", PATH_DOC_BILL, body = Json.write(linkedMapOf(
            "dateFrom" to MtsDate.dayStart(from), "dateTo" to MtsDate.dayEnd(to),
            "deliveryAddress" to email, "documentFormat" to format, "msisdn" to msisdn,
        ))))

    fun orderBalanceReport(msisdns: List<String>, format: String, email: String): EventAck =
        LineParse.eventAck(c.request("POST", PATH_DOC_BALANCE, body = Json.write(linkedMapOf(
            "deliveryAddress" to email, "documentFormat" to format.lowercase(), "msisdns" to msisdns,
        ))))

    companion object {
        const val PATH_BALANCE_ACCOUNT = "/Bills/CheckBalanceByAccount"
        const val PATH_BALANCE_MSISDN = "/Bills/CheckBalanceByMSISDN"
        const val PATH_CHARGES = "/Bills/CheckCharges"
        const val PATH_UNPAID = "/Bills/GetUnpaidAmountByAccountNumber"
        const val PATH_COUNTERS = "/Bills/ValidityInfo"
        const val PATH_TARIFF_FEE = "/Bills/TariffRental"
        const val PATH_PAYMENTS_ACCOUNT = "/Bills/PaymentHistoryByAccount"
        const val PATH_PAYMENTS_MSISDN = "/Bills/PaymentHistoryByMSISDN"
        const val PATH_STATEMENT = "/Bills/BillingStatementByMSISDN"
        const val PATH_PRODUCTS = "/Product/ProductInfo"
        const val PATH_MODIFY = "/Product/ModifyProduct"
        const val PATH_TARIFF = "/Product/BillPlanInfo"
        const val PATH_TARIFF_VALIDATE = "/Product/ChangeBillPlanValidation"
        const val PATH_TARIFF_CHANGE = "/Product/ChangeBillPlan"
        const val PATH_FORWARDING = "/Product/CallForwardingInfo"
        const val PATH_FORWARDING_CHANGE = "/Product/ChangeCallForwarding"
        const val PATH_REQUEST_STATUS = "/Product/CheckRequestStatus"
        const val PATH_LOCATION = "/Service/CurrentSubscriberLocation"
        const val PATH_COMMENTS_GET = "/Service/GetCommentsByMSISDN"
        const val PATH_COMMENTS_SET = "/Service/SetCommentsByMSISDN"
        const val PATH_ACC_COMMENTS_GET = "/Service/GetCommentsByAccount"
        const val PATH_ACC_COMMENTS_SET = "/Service/SetCommentsByAccount"
        const val PATH_SIM_TYPE = "/Resources/SIMType"
        const val PATH_PROMISED_PARAMS = "/Payment/PromisedPaymentParams"
        const val PATH_PROMISED_SET = "/MobileAPI/1/Payment/PromisedPayment"
        const val PATH_BUDGET_MSISDN = "/CorporateBudget/ProvidedRulesByMSISDN"
        const val PATH_BUDGET_ACCOUNT = "/CorporateBudget/ProvidedRulesByAccount"
        const val PATH_DOC_CALLS = "/Documents/CallHistoryByMSISDN"
        const val PATH_DOC_BILL = "/Documents/RegularBillByMSISDN"
        const val PATH_DOC_BALANCE = "/Documents/DetailedBalanceReportByMSISDN"

        const val BULK_CHARGES = 1000
        const val BULK_COMMENTS = 300
        const val MAX_COMMENT = 200
        const val MAX_ACCOUNT_COMMENT = 100

        /** Добровольная блокировка — единственная, которую можно ставить и снимать через API. */
        const val VOLUNTARY_BLOCK = "BL0005"

        val BLOCK_NAMES = linkedMapOf(
            "BL0005" to "Добровольная блокировка",
            "BL0002" to "Частичная блокировка",
            "BL0001" to "Блокировка: аппарат утрачен",
            "BL0003" to "Блокировка: проверка",
            "BL0006" to "Заключительная блокировка",
            "BL0008" to "Первоначальная блокировка",
            "BL0009" to "Принудительная блокировка",
            "BL0011" to "Принудительная блокировка по неоплате счёта",
        )

        /** Понятное название метода — для журнала. */
        fun describe(url: String): String? {
            val path = url.substringBefore('?')
            return when {
                path.endsWith(PATH_BALANCE_ACCOUNT) -> "Баланс лицевого счёта"
                path.endsWith(PATH_BALANCE_MSISDN) -> "Баланс номера"
                path.endsWith(PATH_CHARGES) -> "Начисления за месяц"
                path.endsWith(PATH_UNPAID) -> "Неоплаченные счета"
                path.endsWith(PATH_COUNTERS) -> "Остатки пакетов"
                path.endsWith(PATH_TARIFF_FEE) -> "Плата по тарифу"
                path.endsWith(PATH_PAYMENTS_ACCOUNT) || path.endsWith(PATH_PAYMENTS_MSISDN) -> "История платежей"
                path.endsWith(PATH_STATEMENT) -> "Детализация"
                path.endsWith(PATH_PRODUCTS) -> when {
                    "AvailibleTariffPlann" in url -> "Доступные тарифы"
                    "=block" in url && "actionAllowed=create" in url -> "Доступные блокировки"
                    "=block" in url -> "Блокировки"
                    "actionAllowed=create" in url -> "Доступные услуги"
                    else -> "Подключённые услуги"
                }
                path.endsWith(PATH_MODIFY) -> "Изменение услуги или блокировки"
                path.endsWith(PATH_TARIFF) -> "Тариф"
                path.endsWith(PATH_TARIFF_VALIDATE) -> "Проверка смены тарифа"
                path.endsWith(PATH_TARIFF_CHANGE) -> "Смена тарифа"
                path.endsWith(PATH_FORWARDING) -> "Переадресация"
                path.endsWith(PATH_FORWARDING_CHANGE) -> "Изменение переадресации"
                path.endsWith(PATH_REQUEST_STATUS) -> "Статус заявки"
                path.endsWith(PATH_LOCATION) -> "Роуминг"
                path.endsWith(PATH_COMMENTS_GET) || path.endsWith(PATH_ACC_COMMENTS_GET) -> "Комментарии"
                path.endsWith(PATH_COMMENTS_SET) || path.endsWith(PATH_ACC_COMMENTS_SET) -> "Изменение комментария"
                path.endsWith(PATH_SIM_TYPE) -> "Тип SIM-карты"
                path.endsWith(PATH_PROMISED_PARAMS) -> "Обещанный платёж: условия"
                path.endsWith(PATH_PROMISED_SET) -> "Обещанный платёж"
                path.endsWith(PATH_BUDGET_MSISDN) || path.endsWith(PATH_BUDGET_ACCOUNT) -> "Корпоративный бюджет"
                path.endsWith(PATH_DOC_CALLS) -> "Заказ детализации"
                path.endsWith(PATH_DOC_BILL) -> "Заказ счёта"
                path.endsWith(PATH_DOC_BALANCE) -> "Заказ отчёта по балансу"
                else -> null
            }
        }
    }
}
