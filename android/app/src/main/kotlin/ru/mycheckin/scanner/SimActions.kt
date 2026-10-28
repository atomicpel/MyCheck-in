package ru.mycheckin.scanner

import android.content.Context
import ru.mycheckin.passport.mts.ContractInfo
import ru.mycheckin.passport.mts.HierarchyPage
import ru.mycheckin.passport.mts.MtsClient
import ru.mycheckin.passport.mts.NumberInfo
import ru.mycheckin.passport.mts.OperationResult
import ru.mycheckin.passport.mts.PersonalDataApi
import ru.mycheckin.passport.mts.RateLimiter

/**
 * Обращения к МТС для учёта SIM. Все вызываются в фоновом потоке.
 * Каждое действие — запись в [Journal] с подробностями и всеми HTTP-обменами.
 */
object SimActions {

    class Failure(message: String) : Exception(message)

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: RateLimiter.MonthlyLimitExceeded) {
        throw Failure("Исчерпан месячный лимит вызовов тарифа.")
    } catch (e: Failure) {
        throw e
    } catch (e: java.net.UnknownHostException) {
        throw Failure("Нет подключения к интернету (не найден сервер ${e.message ?: "api.mts.ru"}).")
    } catch (e: java.net.SocketTimeoutException) {
        throw Failure("МТС не ответил вовремя. Попробуйте ещё раз.")
    } catch (e: javax.net.ssl.SSLException) {
        throw Failure("Ошибка защищённого соединения с МТС: ${e.message}")
    } catch (e: Exception) {
        throw Failure(e.message ?: e.javaClass.simpleName)
    }

    private fun accountTitle(a: MtsAccount, index: Int = 0) = a.title.ifBlank { "Доступ ${index + 1}" }

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

    private fun client(context: Context, sim: SimRecord, op: Journal.Op): MtsClient {
        val acc = accountFor(context, sim)
        op.account(acc.title)
        op.line("Доступ: ${acc.title.ifBlank { "без названия" }}" + if (sim.contractId.isNotEmpty()) ", контракт ${sim.contractId}" else "")
        return MtsService.client(context, acc)
    }

    data class SyncReport(val result: SimDb.SyncResult, val errors: List<String>)

    /** Загрузить из МТС контракты, номера и SIM по всем доступам. */
    fun syncNumbers(context: Context, progress: (String, Int) -> Unit): SyncReport =
        Journal.op(context, LogCategory.SYNC, "Загрузка номеров и SIM из МТС") { op ->
            val accounts = MtsService.accounts(context).filter { it.isConfigured }
            if (accounts.isEmpty()) throw Failure("Не введены логин и пароль для интеграции МТС. Откройте «Настройки МТС».")
            op.line("Доступов: ${accounts.size} (${accounts.withIndex().joinToString { accountTitle(it.value, it.index) }})")
            val contracts = LinkedHashMap<String, ContractInfo>()
            val numbers = LinkedHashMap<String, NumberInfo>()
            val accountByContract = HashMap<String, String>()
            val labels = HashMap<String, String>()
            val errors = mutableListOf<String>()
            for ((i, acc) in accounts.withIndex()) {
                val title = accountTitle(acc, i)
                val prefix = if (accounts.size > 1) "$title: " else ""
                try {
                    val started = System.currentTimeMillis()
                    val h = guarded { MtsService.client(context, acc).fetchAllNumbers { t, n -> progress(prefix + t, numbers.size + n) } }
                    h.contracts.forEach { contracts[it.id] = it; accountByContract[it.id] = acc.id }
                    // Если у доступа один контракт, он сразу получает название доступа («Ростовский»).
                    if (h.contracts.size == 1 && acc.title.isNotBlank()) labels[h.contracts[0].id] = acc.title
                    h.numbers.forEach { numbers[it.msisdn] = it }
                    val noSim = h.numbers.count { it.iccid.isNullOrEmpty() }
                    op.line("✓ «$title»: контрактов ${h.contracts.size}, номеров ${h.numbers.size}" +
                        (if (noSim > 0) ", без номера SIM $noSim" else "") + " — за ${(System.currentTimeMillis() - started) / 1000.0} с")
                    for (c in h.contracts) {
                        val count = h.numbers.count { it.contractId == c.id }
                        op.line("   контракт ${c.id}" + (c.orgName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            (c.inn?.let { " · ИНН $it" } ?: "") + " — $count номеров")
                    }
                } catch (e: Failure) {
                    errors += prefix + e.message
                    op.line("✕ «$title»: ${e.message}")
                }
            }
            if (errors.size == accounts.size) throw Failure(errors.joinToString("\n"))
            val r = SimDb.get(context).applyHierarchy(
                HierarchyPage(contracts.values.toList(), numbers.values.toList(), false),
                accountByContract, labels, markMissing = errors.isEmpty(),
            )
            op.line("")
            op.line("Итог: всего ${r.total}, новых ${r.added}, обновлено ${r.updated}" + if (r.missing > 0) ", пропали из МТС ${r.missing}" else "")
            if (r.addedNumbers.isNotEmpty()) op.line("Новые номера: " + list(r.addedNumbers))
            if (r.missingNumbers.isNotEmpty()) op.line("Нет в МТС (помечены, не удалены): " + list(r.missingNumbers))
            if (r.changes.isNotEmpty()) { op.line("Изменения:"); r.changes.take(50).forEach { op.line("   $it") } }
            if (!errors.isEmpty()) op.line("Номера, пропавшие из МТС, не помечались: часть доступов не загрузилась.")
            if (errors.isEmpty()) op.ok("Загрузка из МТС: ${r.total} номеров, новых ${r.added}")
            else op.warn("Загрузка из МТС частично: ${r.total} номеров, ошибок ${errors.size}")
            SyncReport(r, errors)
        }

    private fun list(numbers: List<String>, max: Int = 30) =
        numbers.take(max).joinToString { SimFormat.phone(it) } + if (numbers.size > max) " и ещё ${numbers.size - max}" else ""

    /** Подтянуть данные владельца и статус подтверждения из МТС (заполняет только пустые поля). */
    fun loadOwner(context: Context, sim: SimRecord): SimRecord =
        Journal.op(context, LogCategory.OWNER, "Данные владельца из МТС", msisdn = sim.msisdn) { op ->
            guarded {
                val owner = client(context, sim, op).ownerInfo(sim.msisdn)
                val updated = sim.fillFrom(owner)
                op.line("В МТС: " + listOfNotNull(
                    owner.surname?.let { listOfNotNull(owner.surname, owner.name, owner.patronymic).joinToString(" ") } ?: "ФИО нет",
                    if (owner.hasPassport) "паспорт ${owner.series} ${owner.number}" else "паспорта нет",
                ).joinToString(", "))
                op.line("Подтверждение на Госуслугах: ${owner.confirmation.description}")
                val filled = changedFields(sim, updated)
                op.line(if (filled.isEmpty()) "В карточке ничего не изменилось" else "Заполнено: " + filled.joinToString())
                if (sim.id != 0L) SimDb.get(context).save(updated)
                if (owner.hasName || owner.hasPassport) op.ok("Данные владельца из МТС: ${updated.fullName.ifEmpty { "получены" }}")
                else op.warn("В МТС нет данных владельца")
                updated
            }
        }

    /** Что не даст МТС принять данные — проверяем до отправки. */
    fun problems(sim: SimRecord): List<String> =
        PersonalDataApi.problems(sim.toPassport(), PersonalDataApi.normalizeMsisdn(sim.msisdn), sim.address)

    data class Sent(val sim: SimRecord, val accepted: Boolean, val message: String)

    /** Внести паспортные данные в МТС. Номер заявки сохраняется в карточке. */
    fun send(context: Context, sim: SimRecord): Sent =
        Journal.op(context, LogCategory.SEND, "Внесение паспорта в МТС", msisdn = sim.msisdn) { op ->
            guarded {
                val c = client(context, sim, op)
                op.line("Владелец: ${sim.fullName}, ${if (sim.sex == 'F') "жен." else "муж."}, род. ${sim.birthDate?.format(SimFormat.DATE)}")
                op.line("Паспорт: ${SimFormat.passport(sim)}, выдан ${sim.issueDate?.format(SimFormat.DATE)}, код ${sim.divisionCode}")
                if (!sim.address.isEmpty) op.line("Адрес регистрации передаётся")
                val r = c.submitPersonalData(sim.toPassport(), sim.msisdn, sim.address.takeIf { !it.isEmpty })
                if (r.accepted) {
                    val updated = sim.copy(messageId = r.messageId!!, opStatus = "InProgress", confirmation = "", sentAt = System.currentTimeMillis())
                    val id = SimDb.get(context).save(updated)
                    op.line("Заявка принята, номер ${r.messageId}")
                    op.ok("Паспорт отправлен в МТС: заявка принята")
                    Sent(updated.copy(id = id), true,
                        "Номер заявки: ${r.messageId}\n\nВладельцу номера придёт SMS от МТС: данные нужно подтвердить на Госуслугах " +
                            "(нужна подтверждённая учётная запись). Статус обновится по кнопке «Проверить».")
                } else {
                    val text = listOfNotNull(r.errorDescription.ifBlank { null }, "Код: " + r.errorCode.ifBlank { "нет номера заявки" }).joinToString("\n")
                    op.line("МТС отклонил: $text")
                    op.fail("МТС отклонил внесение паспорта")
                    Sent(sim, false, text)
                }
            }
        }

    data class Checked(val sim: SimRecord, val text: String)

    /** Статус заявки, а после её выполнения — подтверждение через Госуслуги. */
    fun check(context: Context, sim: SimRecord): Checked =
        Journal.op(context, LogCategory.CHECK, "Проверка статуса", msisdn = sim.msisdn) { op ->
            guarded {
                val client = client(context, sim, op)
                val lines = mutableListOf<String>()
                var updated = sim
                if (sim.messageId.isNotEmpty() && sim.opStatus != "Completed") {
                    val res = client.operationResult(sim.msisdn, sim.messageId)
                    lines += "Заявка ${sim.messageId}: ${res.title}"
                    lines += res.errors.map { "• $it" }
                    updated = updated.copy(opStatus = res.status.orEmpty())
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
                lines.forEach(op::line)
                val st = SimStatus.of(updated)
                when (st) {
                    SimStatus.PROBLEM -> op.fail("Статус: ${st.label}")
                    SimStatus.CONFIRMED -> op.ok("Статус: ${st.label}")
                    else -> op.warn("Статус: ${st.label}")
                }
                Checked(updated, lines.joinToString("\n"))
            }
        }

    /** Проверка логина и пароля доступа (получение токена). */
    fun checkAccount(context: Context, account: MtsAccount) =
        Journal.op(context, LogCategory.SETTINGS, "Проверка доступа «${account.title}»", account = account.title) { op ->
            guarded { MtsService.client(context, account).token(force = true) }
            op.line("Логин и пароль приняты, токен получен")
            op.ok("Доступ «${account.title}»: подключение работает")
        }

    fun opTitle(code: String): String = OperationResult.OPERATION_TITLES[code] ?: code

    /** Какие поля карточки изменились — для журнала. */
    fun changedFields(a: SimRecord, b: SimRecord): List<String> = buildList {
        fun f(name: String, x: Any?, y: Any?) { if (x != y) add(name) }
        f("телефон", a.msisdn, b.msisdn); f("SIM (ICCID)", a.iccid, b.iccid); f("контракт", a.contractId, b.contractId)
        f("фамилия", a.surname, b.surname); f("имя", a.name, b.name); f("отчество", a.patronymic, b.patronymic)
        f("пол", a.sex, b.sex); f("дата рождения", a.birthDate, b.birthDate); f("место рождения", a.birthPlace, b.birthPlace)
        f("серия", a.series, b.series); f("номер паспорта", a.number, b.number); f("дата выдачи", a.issueDate, b.issueDate)
        f("код подразделения", a.divisionCode, b.divisionCode); f("кем выдан", a.issuedBy, b.issuedBy)
        f("адрес", a.address, b.address); f("примечание", a.notes, b.notes)
        f("фото паспорта", a.passportPhoto, b.passportPhoto); f("фото владельца", a.facePhoto, b.facePhoto)
    }
}
