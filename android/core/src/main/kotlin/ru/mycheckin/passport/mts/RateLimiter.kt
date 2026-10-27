package ru.mycheckin.passport.mts

/**
 * Ограничитель частоты вызовов API со скользящими окнами.
 *
 * Тариф: не больше 10 вызовов в секунду, 300 в минуту, ~12,5 млн в месяц.
 * По умолчанию держим запас (8/с, 280/мин), чтобы расхождение часов и окон
 * подсчёта на стороне сервера не приводило к ответам 429.
 *
 * [monthlyStore] хранит счётчик за месяц между запусками приложения.
 */
class RateLimiter(
    private val perSecond: Int = 8,
    private val perMinute: Int = 280,
    private val perMonth: Long = 12_500_000,
    private val monthlyStore: MonthlyStore = MonthlyStore.InMemory(),
    private val clock: Clock = Clock.System,
) {
    interface Clock {
        fun nowMillis(): Long
        fun sleep(millis: Long)

        object System : Clock {
            override fun nowMillis() = java.lang.System.currentTimeMillis()
            override fun sleep(millis: Long) = Thread.sleep(millis)
        }
    }

    /** Счётчик вызовов за календарный месяц (ключ — "2026-10"). */
    interface MonthlyStore {
        fun get(month: String): Long
        fun set(month: String, value: Long)

        class InMemory : MonthlyStore {
            private val map = mutableMapOf<String, Long>()
            override fun get(month: String) = map[month] ?: 0L
            override fun set(month: String, value: Long) { map[month] = value }
        }
    }

    class MonthlyLimitExceeded(val used: Long, val limit: Long) :
        IllegalStateException("Исчерпан месячный лимит вызовов API: $used из $limit")

    private val calls = ArrayDeque<Long>()  // время вызовов за последнюю минуту

    private fun month(now: Long): String {
        val d = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/Moscow"))
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    @Synchronized
    fun usedThisMonth(): Long = monthlyStore.get(month(clock.nowMillis()))

    val monthlyLimit: Long get() = perMonth

    /** Через сколько миллисекунд можно сделать следующий вызов (0 — сразу). */
    @Synchronized
    fun waitTime(): Long {
        val now = clock.nowMillis()
        while (calls.isNotEmpty() && calls.first() <= now - 60_000) calls.removeFirst()
        var wait = 0L
        if (calls.size >= perMinute) wait = maxOf(wait, calls.first() + 60_000 - now)
        val lastSecond = calls.filter { it > now - 1_000 }
        if (lastSecond.size >= perSecond) wait = maxOf(wait, lastSecond.first() + 1_000 - now)
        return wait
    }

    /** Дождаться разрешения и учесть вызов. Бросает [MonthlyLimitExceeded] при исчерпании месяца. */
    fun acquire() {
        while (true) {
            synchronized(this) {
                val now = clock.nowMillis()
                val m = month(now)
                val used = monthlyStore.get(m)
                if (used >= perMonth) throw MonthlyLimitExceeded(used, perMonth)
                if (waitTime() == 0L) {
                    calls.addLast(now)
                    monthlyStore.set(m, used + 1)
                    return
                }
            }
            clock.sleep(maxOf(1, waitTime()))
        }
    }
}
