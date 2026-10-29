package ru.mycheckin.passport.mts

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор ответов по примерам из справки МТС Бизнес API (значения вымышленные). */
class LineApiTest {

    @Test fun balanceByAccountAndMsisdn() {
        val b = LineParse.balances("""[{"accountStatus":"Success","accountNo":"123456789012","customerAccountBalance":[{"type":"CustomerAccountBalance",
            "remainedAmount":{"unitOfMeasure":"Российский рубль","amount":-4999.991004},"customerAccount":{"accountType":"CustomerAccount","creditLimit":-2000.0},
            "validFor":{"endDateTime":"2038-01-01T00:00:00.000+0000"}}],
            "productRelationship":[{"relationshipType":"ProductRelationship","product":{"productCharacteristic":[{"name":"MSISDN","value":"79161234567"}]}}]}]""")
        assertEquals(1, b.size)
        assertEquals("123456789012", b[0].accountNo)
        assertEquals(-4999.991004, b[0].amount)
        assertEquals(-2000.0, b[0].creditLimit)
        assertEquals(listOf("79161234567"), b[0].msisdns)
        assertEquals(2038, b[0].validTo?.year)
        // Ответ по номеру — объект, а не массив; при fields=MOAF лимит и срок лежат в customerAccount.
        val m = LineParse.balances("""{"accountStatus":"Success","accountNo":"1","customerAccountBalance":[{"remainedAmount":{"amount":50.01},
            "customerAccount":{"creditLimit":-99999999,"validFor":{"endDateTime":"2030-05-01T00:00:00.000+0000"}}}]}""")
        assertEquals(50.01, m[0].amount)
        assertEquals(-99999999.0, m[0].creditLimit)
        assertEquals(2030, m[0].validTo?.year)
    }

    @Test fun servicesBlocksAndPrices() {
        val s = LineParse.products("""[{"externalID":"PE0526","version":"4","name":"БИТ за границей","status":"ACTIVE","actionAllowed":["CREATE"],
            "validFor":{"startDateTime":"2021-10-26T14:55:42","endDateTime":"0001-01-01T00:00:00"},"productSpecification":{"name":"Service"},
            "productOfferingPrice":[{"name":"Fee.Discount","unitOfMeasure":"MONTH","price":{"taxIncludedAmount":-10.0}},
              {"name":"Fee","unitOfMeasure":"MONTH","price":{"currencyCode":"810","dutyFreeAmount":100.0,"taxIncludedAmount":120.0,"taxRate":20.0}}],
            "product":[{"id":"x","productCharacteristic":[{"name":"IsActivated","value":"true"},{"name":"IsBlocked","value":"false"}]}]}]""")
        val p = s.single()
        assertEquals("PE0526", p.code)
        assertEquals("БИТ за границей", p.name)
        assertEquals(LocalDateTime.of(2021, 10, 26, 14, 55, 42), p.since)
        assertNull(p.until)                     // 0001-01-01 — бессрочно
        assertEquals(120.0, p.fee)              // без «Discount» в названии
        assertEquals("MONTH", p.feeUnit)
        assertEquals(true, p.activated)
        assertEquals(false, p.blocked)
        assertTrue(p.feeText!!.contains("в мес."))

        val blocks = LineParse.products("""[{"externalID":"BL0009","name":"Принудительная блокировка","status":"ACTIVE","actionAllowed":["NONE"],
            "validFor":{"startDateTime":"2021-10-23T10:00:33","endDateTime":"0001-01-01T00:00:00"}}]""")
        assertTrue(blocks.single().isBlock)
        assertTrue(LineParse.products("[]").isEmpty())
    }

    @Test fun tariffAndFee() {
        val t = LineParse.tariff("""{"productCharacteristic":[{"name":"MSISDN","value":"79161234567"}],"productOffering":{"externalID":"0397",
            "name":"Умный бизнес S (КОРП) (SS)","bundledProductOffering":[{"externalID":"0397.2.255647","name":"Казань - Умный бизнес S (КОРП) (SS)"}],
            "validFor":{"startDateTime":"2024-01-01T00:00:00"}}}""")!!
        assertEquals("0397", t.code)
        assertEquals("Умный бизнес S (КОРП) (SS)", t.name)
        assertEquals("Казань - Умный бизнес S (КОРП) (SS)", t.regionalName)

        val f = LineParse.tariffFee("""{"version":"Primary","productOfferingPrice":[{"name":"NextPrimaryFee","price":{}},
            {"unitOfMeasure":"MONTH","name":"CurrentPrimaryFee","price":{"currencyCode":"810","dutyFreeAmount":500.0,"taxIncludedAmount":600.0,"taxRate":100.0}},
            {"unitOfMeasure":"MONTH","name":"CurrentPrimaryFee.Discount","price":{"currencyCode":"810","dutyFreeAmount":-50.0}}]}""")!!
        assertEquals(600.0, f.current)
        assertEquals("MONTH", f.currentUnit)
        assertNull(f.next)
        assertEquals(-50.0, f.discount)

        val av = LineParse.availableTariffs("""[{"name":"Бизнес M","id":"0400","productOfferingPrice":[
            {"type":"ProductOfferingPrice","unitOfMeasure":"MONTH","name":"Fee","price":{"currencyCode":"810","taxIncludedAmount":900}},
            {"type":"DiscountProdOfferPriceAlteration","name":"Disc","price":{"dutyFreeAmount":-100}}]}]""")
        assertEquals(AvailableTariff("0400", "Бизнес M", 900.0, "MONTH"), av.single())
    }

    @Test fun counters() {
        val c = LineParse.counters("""[{"name":"ForisCounters","customerAccount":[{"productRelationship":[
            {"product":{"productPrice":[{"unitOfMeasure":"SECOND"}],"validFor":{"endDateTime":"2026-11-01T00:00:00"},
              "productSpecification":{"name":"Минуты по России","id":"1","productSpecCharacteristic":[{"prodSpecCharacteristicValue":[
                {"valueType":"CurrentValue","value":"18000"},{"valueType":"CurrentInitialValue","value":"30000"},{"valueType":"CounterValueType","value":"RDRemainder"}]}]}}},
            {"product":{"productPrice":[{"unitOfMeasure":"ITEM"}],
              "productSpecification":{"name":"SMS","productSpecCharacteristic":[{"prodSpecCharacteristicValue":[
                {"valueType":"CurrentValue","value":12},{"valueType":"CounterValueType","value":"Accumulation"}]}]}}}]}]},
          {"name":"h2oProfile","description":"default","productSpecCharacteristic":[{"name":"quota","prodSpecCharacteristicValue":[
            {"valueType":"BTQ","value":"20971520"},{"valueType":"reminder","value":"10485760"},{"valueType":"Consumption","value":"10485760"}]}]}]""")
        val minutes = c.first { it.kind == Counter.Kind.MINUTES }
        assertEquals(300.0, minutes.left)
        assertEquals(500.0, minutes.total)
        assertEquals(200.0, minutes.used)
        assertEquals(0.6, minutes.share)
        val sms = c.first { it.kind == Counter.Kind.SMS }
        assertNull(sms.left)
        assertEquals(12.0, sms.used)
        val net = c.first { it.kind == Counter.Kind.INTERNET }
        assertEquals(10240.0, net.left)          // КБ → МБ
        assertEquals(20480.0, net.total)
    }

    @Test fun chargesCommentsUnpaid() {
        assertEquals(mapOf("79161234567" to 1234.5), LineParse.charges("""[{"description":"Success","id":"79161234567","remainedAmount":{"units":"810","amount":1234.5}}]"""))
        assertEquals(mapOf("79161234567" to "Склад", "123456789012" to "Офис"),
            LineParse.comments("""[{"msisdn":"79161234567","comment":"Склад"},{"account":"123456789012","comment":"Офис"}]"""))
        assertEquals(1500.5, LineParse.unpaid("""{"data":{"accounts":["1"],"amount":1500.5,"currencyCode":"810"}}"""))
    }

    @Test fun forwardingLocationSimType() {
        val f = LineParse.forwarding("""[{"relatedParty":[{"type":"Party","characteristic":[{"name":"MSISDN","value":"79161234567"}]}],
            "productCharacteristic":[{"name":"ForwardingAddress","value":"79990001122"},{"name":"ForwardingType","value":"CFNRY"},
            {"name":"NoReplyTimer","value":"20"},{"name":"NumType","value":"Regular"}]}]""")
        assertEquals(Forwarding("CFNRY", "79990001122", 20, "Regular"), f.single())
        assertEquals("Если нет ответа", f.single().title)

        val abroad = LineParse.location("""{"date":"2026-10-28T10:00:00.618Z","Locations":[{"msisdn":"79161234567","imsi":"1","date":"2026-10-28T09:00:00Z",
            "vlr":"1","countryId":"112"}],"references":{"Countries":[{"id":"112","alpha2":"TR","alpha3":"TUR","name":"Турция"}]}}""")
        assertTrue(abroad.abroad)
        assertEquals("Турция", abroad.countryName)
        val home = LineParse.location("""{"date":null,"Locations":[{"msisdn":"79161234567","vlr":"","countryId":null}],"references":{"Countries":[]}}""")
        assertTrue(!home.abroad)

        assertEquals("ESIM", LineParse.simType("""{"simType":"ESIM"}"""))
    }

    @Test fun paymentsPromisedBudget() {
        val p = LineParse.payments("""{"code":0,"accountNumber":"123456789012","payments":[
            {"paymentParameters":{"commonParameter":{"customerDetails":"123456789012","paymentForm":"Банковская карта","specDetailCode":"Common"}},
             "amount":1000,"currency":{"currencyCode":"810"},"dateOfPayment":"2026-10-01T10:00:00+03:00","paymentDocumentNumber":"77","paymentType":"Платёж"},
            {"paymentParameters":{"commonParameter":{"note":"Перенос","paymentForm":"Перенос"}},"amount":200.5,"dateOfPayment":"2026-10-05T10:00:00+03:00"}]}""")
        assertEquals(2, p.size)
        assertEquals(200.5, p[0].amount)          // новые сверху
        assertEquals("Перенос", p[0].note)
        assertEquals("Банковская карта", p[1].form)

        val pp = LineParse.promised("""{"result":{"code":"OK"},"msisdn":"79161234567","amount":300,"currencyCode":"810","dateTo":"2026-11-01T00:00:00Z",
            "maxQuantity":2,"quantity":1,"price":[{"amountFrom":1,"amountTo":99,"price":0},{"amountFrom":100,"amountTo":499,"price":20}],
            "isPossibleNegativeBalance":true,"minBalance":-50}""")
        assertTrue(pp.available)
        assertEquals(20.0, pp.priceFor(300.0))
        assertEquals(499.0, pp.maxAmount)
        assertEquals(true, pp.possibleWithNegative)

        // Как в примере справки: массив целиком внутри строки.
        val rules = LineParse.budgetRules("\"[{\\\"activeFrom\\\":\\\"2026-01-01T00:00:00\\\",\\\"activeTo\\\":null,\\\"ruleType\\\":\\\"1\\\"," +
            "\\\"remainder\\\":{\\\"ruleId\\\":\\\"9\\\",\\\"formattedValue\\\":\\\"300 руб\\\"},\\\"ruleInformation\\\":{\\\"productCode\\\":\\\"CB1\\\"," +
            "\\\"title\\\":\\\"Связь\\\",\\\"subTitle\\\":\\\"Оплата связи\\\"},\\\"valueAndCurrency\\\":{\\\"formattedValue\\\":\\\"500 руб\\\"}}]\"")
        assertEquals("Связь", rules.single().title)
        assertEquals("300 руб", rules.single().remainder)
        assertEquals("500 руб", rules.single().limit)
    }

    @Test fun eventsAndStatuses() {
        assertEquals("abc", LineParse.eventAck(HttpResponse(200, """{"eventID":"abc","eventTime":"2026-10-28T00:00:00"}""")).id)
        assertEquals("1234.5678", LineParse.eventAck(HttpResponse(200, "\"1234.5678\"")).id)          // переадресация
        assertEquals("trace-1", LineParse.eventAck(HttpResponse(200, """{"code":"200","message":"Success"}""", mapOf("X-Trace-Id" to "trace-1"))).id)
        assertEquals("m-1", LineParse.eventAck(HttpResponse(200, """[{"code":200,"message":"OK","request":{"messageId":"m-1"}}]""")).id)
        assertEquals("r-1", LineParse.eventAck(HttpResponse(200, """{"code":200,"response":{"errorCode":"OK","requestId":"r-1"}}""")).id)

        val st = LineParse.requestStatus("""{"relatedParty":[{"type":"Party","id":"abc","status":"Faulted","characteristic":[
            {"name":"msisdn","value":"79161234567"},{"name":"category","value":"productOrdering"},{"name":"db_last_upd","value":"2026-10-28T10:00:00.000+0300"},
            {"name":"error_code","value":"E1"},{"name":"error_description","value":"Услуга несовместима с тарифом"}]}]}""", "abc").single()
        assertTrue(st.failed)
        assertEquals("Отклонено", st.title)
        assertEquals("Услуга несовместима с тарифом", st.errorDescription)
    }

    @Test fun statementFindsEvents() {
        val e = LineParse.statement("""{"items":[{"date":"2026-10-27T10:00:00+03:00","type":"network","amount":1.5,"label":"Исходящий звонок",
            "Characteristics":{"calledMsisdn":"79990001122","direction":"out","factUnits":"60","factUnitsCode":"сек"}},
            {"date":"2026-10-28T10:00:00+03:00","type":"periodical","amount":10,"label":"Абонентская плата"}]}""")
        assertEquals(2, e.size)
        assertEquals("Абонентская плата", e[0].label)
        assertEquals("79990001122", e[1].party)
        assertEquals("60 сек", e[1].units)
    }

    @Test fun clientBuildsRequests() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val http = Http { method, url, _, body ->
            calls += Triple(method, url, body)
            when {
                url.endsWith("/token") -> HttpResponse(200, """{"access_token":"t","expires_in":3600}""")
                "CheckCharges" in url -> HttpResponse(200, """[{"id":"79161234567","remainedAmount":{"amount":10}},{"id":"79161234568","remainedAmount":{"amount":20}}]""")
                "ModifyProduct" in url -> HttpResponse(200, """{"eventID":"ev-1","eventTime":"2026-10-28T00:00:00"}""")
                "PromisedPayment" in url -> HttpResponse(200, """{"code":"200","message":"Success"}""", mapOf("x-trace-id" to "tr-9"))
                else -> HttpResponse(200, "[]")
            }
        }
        val api = LineApi(MtsClient(MtsConfig("l", "p"), RateLimiter(clock = FakeClock()), http, FakeClock()))
        assertEquals(mapOf("79161234567" to 10.0, "79161234568" to 20.0), api.charges(listOf("79161234567", "79161234568")))
        assertTrue(calls.last().second.endsWith("/b2b/v1/Bills/CheckCharges?isBulk=true"))
        assertEquals("""[{"id":"79161234567"},{"id":"79161234568"}]""", calls.last().third)

        api.services("79161234567")
        val u = calls.last().second
        assertTrue("productOffering.productSpecification.productSpecificationType.name=service" in u, u)
        assertTrue("productOffering.actionAllowed=none" in u && "fields=CalculatePrices" in u, u)

        assertEquals("ev-1", api.validateService("79161234567", "PE0001").id)
        assertEquals("PATCH", calls.last().first)
        assertTrue("ValidateMobileConnectivity" in calls.last().third!!)
        api.modifyProduct("79161234567", LineApi.VOLUNTARY_BLOCK, add = false)
        assertEquals("POST", calls.last().first)
        assertTrue(calls.last().second.endsWith("/Product/ModifyProduct?msisdn=79161234567"))
        val body = Json.parse(calls.last().third!!)
        assertEquals("delete", body.field("item").list()[0].field("action"))
        assertEquals("BL0005", body.field("item").list()[0].path("product", "externalID"))

        assertEquals("tr-9", api.setPromised("123456789012", 300).id)
        assertEquals("https://api.mts.ru/MobileAPI/1/Payment/PromisedPayment", calls.last().second)

        api.paymentsByAccount("123456789012", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        assertTrue(calls.last().second.endsWith("accountNumber=123456789012&dateFrom=2026-09-01T00%3A00%3A00Z&dateTo=2026-09-30T23%3A59%3A59Z"), calls.last().second)
    }

    @Test fun unauthorizedNumberIsNotRetriedWithNewToken() {
        var tokens = 0
        val http = Http { _, url, _, _ ->
            if (url.endsWith("/token")) { tokens++; HttpResponse(200, """{"access_token":"t","expires_in":3600}""") }
            else HttpResponse(401, """{"errorCode":"1014","errorMessage":"unauthorized"}""")
        }
        val api = LineApi(MtsClient(MtsConfig("l", "p"), RateLimiter(clock = FakeClock()), http, FakeClock()))
        runCatching { api.simType("79161234567") }
        assertEquals(1, tokens)
    }

    @Test fun money() {
        assertEquals("1 234,5 ₽".replace(",5", ",50"), Money.format(1234.5))
        assertEquals("120 ₽", Money.format(120.0))
    }
}
