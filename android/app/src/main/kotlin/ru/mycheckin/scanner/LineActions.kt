package ru.mycheckin.scanner

import android.content.Context
import ru.mycheckin.passport.mts.AvailableTariff
import ru.mycheckin.passport.mts.EventAck
import ru.mycheckin.passport.mts.Forwarding
import ru.mycheckin.passport.mts.LineApi
import ru.mycheckin.passport.mts.LineChange
import ru.mycheckin.passport.mts.LineSection
import ru.mycheckin.passport.mts.Json
import ru.mycheckin.passport.mts.Money
import ru.mycheckin.passport.mts.MtsApiException
import ru.mycheckin.passport.mts.Payment
import ru.mycheckin.passport.mts.ProductItem
import ru.mycheckin.passport.mts.PromisedPayment
import ru.mycheckin.passport.mts.RateLimiter
import ru.mycheckin.passport.mts.RequestStatus
import ru.mycheckin.passport.mts.StatementEvent
import java.time.LocalDate

/**
 * Изменения и запросы по номеру через МТС Бизнес API. Все вызываются не в главном потоке.
 * Каждое изменение — запись журнала со всеми обращениями к МТС; заявка с номером (EventID)
 * сохраняется и проверяется в фоне, после выполнения затронутые сведения перечитываются.
 */
object LineActions {

    private fun <T> act(context: Context, category: LogCategory, title: String, msisdn: String, block: (LineApi, MtsAccount, Journal.Op) -> T): T =
        try {
            Journal.op(context, category, title, msisdn = msisdn) { op ->
                val (api, acc) = LineSync.api(context, msisdn)
                op.account(acc.title)
                block(api, acc, op)
            }
        } catch (e: SimActions.Failure) {
            throw e
        } catch (e: RateLimiter.MonthlyLimitExceeded) {
            throw SimActions.Failure("Исчерпан месячный лимит вызовов тарифа.")
        } catch (e: java.net.UnknownHostException) {
            throw SimActions.Failure("Нет подключения к интернету.")
        } catch (e: java.net.SocketTimeoutException) {
            throw SimActions.Failure("МТС не ответил вовремя. Попробуйте ещё раз.")
        } catch (e: MtsApiException) {
            throw SimActions.Failure(e.message ?: "Ошибка МТС")
        } catch (e: Exception) {
            throw SimActions.Failure(e.message ?: e.javaClass.simpleName)
        }

    private fun accountNo(context: Context, msisdn: String) = SimDb.get(context).byMsisdn(msisdn)?.accountNo.orEmpty()

    /** Номера по доступам МТС: пакетные запросы идут через логин своего контракта. */
    fun groupByAccount(context: Context, msisdns: Collection<String>): List<List<String>> {
        val db = SimDb.get(context)
        return msisdns.groupBy { m -> runCatching { SimActions.accountFor(context, db.byMsisdn(m) ?: SimRecord(msisdn = m)).id }.getOrDefault("") }
            .values.toList()
    }

    private fun change(context: Context, msisdn: String, kind: LineChange, title: String, call: (LineApi) -> EventAck): EventAck =
        act(context, LogCategory.CHANGE, title, msisdn) { api, _, op ->
            val ack = call(api)
            op.line("Номер заявки: ${ack.id ?: "МТС не прислал"}")
            ack.message?.let { op.line("Ответ МТС: $it") }
            LineSync.track(context, msisdn, accountNo(context, msisdn), kind, title, ack.id)
            ack
        }

    // ---- Блокировки и услуги ----

    fun setVoluntaryBlock(context: Context, msisdn: String, on: Boolean) =
        change(context, msisdn, LineChange.BLOCK, if (on) "Добровольная блокировка: включить" else "Добровольная блокировка: снять") {
            it.modifyProduct(msisdn, LineApi.VOLUNTARY_BLOCK, add = on)
        }

    fun availableServices(context: Context, msisdn: String): List<ProductItem> =
        act(context, LogCategory.LINES, "Доступные услуги", msisdn) { api, _, _ -> api.availableServices(msisdn) }

    /**
     * Проверить возможность подключения: МТС регистрирует заявку-проверку, итог — по её статусу.
     * Ждём до [waitMs]; null — МТС ещё не ответил.
     */
    fun validateService(context: Context, msisdn: String, code: String, waitMs: Long = 20_000): RequestStatus? =
        act(context, LogCategory.CHANGE, "Проверка подключения услуги $code", msisdn) { api, _, op ->
            val ack = api.validateService(msisdn, code)
            val id = ack.id ?: return@act null
            op.line("Заявка-проверка: $id")
            val until = System.currentTimeMillis() + waitMs
            var st: RequestStatus? = null
            while (System.currentTimeMillis() < until) {
                Thread.sleep(3000)
                st = runCatching { api.requestStatus(msisdn, id) }.getOrNull()
                if (st?.done == true || st?.failed == true) break
            }
            op.line("Итог проверки: ${st?.title ?: "МТС ещё проверяет"}")
            st
        }

    fun addService(context: Context, msisdn: String, s: ProductItem) =
        change(context, msisdn, LineChange.SERVICE, "Подключить услугу «${s.name}»") { it.modifyProduct(msisdn, s.code, add = true) }

    fun removeService(context: Context, msisdn: String, s: ProductItem) =
        change(context, msisdn, LineChange.SERVICE, "Отключить услугу «${s.name}»") { it.modifyProduct(msisdn, s.code, add = false) }

    // ---- Тариф ----

    fun availableTariffs(context: Context, msisdn: String): List<AvailableTariff> =
        act(context, LogCategory.LINES, "Тарифы, на которые можно перейти", msisdn) { api, _, _ -> api.availableTariffs(msisdn) }

    fun validateTariff(context: Context, msisdn: String, t: AvailableTariff): EventAck =
        act(context, LogCategory.CHANGE, "Проверка смены тарифа на «${t.name}»", msisdn) { api, _, op ->
            api.validateTariff(msisdn, t.id).also { op.line("Ответ МТС: ${it.message ?: it.id ?: "—"}") }
        }

    fun changeTariff(context: Context, msisdn: String, t: AvailableTariff) =
        change(context, msisdn, LineChange.TARIFF, "Сменить тариф на «${t.name}»") { it.changeTariff(msisdn, t.id) }

    // ---- Переадресация ----

    fun setForwarding(context: Context, msisdn: String, rule: Forwarding, on: Boolean) =
        change(context, msisdn, LineChange.FORWARDING,
            (if (on) "Включить переадресацию" else "Отключить переадресацию") + " «${rule.title}» на ${SimFormat.phone(rule.address)}") {
            it.changeForwarding(msisdn, rule, on)
        }

    // ---- Обещанный платёж ----

    fun promisedParams(context: Context, msisdn: String, accountNo: String): PromisedPayment =
        act(context, LogCategory.LINES, "Обещанный платёж: условия", msisdn) { api, _, _ -> api.promised(accountNo) }

    fun setPromised(context: Context, msisdn: String, accountNo: String, amount: Int) =
        change(context, msisdn, LineChange.PROMISED, "Обещанный платёж ${Money.formatInt(amount)} на счёт $accountNo") {
            it.setPromised(accountNo, amount)
        }

    // ---- Комментарии ----

    fun setComment(context: Context, msisdns: List<String>, text: String) {
        for (group in groupByAccount(context, msisdns)) {
            act(context, LogCategory.CHANGE, if (text.isEmpty()) "Удалить комментарий на ${group.size} ном." else "Комментарий «$text» на ${group.size} ном.", group.first()) { api, _, op ->
                group.chunked(LineApi.BULK_COMMENTS).forEach { api.setComment(it, text) }
                op.line(group.joinToString(", "))
            }
        }
        LineStore.get(context).putAll(LineSection.COMMENT, msisdns.associateWith { m ->
            Json.write(if (text.isEmpty()) emptyList<Any>() else listOf(linkedMapOf("msisdn" to m, "comment" to text)))
        })
    }

    fun setAccountComment(context: Context, msisdn: String, accountNo: String, text: String) {
        act(context, LogCategory.CHANGE, "Комментарий лицевого счёта $accountNo", msisdn) { api, _, _ -> api.setAccountComment(listOf(accountNo), text) }
        LineStore.get(context).put(LineSection.ACCOUNT_COMMENT, accountNo,
            Json.write(if (text.isEmpty()) emptyList<Any>() else listOf(linkedMapOf("account" to accountNo, "comment" to text))))
    }

    // ---- Платежи и детализация (по запросу, не хранятся) ----

    fun payments(context: Context, msisdn: String, accountNo: String?, days: Long): List<Payment> =
        act(context, LogCategory.LINES, "История платежей за $days дн.", msisdn) { api, _, _ ->
            val to = LocalDate.now(); val from = to.minusDays(days)
            if (!accountNo.isNullOrEmpty()) api.paymentsByAccount(accountNo, from, to) else api.paymentsByMsisdn(msisdn, from, to)
        }

    fun statement(context: Context, msisdn: String, days: Long): List<StatementEvent> =
        act(context, LogCategory.LINES, "Детализация за $days дн.", msisdn) { api, _, _ ->
            val to = LocalDate.now(); api.statement(msisdn, to.minusDays(days - 1), to)
        }

    // ---- Документы на email ----

    fun orderCalls(context: Context, msisdns: List<String>, from: LocalDate, to: LocalDate, format: String, email: String): List<EventAck> =
        groupByAccount(context, msisdns).flatMap { it.chunked(DOC_CHUNK) }.map { part ->
            change(context, part.first(), LineChange.DOCUMENT, "Детализация ${part.size} ном. за ${fmt(from)}–${fmt(to)} ($format) на $email") {
                it.orderCallHistory(part, from, to, format, email)
            }
        }

    fun orderBill(context: Context, msisdn: String, from: LocalDate, to: LocalDate, format: String, email: String) =
        change(context, msisdn, LineChange.DOCUMENT, "Счёт за ${fmt(from)}–${fmt(to)} ($format) на $email") {
            it.orderClosedBill(msisdn, from, to, format, email)
        }

    fun orderBalanceReport(context: Context, msisdns: List<String>, email: String): List<EventAck> =
        groupByAccount(context, msisdns).flatMap { it.chunked(DOC_CHUNK) }.map { part ->
            change(context, part.first(), LineChange.DOCUMENT, "Отчёт по балансу (${part.size} ном.) на $email") {
                it.orderBalanceReport(part, "pdf", email)
            }
        }

    /** Номеров в одном заказе документа (с запасом к ограничениям МТС). */
    private const val DOC_CHUNK = 100

    private fun fmt(d: LocalDate) = d.format(SimFormat.DATE)
}

/** Сумма без копеек: 1 500 ₽. */
fun Money.formatInt(v: Int): String = format(v.toDouble())
