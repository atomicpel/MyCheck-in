package ru.mycheckin.passport.mts

import java.time.LocalDateTime

/**
 * Готовая сводка по номеру ([LineSnapshot]) в компактном JSON — для быстрого списка.
 *
 * Разбирать все сохранённые ответы МТС при каждом открытии списка дорого: на 1000+ номеров это
 * ~16 000 ответов, несколько секунд и десятки мегабайт. Поэтому сводка номера считается один раз,
 * когда его сведения изменились, сохраняется и читается списком как есть (≈1 КБ на номер).
 */
object LineDigest {
    private const val VERSION = 1

    private fun d(t: LocalDateTime?): String? = t?.toString()
    private fun d(v: Any?): LocalDateTime? = v.str()?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    private fun i(v: Any?): Int? = v.num()?.toInt()
    private fun b(v: Any?): Boolean? = v.str()?.toBooleanStrictOrNull()

    private fun product(p: ProductItem) = linkedMapOf(
        "c" to p.code, "n" to p.name, "s" to p.status, "f" to d(p.since), "t" to d(p.until), "p" to p.fee, "u" to p.feeUnit,
        "a" to p.activated, "b" to p.blocked, "x" to p.actions.ifEmpty { null },
    )
    private fun product(j: Any?) = ProductItem(
        code = j.field("c").str().orEmpty(), name = j.field("n").str().orEmpty(), status = j.field("s").str(),
        since = d(j.field("f")), until = d(j.field("t")), fee = j.field("p").num(), feeUnit = j.field("u").str(),
        activated = b(j.field("a")), blocked = b(j.field("b")), actions = j.field("x").list().mapNotNull { it.str() },
    )

    private fun rule(r: BudgetRule) = linkedMapOf("c" to r.code, "t" to r.title, "s" to r.subTitle, "l" to r.limit, "r" to r.remainder, "f" to d(r.from), "e" to d(r.to))
    private fun rule(j: Any?) = BudgetRule(j.field("c").str(), j.field("t").str().orEmpty(), j.field("s").str(), j.field("l").str(), j.field("r").str(), d(j.field("f")), d(j.field("e")))

    private fun <K : Enum<K>> times(m: Map<K, Long>) = m.mapKeys { it.key.name }.ifEmpty { null }
    private fun texts(m: Map<LineSection, String>) = m.mapKeys { it.key.name }.ifEmpty { null }

    private fun sectionMap(j: Any?): Map<LineSection, Any?> =
        (j as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> LineSection.of(k as? String)?.let { it to v } }.toMap()

    fun encode(s: LineSnapshot): String = Json.write(linkedMapOf(
        "v" to VERSION.toLong(),
        "m" to s.msisdn, "acc" to s.accountNo,
        "bal" to s.balance?.let { linkedMapOf("a" to it.accountNo, "x" to it.amount, "l" to it.creditLimit, "c" to it.currency, "t" to d(it.validTo)) },
        "unp" to s.unpaid,
        "prm" to s.promised?.let { p -> linkedMapOf(
            "a" to p.amount, "t" to d(p.dateTo), "mq" to p.maxQuantity?.toLong(), "q" to p.quantity?.toLong(),
            "p" to p.prices.map { linkedMapOf("f" to it.from, "t" to it.to, "p" to it.price) },
            "n" to p.possibleWithNegative, "mb" to p.minBalance, "m" to p.msisdn,
        ) },
        "abud" to s.accountBudget?.map { rule(it) },
        "acom" to s.accountComment,
        "chg" to s.charges,
        "com" to s.comment,
        "blk" to s.blocks?.map { product(it) },
        "loc" to s.location?.let { linkedMapOf("n" to it.countryName, "a" to it.alpha2, "t" to d(it.date)) },
        "cnt" to s.counters?.map { c -> linkedMapOf("t" to c.title, "k" to c.kind.name, "l" to c.left, "o" to c.total, "u" to c.used, "e" to d(c.until)) },
        "srv" to s.services?.map { product(it) },
        "tar" to s.tariff?.let { linkedMapOf("c" to it.code, "n" to it.name, "r" to it.regionalName, "f" to d(it.since)) },
        "fee" to s.tariffFee?.let { linkedMapOf("c" to it.current, "cu" to it.currentUnit, "n" to it.next, "nu" to it.nextUnit, "d" to it.discount) },
        "fwd" to s.forwarding?.map { linkedMapOf("t" to it.type, "a" to it.address, "s" to it.timer?.toLong(), "n" to it.numType) },
        "bud" to s.budget?.map { rule(it) },
        "sim" to s.simType,
        "at" to times(s.fetchedAt),
        "err" to texts(s.errors),
        "na" to texts(s.unavailable),
    ))

    /** null — сводка от другой версии или испорчена: посчитать заново из ответов МТС. */
    fun decode(text: String): LineSnapshot? {
        val j = Json.parseOrNull(text) as? Map<*, *> ?: return null
        if (i(j.field("v")) != VERSION) return null
        val msisdn = j.field("m").str() ?: return null
        return LineSnapshot(
            msisdn = msisdn,
            accountNo = j.field("acc").str().orEmpty(),
            balance = j.field("bal")?.let { Balance(it.field("a").str().orEmpty(), it.field("x").num(), it.field("l").num(), it.field("c").str(), d(it.field("t"))) },
            unpaid = j.field("unp").num(),
            promised = j.field("prm")?.let { p -> PromisedPayment(
                amount = p.field("a").num(), dateTo = d(p.field("t")), maxQuantity = i(p.field("mq")), quantity = i(p.field("q")),
                prices = p.field("p").list().map { PromisedPayment.Price(it.field("f").num(), it.field("t").num(), it.field("p").num()) },
                possibleWithNegative = b(p.field("n")), minBalance = p.field("mb").num(), msisdn = p.field("m").str(),
            ) },
            accountBudget = j.field("abud")?.list()?.map { rule(it) },
            accountComment = j.field("acom").str(),
            charges = j.field("chg").num(),
            comment = j.field("com").str(),
            blocks = j.field("blk")?.list()?.map { product(it) },
            location = j.field("loc")?.let { Location(it.field("n").str(), it.field("a").str(), d(it.field("t"))) },
            counters = j.field("cnt")?.list()?.map { c ->
                Counter(c.field("t").str().orEmpty(), runCatching { Counter.Kind.valueOf(c.field("k").str().orEmpty()) }.getOrDefault(Counter.Kind.OTHER),
                    c.field("l").num(), c.field("o").num(), c.field("u").num(), d(c.field("e")))
            },
            services = j.field("srv")?.list()?.map { product(it) },
            tariff = j.field("tar")?.let { Tariff(it.field("c").str().orEmpty(), it.field("n").str().orEmpty(), it.field("r").str(), d(it.field("f"))) },
            tariffFee = j.field("fee")?.let { TariffFee(it.field("c").num(), it.field("cu").str(), it.field("n").num(), it.field("nu").str(), it.field("d").num()) },
            forwarding = j.field("fwd")?.list()?.map { Forwarding(it.field("t").str().orEmpty(), it.field("a").str().orEmpty(), i(it.field("s")), it.field("n").str()) },
            budget = j.field("bud")?.list()?.map { rule(it) },
            simType = j.field("sim").str(),
            fetchedAt = sectionMap(j.field("at")).mapNotNull { (k, v) -> v.num()?.toLong()?.let { k to it } }.toMap(),
            errors = sectionMap(j.field("err")).mapNotNull { (k, v) -> v.str()?.let { k to it } }.toMap(),
            unavailable = sectionMap(j.field("na")).mapNotNull { (k, v) -> v.str()?.let { k to it } }.toMap(),
        )
    }
}
