package ru.mycheckin.passport.mts

import kotlin.math.abs

/*
 * Сведения о номерах для фонового обновления и экрана «Номера МТС».
 *
 * Каждый раздел (баланс, услуги, блокировки…) хранится как тело ответа МТС и разбирается
 * [LineParse] при чтении: так при уточнении разбора не нужно заново запрашивать МТС,
 * а у каждого раздела свой срок свежести — часто меняющееся (баланс, блокировки, роуминг)
 * обновляется чаще, редко меняющееся (тип SIM, тариф) — реже, и лимит вызовов не тратится зря.
 */

/** Как запрашивается раздел: по номеру, пачкой номеров, по лицевому счёту или пачкой счетов. */
enum class LineScope { NUMBER, NUMBERS_BULK, ACCOUNT, ACCOUNTS_BULK }

enum class LineSection(val title: String, val scope: LineScope, val ttlMinutes: Long, val bulkSize: Int = 1) {
    BALANCE("Баланс лицевого счёта", LineScope.ACCOUNT, 60),
    UNPAID("Неоплаченные счета", LineScope.ACCOUNT, 12 * 60),
    PROMISED("Обещанный платёж", LineScope.ACCOUNT, 12 * 60),
    ACCOUNT_BUDGET("Корпоративный бюджет счёта", LineScope.ACCOUNT, 24 * 60),
    ACCOUNT_COMMENT("Комментарий лицевого счёта", LineScope.ACCOUNTS_BULK, 6 * 60, LineApi.BULK_COMMENTS),
    CHARGES("Начисления за месяц", LineScope.NUMBERS_BULK, 3 * 60, LineApi.BULK_CHARGES),
    COMMENT("Комментарий номера", LineScope.NUMBERS_BULK, 6 * 60, LineApi.BULK_COMMENTS),
    BLOCKS("Блокировки", LineScope.NUMBER, 60),
    LOCATION("Роуминг", LineScope.NUMBER, 60),
    COUNTERS("Остатки пакетов", LineScope.NUMBER, 3 * 60),
    SERVICES("Услуги", LineScope.NUMBER, 12 * 60),
    TARIFF("Тариф", LineScope.NUMBER, 24 * 60),
    TARIFF_FEE("Плата по тарифу", LineScope.NUMBER, 24 * 60),
    FORWARDING("Переадресация", LineScope.NUMBER, 24 * 60),
    BUDGET("Корпоративный бюджет", LineScope.NUMBER, 24 * 60),
    SIM_TYPE("Тип SIM-карты", LineScope.NUMBER, 7 * 24 * 60);

    val byAccount: Boolean get() = scope == LineScope.ACCOUNT || scope == LineScope.ACCOUNTS_BULK
    val bulk: Boolean get() = scope == LineScope.NUMBERS_BULK || scope == LineScope.ACCOUNTS_BULK
    val ttlMillis: Long get() = ttlMinutes * 60_000

    companion object {
        fun of(name: String?): LineSection? = entries.firstOrNull { it.name == name }

        /** Что обновить после изменения через API (подключили услугу — перечитать услуги и плату). */
        fun affectedBy(kind: LineChange): Set<LineSection> = when (kind) {
            LineChange.SERVICE -> setOf(SERVICES, TARIFF_FEE, CHARGES)
            LineChange.BLOCK -> setOf(BLOCKS, SERVICES)
            LineChange.TARIFF -> setOf(TARIFF, TARIFF_FEE, SERVICES, COUNTERS)
            LineChange.FORWARDING -> setOf(FORWARDING)
            LineChange.PROMISED -> setOf(BALANCE, PROMISED)
            LineChange.COMMENT -> setOf(COMMENT, ACCOUNT_COMMENT)
            LineChange.DOCUMENT -> emptySet()
        }
    }
}

/** Вид изменения, отправленного в МТС (для заявок и для того, что перечитать после выполнения). */
enum class LineChange(val title: String) {
    SERVICE("Услуга"), BLOCK("Блокировка"), TARIFF("Тариф"), FORWARDING("Переадресация"),
    PROMISED("Обещанный платёж"), COMMENT("Комментарий"), DOCUMENT("Документ на email");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } }
}

/**
 * Сохранённый раздел: тело ответа МТС (или null, если запрос не удался) и когда он получен.
 * [unavailable] — МТС ответил, что раздел для номера недоступен (не подключено, нет прав): это не сбой.
 */
data class StoredSection(val body: String?, val fetchedAt: Long, val error: String? = null, val unavailable: Boolean = false)

// ---------------------------------------------------------------------------------------------
// Сводка по номеру
// ---------------------------------------------------------------------------------------------

/** Состояние баланса лицевого счёта. */
enum class BalanceState(val title: String) { UNKNOWN("Нет сведений"), OK("Положительный"), LOW("Близко к порогу блокировки"), NEGATIVE("Отрицательный") }

/**
 * Всё, что известно о номере из МТС. null у раздела — ещё не загружался (или МТС вернул ошибку,
 * она в [errors]).
 */
data class LineSnapshot(
    val msisdn: String,
    val accountNo: String,
    val balance: Balance? = null,
    val unpaid: Double? = null,
    val promised: PromisedPayment? = null,
    val accountBudget: List<BudgetRule>? = null,
    val accountComment: String? = null,
    val charges: Double? = null,
    val comment: String? = null,
    val blocks: List<ProductItem>? = null,
    val location: Location? = null,
    val counters: List<Counter>? = null,
    val services: List<ProductItem>? = null,
    val tariff: Tariff? = null,
    val tariffFee: TariffFee? = null,
    val forwarding: List<Forwarding>? = null,
    val budget: List<BudgetRule>? = null,
    val simType: String? = null,
    val fetchedAt: Map<LineSection, Long> = emptyMap(),
    val errors: Map<LineSection, String> = emptyMap(),
    /** Разделы, недоступные для номера, с ответом МТС. */
    val unavailable: Map<LineSection, String> = emptyMap(),
) {
    val loaded: Boolean get() = fetchedAt.isNotEmpty()
    val updatedAt: Long? get() = fetchedAt.values.maxOrNull()
    val oldestAt: Long? get() = fetchedAt.values.minOrNull()

    val blockCodes: Set<String> get() = blocks.orEmpty().map { it.code.uppercase() }.toSet()
    val isBlocked: Boolean get() = blocks.orEmpty().isNotEmpty()
    val voluntaryBlock: Boolean get() = LineApi.VOLUNTARY_BLOCK in blockCodes
    val abroad: Boolean get() = location?.abroad == true
    val hasForwarding: Boolean get() = forwarding.orEmpty().isNotEmpty()
    val isEsim: Boolean get() = simType?.contains("esim", ignoreCase = true) == true

    /** Порог блокировки, если он настоящий: МТС пишет −99 999 999 там, где порога фактически нет. */
    val blockThreshold: Double? get() = balance?.creditLimit?.takeIf { abs(it) < NO_LIMIT }

    /** Сколько можно потратить до блокировки (баланс минус порог). */
    val available: Double? get() {
        val amount = balance?.amount ?: return null
        return amount - (blockThreshold ?: 0.0)
    }

    fun balanceState(lowMargin: Double = DEFAULT_LOW_MARGIN): BalanceState {
        val amount = balance?.amount ?: return BalanceState.UNKNOWN
        val left = available ?: amount
        return when {
            left < lowMargin -> if (amount < 0 && left < 0) BalanceState.NEGATIVE else BalanceState.LOW
            amount < 0 -> BalanceState.NEGATIVE
            else -> BalanceState.OK
        }
    }

    /** Ежемесячная плата: тариф и платные услуги (суточные — × 30). */
    val monthlyFee: Double? get() {
        val tariff = tariffFee?.current?.let { perMonth(it, tariffFee.currentUnit) }
        val extra = services.orEmpty().mapNotNull { s -> s.fee?.takeIf { it > 0 }?.let { perMonth(it, s.feeUnit) } }
        if (tariff == null && extra.isEmpty()) return null
        return (tariff ?: 0.0) + extra.sum()
    }

    /** Пакеты минут, SMS и интернета (без денежных и накопительных счётчиков). */
    val packages: List<Counter> get() = counters.orEmpty().filter {
        it.kind in setOf(Counter.Kind.INTERNET, Counter.Kind.MINUTES, Counter.Kind.SMS) && it.share != null
    }

    /** Пакет с наименьшим остатком (доля 0..1). */
    val lowestPackage: Counter? get() = packages.minByOrNull { it.share ?: 1.0 }

    companion object {
        const val NO_LIMIT = 10_000_000.0
        const val DEFAULT_LOW_MARGIN = 300.0

        private fun perMonth(v: Double, unit: String?) = if (unit.equals("DAY", true)) v * 30 else v

        /**
         * Собрать сводку из сохранённых разделов номера ([number]) и его лицевого счёта ([account]).
         * Ошибка разбора одного раздела не мешает остальным.
         */
        fun build(msisdn: String, accountNo: String, number: Map<LineSection, StoredSection>, account: Map<LineSection, StoredSection>): LineSnapshot {
            val all = account.filterKeys { it.byAccount } + number.filterKeys { !it.byAccount }
            val errors = LinkedHashMap<LineSection, String>()
            val unavailable = LinkedHashMap<LineSection, String>()
            fun <T> parse(s: LineSection, f: (String) -> T): T? {
                val st = all[s] ?: return null
                st.error?.let { if (st.unavailable) unavailable[s] = it else errors[s] = it }
                val body = st.body ?: return null
                return runCatching { f(body) }.onFailure { errors[s] = "Не удалось разобрать ответ МТС: ${it.message}" }.getOrNull()
            }
            return LineSnapshot(
                msisdn = msisdn,
                accountNo = accountNo,
                balance = parse(LineSection.BALANCE) { LineParse.balances(it).firstOrNull() },
                unpaid = parse(LineSection.UNPAID) { LineParse.unpaid(it) },
                promised = parse(LineSection.PROMISED) { LineParse.promised(it) },
                accountBudget = parse(LineSection.ACCOUNT_BUDGET) { LineParse.budgetRules(it) },
                accountComment = parse(LineSection.ACCOUNT_COMMENT) { b -> LineParse.comments(b).let { it[accountNo] ?: it.values.firstOrNull() }.orEmpty() },
                charges = parse(LineSection.CHARGES) { b -> LineParse.charges(b).let { m -> if (msisdn in m) m[msisdn] else m.values.firstOrNull() } },
                comment = parse(LineSection.COMMENT) { b -> LineParse.comments(b).let { it[msisdn] ?: it.values.firstOrNull() }.orEmpty() },
                blocks = parse(LineSection.BLOCKS) { LineParse.products(it) },
                location = parse(LineSection.LOCATION) { LineParse.location(it) },
                counters = parse(LineSection.COUNTERS) { LineParse.counters(it) },
                services = parse(LineSection.SERVICES) { LineParse.products(it) },
                tariff = parse(LineSection.TARIFF) { LineParse.tariff(it) },
                tariffFee = parse(LineSection.TARIFF_FEE) { LineParse.tariffFee(it) },
                forwarding = parse(LineSection.FORWARDING) { LineParse.forwarding(it) },
                budget = parse(LineSection.BUDGET) { LineParse.budgetRules(it) },
                simType = parse(LineSection.SIM_TYPE) { LineParse.simType(it) },
                fetchedAt = all.mapValues { it.value.fetchedAt },
                errors = errors,
                unavailable = unavailable,
            )
        }
    }
}

/** Строка списка «Номера МТС»: сведения МТС и то, что известно из учёта SIM. */
data class LineRow(
    val msisdn: String,
    val owner: String,
    val contractId: String,
    val contractTitle: String,
    val accountNo: String,
    val iccid: String,
    val region: String,
    val inMts: Boolean,
    val snap: LineSnapshot,
)

// ---------------------------------------------------------------------------------------------
// Фильтры
// ---------------------------------------------------------------------------------------------

enum class Tri { ANY, YES, NO }

enum class BalanceRule(val title: String) {
    ANY("Любой"), NEGATIVE("Отрицательный"), LOW("Близко к порогу или минус"), BELOW("Меньше суммы"), POSITIVE("Положительный")
}

enum class BlockRule(val title: String) { ANY("Любые"), NONE("Без блокировок"), SOME("Есть блокировка") }

enum class LineSort(val title: String) {
    PHONE("По номеру"), OWNER("По владельцу"), BALANCE("Сначала с меньшим балансом"),
    CHARGES("Сначала с большими начислениями"), FEE("Сначала с большей абонплатой"),
    PACKAGE("Сначала с меньшим остатком пакета"), UPDATED("Сначала давно не обновлявшиеся")
}

/**
 * Настраиваемый фильтр списка номеров. Условия складываются через «И»;
 * пустое множество или ANY — условие не задано.
 */
data class LineFilter(
    val name: String = "",
    val query: String = "",
    val contracts: Set<String> = emptySet(),
    val accounts: Set<String> = emptySet(),
    val balance: BalanceRule = BalanceRule.ANY,
    val balanceBelow: Double? = null,
    val lowMargin: Double = LineSnapshot.DEFAULT_LOW_MARGIN,
    val blocks: BlockRule = BlockRule.ANY,
    val blockCodes: Set<String> = emptySet(),
    val serviceText: String = "",
    val serviceMode: Tri = Tri.YES,
    val tariffs: Set<String> = emptySet(),
    val roaming: Tri = Tri.ANY,
    val forwarding: Tri = Tri.ANY,
    val owner: Tri = Tri.ANY,
    val esim: Tri = Tri.ANY,
    val inMts: Tri = Tri.ANY,
    val chargesAbove: Double? = null,
    val feeAbove: Double? = null,
    val packageBelowPercent: Int? = null,
    val withComment: Tri = Tri.ANY,
    val staleHours: Int? = null,
    val withErrors: Boolean = false,
    val sort: LineSort = LineSort.PHONE,
) {
    /** Сколько условий задано (без поиска и сортировки) — для значка на кнопке «Фильтры». */
    val activeCount: Int get() = listOf(
        contracts.isNotEmpty(), accounts.isNotEmpty(), balance != BalanceRule.ANY, blocks != BlockRule.ANY || blockCodes.isNotEmpty(),
        serviceText.isNotBlank(), tariffs.isNotEmpty(), roaming != Tri.ANY, forwarding != Tri.ANY, owner != Tri.ANY,
        esim != Tri.ANY, inMts != Tri.ANY, chargesAbove != null, feeAbove != null, packageBelowPercent != null,
        withComment != Tri.ANY, staleHours != null, withErrors,
    ).count { it }

    val isEmpty: Boolean get() = activeCount == 0 && query.isBlank()

    fun matches(r: LineRow, now: Long): Boolean {
        val s = r.snap
        if (contracts.isNotEmpty() && r.contractId !in contracts) return false
        if (accounts.isNotEmpty() && r.accountNo !in accounts) return false
        val amount = s.balance?.amount
        when (balance) {
            BalanceRule.ANY -> Unit
            BalanceRule.NEGATIVE -> if (amount == null || amount >= 0) return false
            BalanceRule.LOW -> if (s.balanceState(lowMargin) !in setOf(BalanceState.LOW, BalanceState.NEGATIVE)) return false
            BalanceRule.BELOW -> if (amount == null || amount >= (balanceBelow ?: 0.0)) return false
            BalanceRule.POSITIVE -> if (amount == null || amount < 0) return false
        }
        when (blocks) {
            BlockRule.ANY -> if (blockCodes.isNotEmpty() && s.blockCodes.none { it in blockCodes }) return false
            BlockRule.NONE -> if (s.blocks == null || s.isBlocked) return false
            BlockRule.SOME -> {
                if (!s.isBlocked) return false
                if (blockCodes.isNotEmpty() && s.blockCodes.none { it in blockCodes }) return false
            }
        }
        if (serviceText.isNotBlank()) {
            val words = serviceText.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val has = s.services.orEmpty().any { p ->
                val t = (p.name + " " + p.code).lowercase()
                words.all { it in t }
            }
            if (s.services == null) return false
            if (serviceMode == Tri.YES && !has) return false
            if (serviceMode == Tri.NO && has) return false
        }
        if (tariffs.isNotEmpty() && s.tariff?.name !in tariffs) return false
        if (!tri(roaming, s.location?.let { s.abroad })) return false
        if (!tri(forwarding, s.forwarding?.let { s.hasForwarding })) return false
        if (!tri(owner, r.owner.isNotBlank())) return false
        if (!tri(esim, s.simType?.let { s.isEsim })) return false
        if (!tri(inMts, r.inMts)) return false
        if (!tri(withComment, s.comment?.let { it.isNotBlank() })) return false
        chargesAbove?.let { min -> if ((s.charges ?: return false) < min) return false }
        feeAbove?.let { min -> if ((s.monthlyFee ?: return false) < min) return false }
        packageBelowPercent?.let { p -> if (((s.lowestPackage?.share) ?: return false) * 100 >= p) return false }
        staleHours?.let { h -> val old = s.oldestAt; if (old != null && now - old < h * 3_600_000L) return false }
        if (withErrors && s.errors.isEmpty()) return false
        return LineSearch.matches(r, query)
    }

    /** Отфильтровать и отсортировать. */
    fun apply(rows: List<LineRow>, now: Long): List<LineRow> = sorted(rows.filter { matches(it, now) })

    fun sorted(rows: List<LineRow>): List<LineRow> = when (sort) {
        LineSort.PHONE -> rows.sortedBy { it.msisdn }
        LineSort.OWNER -> rows.sortedWith(compareBy<LineRow> { it.owner.isBlank() }.thenBy { it.owner.lowercase() }.thenBy { it.msisdn })
        LineSort.BALANCE -> rows.sortedWith(compareBy<LineRow> { it.snap.available == null }.thenBy { it.snap.available ?: 0.0 })
        LineSort.CHARGES -> rows.sortedWith(compareBy<LineRow> { it.snap.charges == null }.thenByDescending { it.snap.charges ?: 0.0 })
        LineSort.FEE -> rows.sortedWith(compareBy<LineRow> { it.snap.monthlyFee == null }.thenByDescending { it.snap.monthlyFee ?: 0.0 })
        LineSort.PACKAGE -> rows.sortedWith(compareBy<LineRow> { it.snap.lowestPackage == null }.thenBy { it.snap.lowestPackage?.share ?: 1.0 })
        LineSort.UPDATED -> rows.sortedBy { it.snap.oldestAt ?: 0L }
    }

    private fun tri(t: Tri, v: Boolean?): Boolean = when (t) {
        Tri.ANY -> true
        Tri.YES -> v == true
        Tri.NO -> v == false
    }

    fun toJson(): String = Json.write(linkedMapOf(
        "name" to name, "query" to query, "contracts" to contracts.toList(), "accounts" to accounts.toList(),
        "balance" to balance.name, "balanceBelow" to balanceBelow, "lowMargin" to lowMargin,
        "blocks" to blocks.name, "blockCodes" to blockCodes.toList(),
        "serviceText" to serviceText, "serviceMode" to serviceMode.name, "tariffs" to tariffs.toList(),
        "roaming" to roaming.name, "forwarding" to forwarding.name, "owner" to owner.name, "esim" to esim.name,
        "inMts" to inMts.name, "chargesAbove" to chargesAbove, "feeAbove" to feeAbove,
        "packageBelowPercent" to packageBelowPercent, "withComment" to withComment.name, "staleHours" to staleHours,
        "withErrors" to withErrors, "sort" to sort.name,
    ))

    companion object {
        private inline fun <reified E : Enum<E>> enumOf(v: Any?, def: E): E =
            v.str()?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: def

        private fun set(v: Any?): Set<String> = v.list().mapNotNull { it.str() }.toSet()

        fun fromJson(text: String): LineFilter? = (Json.parseOrNull(text) as? Map<*, *>)?.let { fromMap(it) }

        fun fromMap(j: Any?): LineFilter = LineFilter(
            name = j.field("name").str().orEmpty(),
            query = j.field("query").str().orEmpty(),
            contracts = set(j.field("contracts")),
            accounts = set(j.field("accounts")),
            balance = enumOf(j.field("balance"), BalanceRule.ANY),
            balanceBelow = j.field("balanceBelow").num(),
            lowMargin = j.field("lowMargin").num() ?: LineSnapshot.DEFAULT_LOW_MARGIN,
            blocks = enumOf(j.field("blocks"), BlockRule.ANY),
            blockCodes = set(j.field("blockCodes")),
            serviceText = j.field("serviceText").str().orEmpty(),
            serviceMode = enumOf(j.field("serviceMode"), Tri.YES),
            tariffs = set(j.field("tariffs")),
            roaming = enumOf(j.field("roaming"), Tri.ANY),
            forwarding = enumOf(j.field("forwarding"), Tri.ANY),
            owner = enumOf(j.field("owner"), Tri.ANY),
            esim = enumOf(j.field("esim"), Tri.ANY),
            inMts = enumOf(j.field("inMts"), Tri.ANY),
            chargesAbove = j.field("chargesAbove").num(),
            feeAbove = j.field("feeAbove").num(),
            packageBelowPercent = j.field("packageBelowPercent").num()?.toInt(),
            withComment = enumOf(j.field("withComment"), Tri.ANY),
            staleHours = j.field("staleHours").num()?.toInt(),
            withErrors = j.field("withErrors").str() == "true",
            sort = enumOf(j.field("sort"), LineSort.PHONE),
        )

        fun listToJson(list: List<LineFilter>): String = "[" + list.joinToString(",") { it.toJson() } + "]"
        fun listFromJson(text: String?): List<LineFilter> = Json.parseOrNull(text.orEmpty()).list().map { fromMap(it) }

        /** Готовые наборы для быстрых кнопок. */
        val PRESETS = listOf(
            LineFilter(name = "Заблокированные", blocks = BlockRule.SOME),
            LineFilter(name = "Баланс у порога", balance = BalanceRule.LOW, sort = LineSort.BALANCE),
            LineFilter(name = "В роуминге", roaming = Tri.YES),
            LineFilter(name = "Мало пакета", packageBelowPercent = 20, sort = LineSort.PACKAGE),
            LineFilter(name = "Переадресация", forwarding = Tri.YES),
            LineFilter(name = "Без ФИО", owner = Tri.NO),
            LineFilter(name = "Большие начисления", chargesAbove = 1000.0, sort = LineSort.CHARGES),
        )
    }
}

/** Поиск по списку номеров: телефон в любом виде, владелец, контракт, тариф, услуги, комментарий, ICCID, счёт. */
object LineSearch {
    fun matches(r: LineRow, query: String): Boolean {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val s = r.snap
        val text = buildString {
            append(r.owner).append(' ').append(r.contractTitle).append(' ').append(r.region).append(' ')
            append(s.tariff?.name.orEmpty()).append(' ').append(s.comment.orEmpty()).append(' ').append(s.accountComment.orEmpty()).append(' ')
            append(s.location?.countryName.orEmpty()).append(' ')
            s.services.orEmpty().forEach { append(it.name).append(' ').append(it.code).append(' ') }
            s.blocks.orEmpty().forEach { append(it.name).append(' ').append(it.code).append(' ') }
        }.lowercase()
        val digits = listOf(r.msisdn, r.iccid, r.accountNo, r.contractId)
        return words.all { w ->
            val d = w.filter { it.isDigit() }
            if (d.isNotEmpty() && d.length == w.count { it.isLetterOrDigit() }) {
                val alt = if (d.length == 11 && d[0] == '8') "7" + d.drop(1) else d
                digits.any { it.contains(d) || it.contains(alt) } || w in text
            } else w in text
        }
    }
}

// ---------------------------------------------------------------------------------------------
// План обновления
// ---------------------------------------------------------------------------------------------

object LinePlanner {
    /** Одно задание: раздел и номера или лицевые счета (у пакетных разделов — до [LineSection.bulkSize]). */
    data class Job(val section: LineSection, val keys: List<String>)

    /**
     * Что запросить у МТС, чтобы сведения были свежими. [numbers] — номер → лицевой счёт.
     * [fetchedAt] — когда раздел по номеру или счёту получен в последний раз (null — ни разу).
     * [ttlFactor] растягивает сроки свежести (2.0 — обновлять вдвое реже, экономнее).
     *
     * Порядок: лицевые счета (баланс важнее всего), затем пакетные запросы, затем номера —
     * сначала те, у которых сведения самые старые, все разделы номера подряд, чтобы карточка
     * дополнялась целиком. Прерванное обновление в следующий раз продолжится с того же места.
     */
    fun plan(
        numbers: Map<String, String>,
        sections: Set<LineSection>,
        fetchedAt: (LineSection, String) -> Long?,
        now: Long,
        force: Boolean = false,
        ttlFactor: Double = 1.0,
    ): List<Job> {
        fun stale(s: LineSection, key: String): Boolean {
            if (force) return true
            val at = fetchedAt(s, key) ?: return true
            return now - at >= (s.ttlMillis * ttlFactor).toLong()
        }
        val ordered = LineSection.entries.filter { it in sections }
        val accounts = numbers.values.filter { it.isNotBlank() }.distinct().sorted()
        val msisdns = numbers.keys.sorted()
        val jobs = mutableListOf<Job>()

        for (s in ordered.filter { it.scope == LineScope.ACCOUNT }) {
            accounts.filter { stale(s, it) }.forEach { jobs += Job(s, listOf(it)) }
        }
        for (s in ordered.filter { it.bulk }) {
            val keys = (if (s.byAccount) accounts else msisdns).filter { stale(s, it) }
            keys.chunked(s.bulkSize).forEach { jobs += Job(s, it) }
        }
        val perNumber = ordered.filter { it.scope == LineScope.NUMBER }
        val byAge = msisdns.map { m -> m to perNumber.filter { stale(it, m) } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { (m, ss) -> ss.minOf { fetchedAt(it, m) ?: 0L } }
        for ((m, ss) in byAge) ss.forEach { jobs += Job(it, listOf(m)) }
        return jobs
    }

    /** Сколько вызовов API займёт полное обновление (для подсказки на экране). */
    fun fullCost(numbers: Int, accounts: Int, sections: Set<LineSection>): Int = sections.sumOf { s ->
        when (s.scope) {
            LineScope.NUMBER -> numbers
            LineScope.ACCOUNT -> accounts
            LineScope.NUMBERS_BULK -> (numbers + s.bulkSize - 1) / s.bulkSize
            LineScope.ACCOUNTS_BULK -> (accounts + s.bulkSize - 1) / s.bulkSize
        }
    }
}
