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

    private fun values(): Map<String, String?> {
        val (l1, l2) = Mrz.build("Иванов", "Иван", "Иванович", "4601", "123456", 'M',
            LocalDate.of(1990, 3, 15), LocalDate.of(2010, 6, 20), "770-120")
        val p = Mrz.parse(l1, l2, LocalDate.of(2026, 10, 27)).apply { issuedBy = "ОТДЕЛОМ \"УФМС\"" }
        return Template.values(p, "8 (912) 345-67-89")
    }

    @Test fun templateRendersAndEscapes() {
        val v = values()
        assertEquals("79123456789", v["phone"])
        val body = Template.render("""{"n":"{{surname}}","d":"{{birth_date}}","by":"{{issued_by}}","p":"{{phone}}"}""", v)
        assertEquals("""{"n":"Иванов","d":"1990-03-15","by":"ОТДЕЛОМ \"УФМС\"","p":"79123456789"}""", body)
        assertTrue(Template.placeholders(MtsConfig.DEFAULT_TEMPLATE).all { it in Template.KEYS })
        assertFailsWith<IllegalArgumentException> { Template.render("{{unknown}}", v) }
    }

    @Test fun tokenIsCachedAndRefreshedOn401AndRetriedOn429() {
        val clock = FakeClock()
        val calls = mutableListOf<String>()
        var submitCount = 0
        val http = Http { method, url, headers, body ->
            calls += "$method $url"
            when {
                url.endsWith("/token") -> {
                    assertTrue(headers.getValue("Authorization").startsWith("Basic "))
                    assertEquals("grant_type=client_credentials", body)
                    HttpResponse(200, """{"access_token":"t${calls.size}","expires_in":3600}""")
                }
                else -> {
                    submitCount++
                    when (submitCount) {
                        1 -> HttpResponse(429, "", mapOf("Retry-After" to "2"))
                        2 -> HttpResponse(401, "")
                        else -> HttpResponse(200, """{"ok":true}""").also {
                            assertTrue(headers.getValue("Authorization").startsWith("Bearer t"))
                            assertTrue(body!!.contains("\"lastName\": \"Иванов\""))
                        }
                    }
                }
            }
        }
        val client = MtsClient(
            MtsConfig("key", "secret", submitUrl = "https://api.mts.ru/b2b/v1/test"),
            RateLimiter(clock = clock), http, clock,
        )
        assertEquals(200, client.submit(values()).code)
        assertEquals(2, calls.count { it.endsWith("/token") })   // первый + обновление после 401
        assertTrue(2000L in clock.sleeps)                          // пауза по Retry-After
        // Второй вызов использует кешированный токен.
        client.submit(values())
        assertEquals(2, calls.count { it.endsWith("/token") })
    }

    @Test fun rejectsPlainHttp() {
        val client = MtsClient(MtsConfig("k", "s", submitUrl = "http://example.com"), RateLimiter(), { _, _, _, _ -> HttpResponse(200, "") })
        assertFailsWith<IllegalArgumentException> { client.submit(values()) }
    }
}
