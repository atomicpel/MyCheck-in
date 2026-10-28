package ru.mycheckin.passport.mts

import ru.mycheckin.passport.Mrz
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeClock(var now: Long = 1_790_000_000_000L) : RateLimiter.Clock {
    val sleeps = mutableListOf<Long>()
    override fun nowMillis() = now
    override fun sleep(millis: Long) { sleeps += millis; now += millis }
}

class MtsTest {
    @Test fun limiterNeverExceedsPerSecondAndPerMinute() {
        val clock = FakeClock()
        val limiter = RateLimiter(perSecond = 10, perMinute = 300, clock = clock)
        val times = mutableListOf<Long>()
        repeat(700) { limiter.acquire(); times += clock.now; clock.now += 5 }
        for (i in times.indices) {
            assertTrue(times.count { it in times[i] until times[i] + 1_000 } <= 10, "секунда с ${times[i]}")
            assertTrue(times.count { it in times[i] until times[i] + 60_000 } <= 300, "минута с ${times[i]}")
        }
    }

    @Test fun limiterStopsAtMonthlyLimit() {
        val clock = FakeClock()
        val store = RateLimiter.MonthlyStore.InMemory()
        val limiter = RateLimiter(perSecond = 1000, perMinute = 100_000, perMonth = 5, monthlyStore = store, clock = clock)
        repeat(5) { limiter.acquire() }
        assertEquals(5, limiter.usedThisMonth())
        assertFailsWith<RateLimiter.MonthlyLimitExceeded> { limiter.acquire() }
    }

    private fun passport() = Mrz.build("Иванов", "Иван", "Иванович", "4601", "123456", 'M',
        LocalDate.of(1990, 3, 15), LocalDate.of(2010, 6, 20), "770-120").let { (l1, l2) ->
        Mrz.parse(l1, l2, LocalDate.of(2026, 10, 27)).apply {
            issuedBy = "ОТДЕЛОМ \"УФМС\" РОССИИ"
            birthPlace = "ГОР. МОСКВА"
        }
    }

    @Test fun normalizesPhone() {
        assertEquals("79123456789", PersonalDataApi.normalizeMsisdn("8 (912) 345-67-89"))
        assertEquals("79123456789", PersonalDataApi.normalizeMsisdn("+7 912 345 67 89"))
        assertEquals("79123456789", PersonalDataApi.normalizeMsisdn("9123456789"))
        assertEquals(null, PersonalDataApi.normalizeMsisdn("84951234567"))
        assertEquals(null, PersonalDataApi.normalizeMsisdn("12345"))
    }

    @Test fun changeBodyMatchesMtsSpec() {
        val body = PersonalDataApi.changeBody(passport(), "79123456789", "guid-1", RegAddress(city = "Керчь", house = "5", zip = "298300"))
        val j = Json.parse(body)
        val item = j.path("request", "Items").list().single()
        assertEquals("79123456789", item.field("Msisdn"))
        val u = item.field("UserData")
        assertEquals("Create", u.field("Action"))
        assertEquals("1990-03-15T00:00:00", u.field("Birthday"))
        assertEquals("Male", u.field("Gender"))
        assertEquals("ГОР. МОСКВА", u.field("BirthPlace"))
        assertEquals(false, u.field("IsEntrepreneur"))
        assertEquals("1", u.path("LegalCategory", "Code"))
        val doc = u.field("Identifications").list().single()
        assertEquals("21", doc.path("DocumentType", "Code"))
        assertEquals("RU", doc.path("Country", "Code"))
        assertEquals("4601", doc.field("DocumentSeries"))
        assertEquals("123456", doc.field("DocumentNumber"))
        assertEquals("2010-06-20T00:00:00", doc.field("DateIssued"))
        assertEquals("ОТДЕЛОМ \"УФМС\" РОССИИ", doc.field("Issuer"))
        assertEquals("770-120", doc.field("IssuerCode"))
        val n = u.field("Names").list().single()
        assertEquals(listOf("Иванов", "Иван", "Иванович", "1"), listOf(n.field("SurName"), n.field("FirstName"), n.field("SecondName"), n.path("Language", "Code")))
        val a = u.field("Addresses").list().single()
        assertEquals("Россия", a.field("Country"))
        assertEquals("RegAddress", a.field("AddressTypes"))
        assertEquals(null, a.field("Street"))          // пустые поля не передаются
        val si = j.field("SubscriberInformation")
        assertEquals(listOf("guid-1", "DB:", "MobileAPI", "MTSBusinessAPI"),
            listOf(si.field("MessageId"), si.field("ReplyToURL"), si.field("SubscriberName"), si.field("OperatorType")))
        // Без адреса блок Addresses не передаётся, без отчества — SecondName.
        val p2 = passport().apply { patronymic = null; sex = 'F' }
        val u2 = Json.parse(PersonalDataApi.changeBody(p2, "79123456789", "g", RegAddress())).path("request", "Items").list()[0].field("UserData")
        assertEquals(null, u2.field("Addresses"))
        assertEquals("Female", u2.field("Gender"))
        assertEquals(null, u2.field("Names").list()[0].field("SecondName"))
    }

    @Test fun problemsAreFoundBeforeSending() {
        val p = passport().apply { divisionCode = "770120"; issuedBy = "" }
        val pr = PersonalDataApi.problems(p, null)
        assertTrue(pr.any { "телефон" in it.lowercase() })
        assertTrue(pr.any { "Код подразделения" in it })
        assertTrue(pr.any { "Кем выдан" in it || "кем выдан" in it })
        assertTrue(PersonalDataApi.problems(passport(), "79123456789").isEmpty())
    }

    @Test fun parsesResponses() {
        val ok = PersonalDataApi.parseSubmit("""{"response":{"ErrorCode":"","ErrorDescription":""},"MessageId":"abc-1"}""")
        assertTrue(ok.accepted)
        assertEquals("abc-1", ok.messageId)
        val bad = PersonalDataApi.parseSubmit("""{"response":{"ErrorCode":"35","ErrorDescription":"Неверная серия"},"MessageId":"abc-2"}""")
        assertTrue(!bad.accepted)
        assertEquals("Неверная серия", bad.errorDescription)

        val res = PersonalDataApi.parseResult("""{"response":{"Body":{"GetOperationResultResult":[{"Id":"1","OperationDate":"2026-10-27T12:00:00",
            "BusinessProcessCommonResult":{"${'$'}type":"BusinessProcessCommonBulkResult","Objects":[{"Object":{"EntityType":"PhysicalResource","Msisdn":"79123456789"},
            "OperationStatus":"Faulted","Errors":[{"Message":"Документ недействителен","Code":"77"}]}],"State":"Faulted","Errors":[]}}]}}}""", "79123456789")
        assertEquals("Faulted", res.status)
        assertEquals("Отклонено", res.title)
        assertEquals(listOf("Документ недействителен (код 77)"), res.errors)

        val conf = PersonalDataApi.parseConfirmation("""[{"name":"x","characteristic":[{"name":"Other","value":"1"},{"name":"PersonalDataConfirmation","value":"WaitingForAcceptance"}]}]""")
        assertEquals("WaitingForAcceptance", conf.code)
        assertTrue("Госуслуги" in conf.description)
    }

    @Test fun jsonRoundTrip() {
        val v = Json.parse("""{"a":[1,2.5,true,null,"x\"y\u0416"],"b":{}}""")
        assertEquals(listOf(1L, 2.5, true, null, "x\"yЖ"), v.field("a"))
        assertEquals("""{"s":"a\"b","n":[1,false]}""", Json.write(linkedMapOf("s" to "a\"b", "skip" to null, "n" to listOf(1, false))))
        assertFailsWith<IllegalArgumentException> { Json.parse("{\"a\":}") }
    }

    @Test fun clientSendsHeadersRefreshesTokenAndRetries() {
        val clock = FakeClock()
        val calls = mutableListOf<String>()
        var submitCount = 0
        val http = Http { method, url, headers, body ->
            calls += "$method $url"
            when {
                url.endsWith("/token") -> {
                    assertTrue(headers.getValue("Authorization").startsWith("Basic "))
                    assertEquals("grant_type=client_credentials", body)
                    HttpResponse(200, """{"access_token":"t${calls.size}","token_type":"Bearer","expires_in":3600}""")
                }
                url.endsWith("/PersonalData/ChangePersonalData") -> {
                    submitCount++
                    when (submitCount) {
                        1 -> HttpResponse(429, "", mapOf("Retry-After" to "2"))
                        2 -> HttpResponse(401, """{"errorCode":1013,"errorMessage":"token expired"}""")
                        else -> {
                            assertTrue(headers.getValue("Authorization").startsWith("Bearer t"))
                            assertEquals(PersonalDataApi.CHANGE_SOAP_ACTION, headers["x-soap-action"])
                            assertTrue(headers.getValue("x-soap-action").startsWith("\""))
                            assertEquals("79123456789", headers["X-MTS-MSISDN"])
                            assertEquals("msg-1", Json.parse(body!!).path("SubscriberInformation", "MessageId"))
                            HttpResponse(200, """{"response":{"ErrorCode":"","ErrorDescription":""},"MessageId":"srv-1"}""")
                        }
                    }
                }
                url.endsWith("/Operations/GetOperationResult") -> {
                    assertEquals(PersonalDataApi.RESULT_SOAP_ACTION, headers["x-soap-action"])
                    assertEquals("srv-1", Json.parse(body!!).path("request", "Body", "MessageId"))
                    HttpResponse(200, """{"response":{"Body":{"GetOperationResultResult":[{"BusinessProcessCommonResult":{"Objects":[],"State":"InProgress","Errors":[]}}]}}}""")
                }
                url.contains("/PersonalData/PersonalDataInfo?contactMedium.phoneNumber=79123456789") -> {
                    assertEquals("GET", method)
                    HttpResponse(200, """[{"characteristic":[{"name":"PersonalDataConfirmation","value":"Activated"}]}]""")
                }
                else -> HttpResponse(404, "")
            }
        }
        val client = MtsClient(MtsConfig("login", "password"), RateLimiter(clock = clock), http, clock, newMessageId = { "msg-1" })
        val r = client.submitPersonalData(passport(), "8 912 345-67-89")
        assertTrue(r.accepted)
        assertEquals("srv-1", r.messageId)
        assertEquals(2, calls.count { it.endsWith("/token") })   // первый + обновление после 401
        assertTrue(2000L in clock.sleeps)                          // пауза по Retry-After
        assertEquals("InProgress", client.operationResult("89123456789", "srv-1").status)
        assertTrue(client.confirmationStatus("+79123456789").confirmed)
        assertEquals(2, calls.count { it.endsWith("/token") })   // токен кеширован
    }

    @Test fun httpErrorIsReported() {
        val http = Http { _, url, _, _ ->
            if (url.endsWith("/token")) HttpResponse(200, """{"access_token":"t","expires_in":3600}""")
            else HttpResponse(400, """{"fault":{"message":"Invalid MSISDN","code":"900"}}""")
        }
        val client = MtsClient(MtsConfig("l", "p"), RateLimiter(clock = FakeClock()), http, FakeClock())
        val e = assertFailsWith<MtsApiException> { client.confirmationStatus("79123456789") }
        assertTrue("Invalid MSISDN" in e.message!!)
    }

    @Test fun badCredentialsAreExplained() {
        val client = MtsClient(MtsConfig("l", "p"), RateLimiter(clock = FakeClock()), { _, _, _, _ -> HttpResponse(401, "") }, FakeClock())
        val e = assertFailsWith<MtsApiException> { client.token() }
        assertTrue("логин и пароль" in e.message!!)
    }

    @Test fun rejectsPlainHttp() {
        val client = MtsClient(MtsConfig("k", "s", apiBaseUrl = "http://api.mts.ru/b2b/v1"), RateLimiter(), { _, _, _, _ -> HttpResponse(200, "{\"access_token\":\"t\"}") })
        assertFailsWith<IllegalArgumentException> { client.submitPersonalData(passport(), "79123456789") }
        val client2 = MtsClient(MtsConfig("k", "s", tokenUrl = "http://api.mts.ru/token"), RateLimiter(), { _, _, _, _ -> HttpResponse(200, "") })
        assertFailsWith<IllegalArgumentException> { client2.token() }
    }

    private val hierarchyJson = """[{"description":"NationalContract","name":"ООО Ромашка","partyRole":[
        {"type":"Customer","description":"Contract","id":"161374779436","characteristic":[{"name":"INN","value":"7700000000"},{"name":"KPP","value":"770001001"}],
         "customerAccount":[{"accountType":"CustomerAccount","description":"Account","accountNo":"123456789",
           "productRelationship":[
             {"relationshipType":"ProductRelationship","description":"MSISDN","product":{"description":"МР Юг","productSerialNumber":"79896296939",
               "productCharacteristic":[{"name":"IMSI","value":"250010000000001"},{"name":"SIM","value":"89701015388529130733"}]}},
             {"relationshipType":"ProductRelationship","description":"MSISDN","product":{"description":"МР Юг","productSerialNumber":"79885701906",
               "productCharacteristic":[{"name":"SIM","value":"89701015388574116447"}]}}]}]},
        {"type":"Customer","description":"Contract","id":"222","customerAccount":[]}]}]"""

    @Test fun parsesHierarchy() {
        val h = Hierarchy.parse(hierarchyJson)
        assertEquals(listOf("161374779436", "222"), h.contracts.map { it.id })
        assertEquals("ООО Ромашка", h.contracts[0].orgName)
        assertEquals("7700000000", h.contracts[0].inn)
        assertEquals(2, h.numbers.size)
        val n = h.numbers[0]
        assertEquals("79896296939", n.msisdn)
        assertEquals("89701015388529130733", n.iccid)
        assertEquals("250010000000001", n.imsi)
        assertEquals("123456789", n.accountNo)
        assertEquals("МР Юг", n.region)
        assertEquals(null, h.numbers[1].imsi)
        assertTrue(!h.hasMore)
        assertTrue(Hierarchy.parse("""[{"name":"x","partyRole":[],"hasMore":true}]""").hasMore)
    }

    @Test fun fetchAllPagesWhenHasMore() {
        val urls = mutableListOf<String>()
        val http = Http { _, url, _, _ ->
            urls += url
            when {
                url.endsWith("/token") -> HttpResponse(200, """{"access_token":"t","expires_in":3600}""")
                url.endsWith("/Service/HierarchyStructure") ->
                    HttpResponse(200, """[{"name":"Org","hasMore":true,"partyRole":[{"id":"C1","customerAccount":[{"accountNo":"A","productRelationship":[
                        {"product":{"productSerialNumber":"79000000001"}}]}]}]}]""")
                url.contains("pageNum=1") -> HttpResponse(200, """[{"name":"Org","hasMore":true,"partyRole":[{"id":"C1","customerAccount":[{"accountNo":"A","productRelationship":[
                        {"product":{"productSerialNumber":"79000000001"}},{"product":{"productSerialNumber":"79000000002"}}]}]}]}]""")
                url.contains("pageNum=2") -> HttpResponse(200, """[{"name":"Org","partyRole":[{"id":"C1","customerAccount":[{"accountNo":"A","productRelationship":[
                        {"product":{"productSerialNumber":"79000000003"}}]}]}]}]""")
                else -> HttpResponse(404, "")
            }
        }
        val client = MtsClient(MtsConfig("l", "p"), RateLimiter(clock = FakeClock()), http, FakeClock())
        val all = client.fetchAllNumbers()
        assertEquals(listOf("79000000001", "79000000002", "79000000003"), all.numbers.map { it.msisdn })
        assertTrue(urls.any { it.endsWith("HierarchyStructure?contract=C1&pageNum=2&pageSize=1000") })
        assertEquals(1 + 1 + 2, urls.size)   // токен + общий запрос + 2 страницы
    }

    @Test fun parsesOwnerInfo() {
        val o = OwnerInfo.parse("""[{"placeOfBirth":"ГОР. КИРОВ","gender":"Male","name":"Дудин Павел Андреевич","id":"1",
            "identifiedBy":[{"type":"IndividualDocumentIdentification","issuingAuthorityID":"430-024","documentType":"Паспорт гражданина РФ",
              "issuedBy":"ОТДЕЛЕНИЕМ УФМС","documentNo":"092076","issuingCountry":"RU","documentSeries":"33 11","issuedDate":"2011-07-19T00:00:00"}],
            "characteristic":[{"name":"PersonalDataConfirmation","value":"Activated"}],"birthDate":"1991-04-06T00:00:00",
            "contactMedium":[{"type":"RegisteredAddress","addressType":"RegAddress","address":{"type":"RussianBasicAddress","country":"Россия",
              "addressObject":{"type":"RussianBasicAddressObject","name":"Ростовская обл","objectType":"District",
                "addressObject":{"type":"RussianBasicAddressObject","name":"Ростов-на-Дону","objectType":"City",
                  "addressObject":{"type":"RussianBasicAddressObject","name":"Садовая","objectType":"Street",
                    "addressObject":{"type":"RussianBasicHouseObject","number":"5","apartment":{"type":"RussianBasicApartment","number":"12"}}}}}}}]}]""")
        assertEquals(listOf("Дудин", "Павел", "Андреевич"), listOf(o.surname, o.name, o.patronymic))
        assertEquals('M', o.sex)
        assertEquals(LocalDate.of(1991, 4, 6), o.birthDate)
        assertEquals("3311", o.series)
        assertEquals("092076", o.number)
        assertEquals(LocalDate.of(2011, 7, 19), o.issueDate)
        assertEquals("430-024", o.divisionCode)
        assertTrue(o.confirmation.confirmed)
        assertEquals(RegAddress(region = "Ростовская обл", city = "Ростов-на-Дону", street = "Садовая", house = "5", apartment = "12"), o.address)
        val empty = OwnerInfo.parse("[]")
        assertTrue(!empty.hasPassport && !empty.hasName && empty.confirmation.code == null)
    }
}
