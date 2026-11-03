package ru.mycheckin.passport.mts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сводка по номеру, фильтры и план фонового обновления (значения вымышленные). */
class LineDataTest {

    private val now = 1_800_000_000_000L
    private fun st(body: String?, ago: Long = 0, error: String? = null) = StoredSection(body, now - ago, error)

    private val balanceBody = """[{"accountNo":"123456789012","customerAccountBalance":[{"remainedAmount":{"amount":-1900.0},
        "customerAccount":{"creditLimit":-2000.0}}]}]"""
    private val blocksBody = """[{"externalID":"BL0005","name":"Добровольная блокировка","status":"ACTIVE",
        "validFor":{"startDateTime":"2026-10-01T10:00:00","endDateTime":"0001-01-01T00:00:00"}}]"""
    private val servicesBody = """[{"externalID":"PE0526","name":"БИТ за границей","productOfferingPrice":[{"name":"Fee","unitOfMeasure":"DAY",
        "price":{"taxIncludedAmount":10.0}}]},{"externalID":"S1","name":"Кто звонил"}]"""
    private val feeBody = """{"productOfferingPrice":[{"unitOfMeasure":"MONTH","name":"CurrentPrimaryFee","price":{"taxIncludedAmount":600.0}}]}"""
    private val abroadBody = """{"Locations":[{"msisdn":"79161234567","countryId":"112"}],"references":{"Countries":[{"id":"112","alpha2":"TR","name":"Турция"}]}}"""
    private val countersBody = """[{"customerAccount":[{"productRelationship":[
        {"product":{"productPrice":[{"unitOfMeasure":"ITEM"}],"productSpecification":{"name":"SMS","productSpecCharacteristic":[{"prodSpecCharacteristicValue":[
          {"valueType":"CurrentValue","value":"10"},{"valueType":"CurrentInitialValue","value":"100"}]}]}}}]}]}]"""

    private fun snapshot(extraNumber: Map<LineSection, StoredSection> = emptyMap()) = LineSnapshot.build(
        "79161234567", "123456789012",
        number = mapOf(
            LineSection.BLOCKS to st(blocksBody),
            LineSection.SERVICES to st(servicesBody),
            LineSection.TARIFF_FEE to st(feeBody),
            LineSection.LOCATION to st(abroadBody),
            LineSection.COUNTERS to st(countersBody),
            LineSection.CHARGES to st("""[{"id":"79161234567","remainedAmount":{"amount":1234.5}}]"""),
            LineSection.COMMENT to st("""[{"msisdn":"79161234567","comment":"Склад"}]"""),
        ) + extraNumber,
        account = mapOf(LineSection.BALANCE to st(balanceBody, ago = 3_600_000)),
    )

    @Test fun snapshotFromStoredBodies() {
        val s = snapshot()
        assertEquals(-1900.0, s.balance?.amount)
        assertEquals(100.0, s.available)
        assertEquals(BalanceState.LOW, s.balanceState())          // минус, но до порога 100 ₽
        assertTrue(s.voluntaryBlock)
        assertTrue(s.isBlocked)
        assertTrue(s.abroad)
        assertEquals(1234.5, s.charges)
        assertEquals("Склад", s.comment)
        assertEquals(900.0, s.monthlyFee)                        // 600 тариф + 10 в сутки × 30
        assertEquals(0.1, s.lowestPackage?.share)
        assertEquals(now - 3_600_000, s.oldestAt)
        assertEquals(now, s.updatedAt)
        assertNull(s.tariff)                                      // не загружался
    }

    @Test fun brokenSectionDoesNotBreakOthers() {
        val s = snapshot(mapOf(
            LineSection.FORWARDING to st(null, error = "МТС ответил HTTP 500"),
            LineSection.SIM_TYPE to st("""{"simType":"ESIM"}"""),
        ))
        assertNull(s.forwarding)
        assertEquals("МТС ответил HTTP 500", s.errors[LineSection.FORWARDING])
        assertTrue(s.isEsim)
        assertTrue(s.voluntaryBlock)
        // Раздел недоступен для номера (4xx) — не ошибка: значка «Ошибки» не будет.
        val u = snapshot(mapOf(LineSection.BUDGET to StoredSection(null, now, "HTTP 404: правил нет", unavailable = true)))
        assertTrue(u.errors.isEmpty())
        assertEquals("HTTP 404: правил нет", u.unavailable[LineSection.BUDGET])
    }

    @Test fun balanceStates() {
        fun state(amount: Double, limit: Double?) = LineSnapshot("7", "1",
            balance = Balance("1", amount, limit, null, null)).balanceState()
        assertEquals(BalanceState.OK, state(5000.0, -2000.0))
        assertEquals(BalanceState.NEGATIVE, state(-500.0, -2000.0))   // минус, но до порога далеко
        assertEquals(BalanceState.NEGATIVE, state(-2100.0, -2000.0))
        assertEquals(BalanceState.LOW, state(100.0, null))
        assertEquals(BalanceState.NEGATIVE, state(-500.0, -99_999_999.0))     // «порог» −99 999 999 — порога нет
        assertEquals(BalanceState.UNKNOWN, LineSnapshot("7", "1").balanceState())
    }

    @Test fun splitBulkKeepsResponseShape() {
        val parts = LineParse.splitBulk(LineSection.COMMENT,
            """[{"msisdn":"79160000001","comment":"Склад"},{"msisdn":"79160000002","comment":"Офис"}]""",
            listOf("79160000001", "79160000002", "79160000003"))
        assertEquals(mapOf("79160000001" to "Склад"), LineParse.comments(parts.getValue("79160000001")))
        assertEquals(mapOf("79160000002" to "Офис"), LineParse.comments(parts.getValue("79160000002")))
        assertEquals("[]", parts["79160000003"])
        val ch = LineParse.splitBulk(LineSection.CHARGES,
            """[{"id":"79160000001","remainedAmount":{"amount":12.5}},{"id":"79160000002","remainedAmount":{"amount":3}}]""",
            listOf("79160000001", "79160000002"))
        assertEquals(12.5, LineParse.charges(ch.getValue("79160000001"))["79160000001"])
        assertEquals(3.0, LineParse.charges(ch.getValue("79160000002"))["79160000002"])
    }

    private fun row(msisdn: String, snap: LineSnapshot, owner: String = "Иванов Иван", contract: String = "C1") =
        LineRow(msisdn, owner, contract, "Ростовский", snap.accountNo, "8970100000000000001", "МР Юг", true, snap)

    @Test fun filters() {
        val blocked = row("79161234567", snapshot())
        val clean = row("79160000002", LineSnapshot.build("79160000002", "999", mapOf(
            LineSection.BLOCKS to st("[]"), LineSection.SERVICES to st(servicesBody.replace("БИТ за границей", "Мобильный интернет")),
            LineSection.LOCATION to st("""{"Locations":[{"countryId":null}],"references":{"Countries":[]}}"""),
        ), mapOf(LineSection.BALANCE to st("""[{"accountNo":"999","customerAccountBalance":[{"remainedAmount":{"amount":5000}}]}]"""))),
            owner = "", contract = "C2")
        val rows = listOf(blocked, clean)

        fun names(f: LineFilter) = f.apply(rows, now).map { it.msisdn }
        assertEquals(listOf("79161234567"), names(LineFilter(blocks = BlockRule.SOME)))
        assertEquals(listOf("79160000002"), names(LineFilter(blocks = BlockRule.NONE)))
        assertEquals(listOf("79161234567"), names(LineFilter(blockCodes = setOf("BL0005"))))
        assertEquals(listOf("79161234567"), names(LineFilter(balance = BalanceRule.LOW)))
        assertEquals(listOf("79161234567"), names(LineFilter(roaming = Tri.YES)))
        assertEquals(listOf("79160000002"), names(LineFilter(roaming = Tri.NO)))
        assertEquals(listOf("79160000002"), names(LineFilter(owner = Tri.NO)))
        assertEquals(listOf("79160000002"), names(LineFilter(contracts = setOf("C2"))))
        assertEquals(listOf("79161234567"), names(LineFilter(serviceText = "бит")))
        assertEquals(listOf("79160000002"), names(LineFilter(serviceText = "бит", serviceMode = Tri.NO)))
        assertEquals(listOf("79161234567"), names(LineFilter(chargesAbove = 1000.0)))
        assertEquals(listOf("79161234567"), names(LineFilter(packageBelowPercent = 20)))
        assertEquals(listOf("79161234567"), names(LineFilter(query = "склад")))        // комментарий
        assertEquals(listOf("79161234567"), names(LineFilter(query = "8 916 123")))   // телефон в любом виде
        assertEquals(listOf("79161234567"), names(LineFilter(query = "турция")))
        assertEquals(listOf("79161234567", "79160000002"), names(LineFilter(sort = LineSort.BALANCE)))
        assertEquals(listOf("79161234567"), names(LineFilter(staleHours = 1)))       // баланс получен час назад
        assertEquals(2, LineFilter(blocks = BlockRule.SOME, roaming = Tri.YES, query = "x").activeCount)
    }

    @Test fun filterJsonRoundTrip() {
        val f = LineFilter(name = "Мой", contracts = setOf("C1"), balance = BalanceRule.BELOW, balanceBelow = 150.5,
            blocks = BlockRule.SOME, blockCodes = setOf("BL0009"), serviceText = "бит", serviceMode = Tri.NO, roaming = Tri.YES,
            chargesAbove = 1000.0, packageBelowPercent = 15, staleHours = 6, withErrors = true, sort = LineSort.CHARGES)
        assertEquals(f, LineFilter.fromJson(f.toJson()))
        assertEquals(listOf(f, LineFilter()), LineFilter.listFromJson(LineFilter.listToJson(listOf(f, LineFilter()))))
        assertEquals("1.5E7", Json.write(1.5e7))
    }

    private fun ttl(vararg ss: LineSection) = ss.associateWith { it.ttlMillis }

    @Test fun plannerOrdersAndSkipsFresh() {
        val numbers = mapOf("79160000002" to "A1", "79160000001" to "A1", "79160000003" to "A2")
        val fetched = mapOf(
            (LineSection.BALANCE to "A1") to now - 10 * 60_000,          // свежий
            (LineSection.BLOCKS to "79160000001") to now - 2 * 3_600_000, // устарел (час)
            (LineSection.BLOCKS to "79160000002") to now - 30 * 60_000,   // свежий
            (LineSection.BLOCKS to "79160000003") to now - 5 * 3_600_000, // самый старый
        )
        val jobs = LinePlanner.plan(numbers, ttl(LineSection.BALANCE, LineSection.COMMENT, LineSection.BLOCKS),
            { s, k -> fetched[s to k] }, now)
        assertEquals(listOf(
            LinePlanner.Job(LineSection.BALANCE, listOf("A2")),
            LinePlanner.Job(LineSection.COMMENT, listOf("79160000001", "79160000002", "79160000003")),
            LinePlanner.Job(LineSection.BLOCKS, listOf("79160000003")),
            LinePlanner.Job(LineSection.BLOCKS, listOf("79160000001")),
        ), jobs)
        // Свой период: блокировки раз в 3 ч — двухчасовые ещё свежие.
        val lazy = LinePlanner.plan(numbers, mapOf(LineSection.BLOCKS to 3 * 3_600_000L), { s, k -> fetched[s to k] }, now)
        assertEquals(listOf(LinePlanner.Job(LineSection.BLOCKS, listOf("79160000003"))), lazy)
        // Принудительно: 2 счёта + 1 пакетный запрос комментариев + 3 номера.
        assertEquals(2 + 1 + 3, LinePlanner.plan(numbers, ttl(LineSection.BALANCE, LineSection.BLOCKS, LineSection.COMMENT),
            { s, k -> fetched[s to k] }, now, force = true).size)
    }

    @Test fun plannerChunksBulk() {
        val numbers = (1..650).associate { "7916%07d".format(it) to "A" }
        val jobs = LinePlanner.plan(numbers, ttl(LineSection.COMMENT, LineSection.CHARGES), { _, _ -> null }, now)
        assertEquals(listOf(1, 3), jobs.groupBy { it.section }.let { listOf(it.getValue(LineSection.CHARGES).size, it.getValue(LineSection.COMMENT).size) })
        assertEquals(300, jobs.first { it.section == LineSection.COMMENT }.keys.size)
        assertEquals(1 + 3 + 650 * 2 + 2, LinePlanner.fullCost(650, 2, setOf(LineSection.CHARGES, LineSection.COMMENT, LineSection.BLOCKS, LineSection.TARIFF, LineSection.BALANCE)))
    }

    @Test fun initialLoadIsSectionMajorAndResumable() {
        val numbers = mapOf("79160000001" to "A1", "79160000002" to "A1")
        val status = mutableMapOf<Pair<LineSection, String>, SectionStatus>()
        val sections = setOf(LineSection.SIM_TYPE, LineSection.BLOCKS, LineSection.BALANCE, LineSection.COMMENT)
        val first = LinePlanner.initial(numbers, sections) { s, k -> status[s to k] }
        // Сначала баланс счёта и пакетные комментарии, затем блокировки по всем номерам, тип SIM — в конце.
        assertEquals(listOf(
            LinePlanner.Job(LineSection.BALANCE, listOf("A1")),
            LinePlanner.Job(LineSection.COMMENT, listOf("79160000001", "79160000002")),
            LinePlanner.Job(LineSection.BLOCKS, listOf("79160000001")),
            LinePlanner.Job(LineSection.BLOCKS, listOf("79160000002")),
            LinePlanner.Job(LineSection.SIM_TYPE, listOf("79160000001")),
            LinePlanner.Job(LineSection.SIM_TYPE, listOf("79160000002")),
        ), first)
        // Загрузка прервалась: часть получена, одна — недоступна, одна — сбой.
        status[LineSection.BALANCE to "A1"] = SectionStatus.LOADED
        status[LineSection.COMMENT to "79160000001"] = SectionStatus.LOADED
        status[LineSection.COMMENT to "79160000002"] = SectionStatus.LOADED
        status[LineSection.BLOCKS to "79160000001"] = SectionStatus.UNAVAILABLE
        status[LineSection.BLOCKS to "79160000002"] = SectionStatus.FAILED
        val second = LinePlanner.initial(numbers, sections) { s, k -> status[s to k] }
        assertEquals(listOf(LineSection.BLOCKS to "79160000002", LineSection.SIM_TYPE to "79160000001", LineSection.SIM_TYPE to "79160000002"),
            second.map { it.section to it.keys.single() })
        val cov = LinePlanner.coverage(numbers, sections) { s, k -> status[s to k] }
        assertEquals(LinePlanner.Coverage(total = 7, loaded = 3, unavailable = 1, failed = 1), cov)
        assertEquals(2, cov.missing)
        assertEquals(57, cov.percent)
        assertTrue(!cov.complete)
    }

    @Test fun scheduleByCategory() {
        val d = LineSchedule()
        assertEquals(60 * 60_000L, d.ttl[LineSection.BALANCE])
        assertEquals(60 * 60_000L, d.ttl[LineSection.UNPAID])        // та же категория «Баланс и счёт»
        assertEquals(7 * 24 * 60 * 60_000L, d.ttl[LineSection.SIM_TYPE])
        assertEquals(60, d.runEveryMinutes)
        val s = d.with(LineCategory.BLOCKS, 15).with(LineCategory.SIM_TYPE, 0)
        assertEquals(15, s.runEveryMinutes)
        assertTrue(LineSection.SIM_TYPE !in s.sections)
        assertEquals(s, LineSchedule.fromJson(s.toJson()))
        assertEquals(LineSchedule(), LineSchedule.fromJson("мусор"))
        val off = LineSchedule(LineCategory.entries.associateWith { 0 })
        assertEquals(0, off.runEveryMinutes)
        assertTrue(off.sections.isEmpty())
        // Каждый раздел — ровно в одной категории.
        LineSection.entries.forEach { sec -> assertEquals(1, LineCategory.entries.count { sec in it.sections }) }
        assertEquals("Раз в сутки", LineCategory.periodTitle(1440))
        assertEquals("3 ч", LineCategory.periodTitle(180))
    }

    @Test fun errorKindsFromRealJournal() {
        assertTrue(!LineErrors.isTransient(500, """{"code":500,"message":"IL.UnknownError","description":"FORIS_IL Неизвестная ошибка"}"""))
        assertTrue(LineErrors.isTransient(421, """{"errorMessage":"Сервис Foris временно недоступен","sourceID":"PublicApi","errorCode":"3003"}"""))
        assertTrue(!LineErrors.isTransient(400, """{"message":"Отсутствует Группа КБ"}"""))
        assertTrue(!LineErrors.isTransient(403, """{"code":403,"message":"OperationClose"}"""))
        assertTrue(!LineErrors.isTransient(423, """{"code":423,"message":"PhoneBlocked"}"""))
        assertTrue(LineErrors.isTransient(503, "<html>Service Unavailable</html>"))
        assertTrue(LineErrors.isTransient(500, "Internal Server Error"))
        assertTrue(LineErrors.isTransient(429, ""))
        assertTrue(LineErrors.isTransient(null, null))
        assertEquals("FORIS_IL Для данного абонента операция не разрешена!",
            LineErrors.describe("""{"code":403,"message":"OperationClose","description":"FORIS_IL Для данного абонента операция не разрешена!"}"""))
        assertEquals("Отсутствует Группа КБ", LineErrors.describe("""{"message":"Отсутствует Группа КБ"}"""))
        assertEquals(24 * 60 * 60_000L, LineSchedule().ttl[LineSection.PROMISED])
    }

    @Test fun digestRoundTrip() {
        val full = snapshot(mapOf(
            LineSection.FORWARDING to st("""[{"productCharacteristic":[{"name":"ForwardingAddress","value":"79990001122"},{"name":"ForwardingType","value":"CFNRY"},{"name":"NoReplyTimer","value":"20"}]}]"""),
            LineSection.TARIFF to st("""{"productOffering":{"externalID":"0397","name":"Умный бизнес S","validFor":{"startDateTime":"2024-01-01T00:00:00"}}}"""),
            LineSection.SIM_TYPE to st("""{"simType":"ESIM"}"""),
            LineSection.BUDGET to StoredSection(null, now, "HTTP 400: Отсутствует Группа КБ", unavailable = true),
            LineSection.SERVICES to st(null, error = "МТС ответил HTTP 503"),
        ))
        assertEquals(full, LineDigest.decode(LineDigest.encode(full)))
        val empty = LineSnapshot("79160000001", "")
        assertEquals(empty, LineDigest.decode(LineDigest.encode(empty)))
        assertNull(LineDigest.decode("""{"v":0,"m":"7"}"""))     // другая версия — пересчитать
        assertNull(LineDigest.decode("мусор"))
        assertTrue(LineDigest.encode(full).length < 3000)
    }

    @Test fun oldBlocksAndServiceChoice() {
        val today = LineSnapshot.toLocal(now)
        fun line(m: String, blockedYearsAgo: Long?, vararg services: String) = row(m, LineSnapshot(m, "A",
            blocks = blockedYearsAgo?.let { listOf(ProductItem("BL0005", "Добровольная блокировка", since = today.minusDays(it * 365 + 5))) } ?: emptyList(),
            services = services.mapIndexed { i, n -> ProductItem("S$i", n) }))
        val rows = listOf(
            line("79160000001", 3, "Интернет VIP"),
            line("79160000002", 1, "Пакет 30 ГБ"),
            line("79160000003", null, "Пакет 70 ГБ", "Кто звонил"),
            line("79160000004", 0, "Кто звонил"),
        )
        fun names(f: LineFilter) = f.apply(rows, now).map { it.msisdn.takeLast(1) }
        // Блокировка дольше двух лет.
        assertEquals(listOf("1"), names(LineFilter(blocks = BlockRule.SOME, blockOlderDays = 730)))
        assertEquals(listOf("1", "2"), names(LineFilter(blockOlderDays = 365)))
        assertEquals(emptyList(), names(LineFilter(blockOlderDays = 365, blockCodes = setOf("BL0009"))))
        // Несколько услуг из списка — подходит любая.
        assertEquals(listOf("1", "2", "3"), names(LineFilter(services = setOf("Интернет VIP", "Пакет 30 ГБ", "Пакет 70 ГБ"))))
        assertEquals(listOf("4"), names(LineFilter(services = setOf("Интернет VIP", "Пакет 30 ГБ", "Пакет 70 ГБ"), serviceMode = Tri.NO)))
        // Текст: варианты через запятую.
        assertEquals(listOf("1", "2", "3"), names(LineFilter(serviceText = "vip, 30 гб, 70 гб")))
        // Давние блокировки — первыми, без блокировки — в конце.
        assertEquals(listOf("1", "2", "4", "3"), names(LineFilter(sort = LineSort.BLOCK_AGE)))
        val f = LineFilter(services = setOf("Интернет VIP"), blockOlderDays = 730)
        assertEquals(f, LineFilter.fromJson(f.toJson()))
        assertEquals(2, f.activeCount)
        assertEquals(730, LineFilter.PRESETS.first { it.name == "Блокировка > 2 лет" }.blockOlderDays)
    }
}
