package ru.mycheckin.scanner

import android.content.Context
import ru.mycheckin.passport.mts.HierarchyPage
import ru.mycheckin.passport.mts.MtsClient
import ru.mycheckin.passport.mts.PersonalDataApi
import ru.mycheckin.passport.mts.RateLimiter

/** Обращения к МТС для учёта SIM. Все вызываются в фоновом потоке. */
object SimActions {

    class Failure(message: String) : Exception(message)

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: RateLimiter.MonthlyLimitExceeded) {
        throw Failure("Исчерпан месячный лимит вызовов тарифа.")
    } catch (e: Failure) {
        throw e
    } catch (e: java.net.UnknownHostException) {
        throw Failure("Нет подключения к интернету.")
    } catch (e: java.net.SocketTimeoutException) {
        throw Failure("МТС не ответил вовремя. Попробуйте ещё раз.")
    } catch (e: Exception) {
        throw Failure(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Доступ, через который работаем с номером: тот, с которым пришёл его контракт.
     * Если контракт неизвестен (карточка добавлена вручную) и доступ один — берём его.
     */
    fun accountFor(context: Context, sim: SimRecord): MtsAccount {
        val accounts = MtsService.accounts(context).filter { it.isConfigured }
        if (accounts.isEmpty()) throw Failure("Не введены логин и пароль для интеграции МТС. Откройте «Настройки МТС».")
        val byContract = sim.contractId.takeIf { it.isNotEmpty() }
            ?.let { SimDb.get(context).contract(it)?.accountId }
            ?.let { id -> accounts.firstOrNull { it.id == id } }
        return byContract ?: accounts.singleOrNull()
            ?: throw Failure("Не понятно, через какой доступ МТС работать с этим номером. Выберите контракт в карточке " +
                "или загрузите номера из МТС, чтобы контракт определился сам.")
    }

    private fun client(context: Context, sim: SimRecord): MtsClient = MtsService.client(context, accountFor(context, sim))

    data class SyncReport(val result: SimDb.SyncResult, val errors: List<String>)

    /** Загрузить из МТС контракты, номера и SIM по всем доступам. */
    fun syncNumbers(context: Context, progress: (String, Int) -> Unit): SyncReport {
        val accounts = MtsService.accounts(context).filter { it.isConfigured }
        if (accounts.isEmpty()) throw Failure("Не введены логин и пароль для интеграции МТС. Откройте «Настройки МТС».")
        val contracts = LinkedHashMap<String, ru.mycheckin.passport.mts.ContractInfo>()
        val numbers = LinkedHashMap<String, ru.mycheckin.passport.mts.NumberInfo>()
        val accountByContract = HashMap<String, String>()
        val labels = HashMap<String, String>()
        val errors = mutableListOf<String>()
        for ((i, acc) in accounts.withIndex()) {
            val prefix = if (accounts.size > 1) "${acc.title.ifBlank { "Доступ ${i + 1}" }}: " else ""
            try {
                val h = guarded { MtsService.client(context, acc).fetchAllNumbers { t, n -> progress(prefix + t, numbers.size + n) } }
                h.contracts.forEach { contracts[it.id] = it; accountByContract[it.id] = acc.id }
                // Если у доступа один контракт, он сразу получает название доступа («Ростовский»).
                if (h.contracts.size == 1 && acc.title.isNotBlank()) labels[h.contracts[0].id] = acc.title
                h.numbers.forEach { numbers[it.msisdn] = it }
            } catch (e: Failure) {
                errors += prefix + e.message
            }
        }
        if (errors.size == accounts.size) throw Failure(errors.joinToString("\n"))
        val result = SimDb.get(context).applyHierarchy(
            HierarchyPage(contracts.values.toList(), numbers.values.toList(), false),
            accountByContract, labels, markMissing = errors.isEmpty(),
        )
        return SyncReport(result, errors)
    }

    /** Подтянуть данные владельца и статус подтверждения из МТС (заполняет только пустые поля). */
    fun loadOwner(context: Context, sim: SimRecord): SimRecord = guarded {
        val owner = client(context, sim).ownerInfo(sim.msisdn)
        val updated = sim.fillFrom(owner)
        if (sim.id != 0L) SimDb.get(context).save(updated)
        updated
    }

    /** Что не даст МТС принять данные — проверяем до отправки. */
    fun problems(sim: SimRecord): List<String> =
        PersonalDataApi.problems(sim.toPassport(), PersonalDataApi.normalizeMsisdn(sim.msisdn), sim.address)

    data class Sent(val sim: SimRecord, val accepted: Boolean, val message: String)

    /** Внести паспортные данные в МТС. Номер заявки сохраняется в карточке. */
    fun send(context: Context, sim: SimRecord): Sent = guarded {
        val r = client(context, sim).submitPersonalData(sim.toPassport(), sim.msisdn, sim.address.takeIf { !it.isEmpty })
        if (r.accepted) {
            val updated = sim.copy(messageId = r.messageId!!, opStatus = "InProgress", confirmation = "", sentAt = System.currentTimeMillis())
            val id = SimDb.get(context).save(updated)
            Sent(updated.copy(id = id), true,
                "Номер заявки: ${r.messageId}\n\nВладельцу номера придёт SMS от МТС: данные нужно подтвердить на Госуслугах " +
                    "(нужна подтверждённая учётная запись). Статус обновится по кнопке «Проверить».")
        } else {
            Sent(sim, false, listOfNotNull(r.errorDescription.ifBlank { null }, "Код: " + r.errorCode.ifBlank { "нет номера заявки" }).joinToString("\n"))
        }
    }

    data class Checked(val sim: SimRecord, val text: String)

    /** Статус заявки, а после её выполнения — подтверждение через Госуслуги. */
    fun check(context: Context, sim: SimRecord): Checked = guarded {
        val client = client(context, sim)
        val lines = mutableListOf<String>()
        var updated = sim
        if (sim.messageId.isNotEmpty() && sim.opStatus != "Completed") {
            val op = client.operationResult(sim.msisdn, sim.messageId)
            lines += "Заявка: ${op.title}"
            lines += op.errors.map { "• $it" }
            updated = updated.copy(opStatus = op.status.orEmpty())
        }
        if (sim.messageId.isEmpty() || updated.opStatus == "Completed") {
            val owner = client.ownerInfo(sim.msisdn)
            updated = updated.copy(confirmation = owner.confirmation.code.orEmpty())
            lines += "Госуслуги: ${owner.confirmation.description}"
        } else if (updated.opStatus == "InProgress") {
            lines += "МТС ещё обрабатывает заявку. Проверьте через несколько минут."
        }
        updated = updated.copy(checkedAt = System.currentTimeMillis())
        if (updated.id != 0L) SimDb.get(context).save(updated)
        Checked(updated, lines.joinToString("\n"))
    }
}
