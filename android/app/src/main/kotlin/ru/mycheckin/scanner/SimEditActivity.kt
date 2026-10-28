package ru.mycheckin.scanner

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import ru.mycheckin.passport.Level
import ru.mycheckin.passport.PassportData
import ru.mycheckin.passport.Validation
import ru.mycheckin.passport.mts.ConfirmationStatus
import ru.mycheckin.passport.mts.RegAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.Executors

/**
 * Карточка SIM-карты: номер, ICCID, контракт, паспорт владельца (скан камерой или из галереи),
 * внесение данных в МТС и проверка статуса.
 */
class SimEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "sim_id"
        const val EXTRA_CONTRACT = "contract"
        /** Новая карточка с уже известным номером или ICCID (например, после сканирования в поиске). */
        const val EXTRA_PHONE = "phone"
        const val EXTRA_ICCID = "iccid"
        private val RU = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    }

    private val db by lazy { SimDb.get(this) }
    private val io = Executors.newSingleThreadExecutor()

    /** Карточка, как она сохранена в базе (для «есть несохранённые изменения»). */
    private var saved: SimRecord? = null
    /** Текущая карточка: поля МТС, фото и статусы (поля формы — в виджетах). */
    private var sim = SimRecord(msisdn = "")
    private var contractIds: List<String> = emptyList()
    private var busyNow = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var busy: LinearProgressIndicator
    private lateinit var scroll: NestedScrollView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var btnCheck: MaterialButton
    private lateinit var contract: MaterialAutoCompleteTextView
    private lateinit var mtsInfo: TextView
    private lateinit var photoBox: View
    private lateinit var passportPhoto: ShapeableImageView
    private lateinit var facePhoto: ShapeableImageView
    private lateinit var scanNote: TextView
    private lateinit var sex: MaterialButtonToggleGroup
    private lateinit var addressBox: View
    private lateinit var btnSend: MaterialButton
    private lateinit var btnSave: MaterialButton

    private fun edit(id: Int): EditText = findViewById(id)
    private fun layout(id: Int): TextInputLayout = findViewById(id)
    private val phone by lazy { edit(R.id.phone) }
    private val iccid by lazy { edit(R.id.iccid) }
    private val surname by lazy { edit(R.id.surname) }
    private val name by lazy { edit(R.id.name) }
    private val patronymic by lazy { edit(R.id.patronymic) }
    private val birthDate by lazy { edit(R.id.birth_date) }
    private val issueDate by lazy { edit(R.id.issue_date) }
    private val birthPlace by lazy { edit(R.id.birth_place) }
    private val series by lazy { edit(R.id.series) }
    private val number by lazy { edit(R.id.number) }
    private val divisionCode by lazy { edit(R.id.division_code) }
    private val issuedBy by lazy { edit(R.id.issued_by) }
    private val addrRegion by lazy { edit(R.id.addr_region) }
    private val addrCity by lazy { edit(R.id.addr_city) }
    private val addrStreet by lazy { edit(R.id.addr_street) }
    private val addrHouse by lazy { edit(R.id.addr_house) }
    private val addrApartment by lazy { edit(R.id.addr_apartment) }
    private val addrZip by lazy { edit(R.id.addr_zip) }
    private val notes by lazy { edit(R.id.notes) }

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) applyScan()
    }

    /** Сканер кода SIM для полей «Номер телефона» и «ICCID». */
    private val scanField = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val value = data.getStringExtra(CodeScanActivity.RESULT_VALUE).orEmpty()
        val field = if (data.getStringExtra(CodeScanActivity.RESULT_KIND) == "PHONE") phone else iccid
        field.setText(if (field === phone) SimFormat.phone(value) else value)
        flash(field)
        val other = data.getLongExtra(CodeScanActivity.RESULT_SIM_ID, 0L)
        if (other != 0L && other != sim.id) Snackbar.make(scroll, "Эта SIM уже есть в списке", Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.bottom_bar)
            .setAction("Открыть") { openScreen(Intent(this, SimEditActivity::class.java).putExtra(EXTRA_ID, other)) }.show()
    }

    private fun setupFieldScanner(layoutId: Int, target: String) {
        val l = layout(layoutId)
        l.endIconMode = TextInputLayout.END_ICON_CUSTOM
        l.setEndIconDrawable(R.drawable.ic_scan_code)
        l.endIconContentDescription = "Сканировать"
        l.setEndIconOnClickListener {
            scanField.launch(Intent(this, CodeScanActivity::class.java).putExtra(CodeScanActivity.EXTRA_TARGET, target))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sim_edit)
        toolbar = findViewById(R.id.toolbar)
        busy = findViewById(R.id.busy)
        scroll = findViewById(R.id.scroll)
        statusCard = findViewById(R.id.status_card)
        statusIcon = findViewById(R.id.status_icon)
        statusTitle = findViewById(R.id.status_title)
        statusText = findViewById(R.id.status_text)
        btnCheck = findViewById(R.id.btn_check)
        contract = findViewById(R.id.contract)
        mtsInfo = findViewById(R.id.mts_info)
        photoBox = findViewById(R.id.photo_box)
        passportPhoto = findViewById(R.id.passport_photo)
        facePhoto = findViewById(R.id.face_photo)
        scanNote = findViewById(R.id.scan_note)
        sex = findViewById(R.id.sex)
        addressBox = findViewById(R.id.address_box)
        btnSend = findViewById(R.id.btn_send)
        btnSave = findViewById(R.id.btn_save)

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_load_owner -> loadOwner()
                R.id.action_delete -> askDelete()
                R.id.action_journal -> openJournal()
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        setupFieldScanner(R.id.phone_layout, CodeScanActivity.TARGET_PHONE)
        setupFieldScanner(R.id.iccid_layout, CodeScanActivity.TARGET_ICCID)
        DateMask.attach(birthDate); DateMask.attach(issueDate)
        DivisionMask.attach(divisionCode)
        layout(R.id.birth_date_layout).setEndIconOnClickListener { pickDate(birthDate, "Дата рождения") }
        layout(R.id.issue_date_layout).setEndIconOnClickListener { pickDate(issueDate, "Дата выдачи") }

        findViewById<View>(R.id.btn_scan).setOnClickListener { scan.launch(Intent(this, ScanActivity::class.java)) }
        findViewById<View>(R.id.btn_gallery).setOnClickListener {
            scan.launch(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_GALLERY, true))
        }
        findViewById<View>(R.id.address_header).setOnClickListener { toggleAddress() }
        btnCheck.setOnClickListener { checkStatus() }
        btnSave.setOnClickListener { save(close = true) }
        btnSend.setOnClickListener { askSend() }
        passportPhoto.setOnClickListener { showPhoto() }

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val preContract = intent.getStringExtra(EXTRA_CONTRACT)
        scroll.alpha = 0f
        io.execute {
            val record = if (id != 0L) db.byId(id) else null
            val contracts = db.contracts()
            val bitmaps = record?.let { SimPhotos.load(it.passportPhoto) to SimPhotos.load(it.facePhoto, 400) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setupContracts(contracts)
                val r = record ?: SimRecord(
                    msisdn = intent.getStringExtra(EXTRA_PHONE).orEmpty(),
                    iccid = intent.getStringExtra(EXTRA_ICCID).orEmpty(),
                    contractId = preContract.orEmpty(),
                )
                saved = record
                sim = r
                bind(r)
                bitmaps?.let { (p, f) -> showPhotos(p, f, animate = false) }
                toolbar.title = if (record == null) "Новая SIM-карта" else "SIM-карта"
                toolbar.menu.findItem(R.id.action_delete)?.isVisible = record != null
                scroll.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    override fun finish() {
        super.finish()
        closeScreenAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- Привязка полей ----

    private fun setupContracts(list: List<Contract>) {
        contractIds = listOf("") + list.map { it.id }
        val titles = listOf("Без контракта") + list.map { "${it.title} (${it.id})" }
        contract.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, titles))
    }

    private fun setContract(id: String) {
        val i = contractIds.indexOf(id).coerceAtLeast(0)
        contract.setText(contract.adapter.getItem(i).toString(), false)
        contract.tag = contractIds.getOrElse(i) { "" }
        contract.setOnItemClickListener { _, _, pos, _ -> contract.tag = contractIds[pos] }
    }

    private fun bind(s: SimRecord) {
        setContract(s.contractId)
        phone.setText(if (s.msisdn.isNotEmpty()) SimFormat.phone(s.msisdn) else "")
        iccid.setText(s.iccid)
        bindPassport(s.toPassport(), overwrite = true)
        addrRegion.setText(s.address.region); addrCity.setText(s.address.city); addrStreet.setText(s.address.street)
        addrHouse.setText(s.address.house); addrApartment.setText(s.address.apartment); addrZip.setText(s.address.zip)
        if (!s.address.isEmpty) toggleAddress(open = true, animate = false)
        notes.setText(s.notes)
        renderMtsInfo()
        renderStatus()
    }

    /**
     * Заполнить паспортные поля. [overwrite] — как есть (открытие карточки);
     * иначе (скан, данные МТС) непустые значения заменяют введённое, изменённые поля подсвечиваются.
     */
    private fun bindPassport(p: PassportData, overwrite: Boolean) {
        fun set(e: EditText, v: String?) = e.setText(v.orEmpty())
        fun putAlways(e: EditText, v: String?) {
            val value = v.orEmpty()
            if (value.isEmpty() || e.text.toString() == value) return
            e.setText(value)
            flash(e)
        }
        if (overwrite) {
            set(surname, p.surname); set(name, p.name); set(patronymic, p.patronymic)
            set(birthDate, p.birthDate?.format(RU)); set(birthPlace, p.birthPlace)
            set(series, p.series); set(number, p.number); set(issueDate, p.issueDate?.format(RU))
            set(divisionCode, p.divisionCode); set(issuedBy, p.issuedBy)
            when (p.sex) { 'M' -> sex.check(R.id.sex_m); 'F' -> sex.check(R.id.sex_f); else -> sex.clearChecked() }
        } else {
            // Скан: распознанное заменяет введённое (кроме пустых значений).
            putAlways(surname, p.surname); putAlways(name, p.name); putAlways(patronymic, p.patronymic)
            putAlways(birthDate, p.birthDate?.format(RU)); putAlways(birthPlace, p.birthPlace)
            putAlways(series, p.series); putAlways(number, p.number); putAlways(issueDate, p.issueDate?.format(RU))
            putAlways(divisionCode, p.divisionCode); putAlways(issuedBy, p.issuedBy)
            when (p.sex) { 'M' -> sex.check(R.id.sex_m); 'F' -> sex.check(R.id.sex_f) }
        }
    }

    private fun flash(v: View) {
        v.animate().cancel()
        v.alpha = 0.25f
        v.animate().alpha(1f).setDuration(450).start()
    }

    private fun parseDate(e: EditText): LocalDate? = try {
        LocalDate.parse(e.text.toString().trim(), RU)
    } catch (_: Exception) {
        null
    }

    private fun msisdnOf(text: String): String? {
        val d = text.filter { it.isDigit() }
        return when {
            d.length == 10 -> "7$d"
            d.length == 11 && (d[0] == '7' || d[0] == '8') -> "7" + d.drop(1)
            else -> null
        }
    }

    /** Собрать карточку из полей. */
    private fun collect(): SimRecord = sim.copy(
        msisdn = msisdnOf(phone.text.toString()) ?: phone.text.toString().filter { it.isDigit() },
        iccid = iccid.text.toString().filter { it.isLetterOrDigit() },
        contractId = contract.tag as? String ?: "",
        surname = surname.text.toString().trim(),
        name = name.text.toString().trim(),
        patronymic = patronymic.text.toString().trim(),
        sex = when (sex.checkedButtonId) { R.id.sex_m -> 'M'; R.id.sex_f -> 'F'; else -> null },
        birthDate = parseDate(birthDate),
        birthPlace = birthPlace.text.toString().replace(Regex("\\s+"), " ").trim(),
        series = series.text.toString().filter { it.isDigit() },
        number = number.text.toString().filter { it.isDigit() },
        issueDate = parseDate(issueDate),
        divisionCode = divisionCode.text.toString().trim(),
        issuedBy = issuedBy.text.toString().replace(Regex("\\s+"), " ").trim(),
        address = RegAddress(
            region = addrRegion.text.toString().trim(), city = addrCity.text.toString().trim(),
            street = addrStreet.text.toString().trim(), house = addrHouse.text.toString().trim(),
            apartment = addrApartment.text.toString().trim(), zip = addrZip.text.toString().trim(),
        ),
        notes = notes.text.toString().trim(),
    )

    /** Проверка формата полей; ошибки показываются под полями. */
    private fun validate(forSend: Boolean): Boolean {
        val all = listOf(R.id.phone_layout, R.id.iccid_layout, R.id.surname_layout, R.id.name_layout, R.id.birth_date_layout,
            R.id.issue_date_layout, R.id.series_layout, R.id.number_layout, R.id.division_code_layout, R.id.issued_by_layout,
            R.id.birth_place_layout, R.id.addr_zip_layout)
        all.forEach { layout(it).error = null }
        fun err(id: Int, msg: String) { if (layout(id).error == null) layout(id).error = msg }
        if (msisdnOf(phone.text.toString()) == null) err(R.id.phone_layout, "Номер из 11 цифр: +7 XXX XXX-XX-XX")
        val ic = iccid.text.toString().filter { it.isLetterOrDigit() }
        if (ic.isNotEmpty() && ic.length !in 18..22) err(R.id.iccid_layout, "ICCID — обычно 19–20 цифр")
        fun date(e: EditText, id: Int) { if (e.text.isNotBlank() && parseDate(e) == null) err(id, "Формат ДД.ММ.ГГГГ") }
        date(birthDate, R.id.birth_date_layout); date(issueDate, R.id.issue_date_layout)
        val ser = series.text.toString().filter { it.isDigit() }
        if (ser.isNotEmpty() && ser.length != 4) err(R.id.series_layout, "4 цифры")
        val num = number.text.toString().filter { it.isDigit() }
        if (num.isNotEmpty() && num.length != 6) err(R.id.number_layout, "6 цифр")
        val code = divisionCode.text.toString().trim()
        if (code.isNotEmpty() && !Regex("\\d{3}-\\d{3}").matches(code)) err(R.id.division_code_layout, "Формат 000-000")
        if (issuedBy.text.length > 256) err(R.id.issued_by_layout, "Не длиннее 256 знаков")
        val zip = addrZip.text.toString().trim()
        if (zip.isNotEmpty() && !Regex("\\d{6}").matches(zip)) err(R.id.addr_zip_layout, "6 цифр")
        if (forSend) {
            fun need(e: EditText, id: Int) { if (e.text.isBlank()) err(id, "Нужно для МТС") }
            need(surname, R.id.surname_layout); need(name, R.id.name_layout); need(birthDate, R.id.birth_date_layout)
            need(series, R.id.series_layout); need(number, R.id.number_layout); need(issueDate, R.id.issue_date_layout)
            need(divisionCode, R.id.division_code_layout); need(issuedBy, R.id.issued_by_layout)
        }
        val bad = all.firstOrNull { layout(it).error != null }
        if (bad != null) {
            val v = layout(bad)
            scroll.post { scroll.smoothScrollTo(0, (v.top + (v.parent as View).top - 40).coerceAtLeast(0)) }
            v.animate().translationX(14f).setDuration(60).withEndAction {
                v.animate().translationX(-10f).setDuration(60).withEndAction { v.animate().translationX(0f).setDuration(80).start() }.start()
            }.start()
            return false
        }
        if (forSend && sex.checkedButtonId == View.NO_ID) {
            Snackbar.make(scroll, "Укажите пол", Snackbar.LENGTH_SHORT).setAnchorView(R.id.bottom_bar).show()
            return false
        }
        return true
    }

    // ---- Сохранение ----

    private fun save(close: Boolean, then: ((SimRecord) -> Unit)? = null) {
        if (busyNow || !validate(forSend = false)) return
        val record = collect()
        setBusy(true)
        io.execute {
            val outcome = runCatching { record.copy(id = db.save(record)) }
            outcome.onSuccess { VaultSync.syncSoon(this, force = true) }
            outcome.onSuccess { r ->
                val before = saved
                val changes = if (before == null) emptyList() else SimActions.changedFields(before, r)
                if (before == null || changes.isNotEmpty()) Journal.event(this, LogLevel.OK, LogCategory.CARD,
                    if (before == null) "Создана карточка SIM" else "Карточка сохранена",
                    listOfNotNull(
                        r.fullName.ifEmpty { null }?.let { "Владелец: $it" },
                        r.iccid.ifEmpty { null }?.let { "SIM: $it" },
                        if (changes.isNotEmpty()) "Изменено: " + changes.joinToString() else null,
                        "Хранится только на этом телефоне; в МТС не отправлялось",
                    ).joinToString("\n"), msisdn = r.msisdn)
                // Фото заменили сканом — прежние файлы больше не нужны.
                saved?.let { old ->
                    if (old.passportPhoto != r.passportPhoto) SimPhotos.delete(old.passportPhoto)
                    if (old.facePhoto != r.facePhoto) SimPhotos.delete(old.facePhoto)
                }
            }
            runOnUiThread {
                setBusy(false)
                outcome.onSuccess { r ->
                    saved = r; sim = r
                    toolbar.menu.findItem(R.id.action_delete)?.isVisible = true
                    toolbar.title = "SIM-карта"
                    if (close) finish() else then?.invoke(r)
                }
                outcome.onFailure { e ->
                    if (e is IllegalStateException) layout(R.id.phone_layout).error = e.message
                    else error("Не удалось сохранить", e.message)
                }
            }
        }
    }

    private fun isDirty(): Boolean {
        val now = collect()
        val base = saved ?: SimRecord(
            msisdn = intent.getStringExtra(EXTRA_PHONE).orEmpty(),
            iccid = intent.getStringExtra(EXTRA_ICCID).orEmpty(),
            contractId = intent.getStringExtra(EXTRA_CONTRACT).orEmpty(),
        )
        return now.copy(updatedAt = 0) != base.copy(updatedAt = 0) && !(saved == null && now.msisdn.isEmpty() && now.fullName.isEmpty() && now.iccid.isEmpty())
    }

    private fun leave() {
        if (!isDirty()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Сохранить изменения?")
            .setPositiveButton("Сохранить") { _, _ -> save(close = true) }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun askDelete() {
        val s = saved ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить карточку ${SimFormat.phone(s.msisdn)}?")
            .setMessage("Удалятся паспортные данные и фото на этом телефоне. В МТС ничего не изменится. " +
                if (s.inMts) "При следующей загрузке из МТС номер появится снова, без паспорта." else "")
            .setPositiveButton("Удалить") { _, _ ->
                io.execute {
                    db.delete(s.id); SimPhotos.delete(s.passportPhoto); SimPhotos.delete(s.facePhoto)
                    Journal.event(this, LogLevel.WARN, LogCategory.CARD, "Карточка удалена",
                        listOfNotNull(s.fullName.ifEmpty { null }?.let { "Владелец: $it" }, "Удалены паспортные данные и фото на телефоне; в МТС ничего не менялось")
                            .joinToString("\n"), msisdn = s.msisdn)
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- Скан паспорта ----

    private fun applyScan() {
        val p = ScanSession.passport ?: return
        val spread = ScanSession.spread
        val face = ScanSession.photo
        bindPassport(p, overwrite = false)
        val notesList = p.issues.filter { it.level != Level.INFO }.map { (if (it.level == Level.ERROR) "⛔ " else "⚠️ ") + it.message }
        val checks = if (p.checks.isNotEmpty()) "Контрольные суммы MRZ: ${p.checks.values.count { it }} из ${p.checks.size}" else null
        val ok = p.allChecksPassed && notesList.isEmpty()
        val noMrz = p.source == "viz"
        scanNote.text = (listOfNotNull(
            when {
                noMrz -> "Паспорт без полосы MRZ: все поля прочитаны с фото без контрольных сумм — сверьте каждое поле с паспортом"
                ok -> "✓ Паспорт распознан, контрольные суммы сошлись"
                else -> "Паспорт распознан — проверьте поля"
            },
            checks,
            "«Кем выдан» и место рождения прочитаны с фото — сверьте с паспортом",
        ) + notesList).joinToString("\n")
        scanNote.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn))
        scanNote.visibility = View.VISIBLE
        val phoneNow = msisdnOf(phone.text.toString()).orEmpty()
        io.execute {
            Journal.event(this, if (ok) LogLevel.OK else LogLevel.WARN, LogCategory.SCAN,
                if (ok) "Паспорт распознан" else "Паспорт распознан с замечаниями",
                (listOfNotNull(
                    "Источник: " + when (p.source) { "mrz+viz" -> "MRZ и текст разворота"; "viz" -> "только текст разворота"; else -> "MRZ" },
                    checks,
                    listOfNotNull(p.surname, p.name, p.patronymic).joinToString(" ").ifEmpty { null }?.let { "ФИО: $it" },
                    p.seriesNumber?.let { "Паспорт: $it" },
                    if (p.corrections.isNotEmpty()) "Исправлено автоматически: " + p.corrections.joinToString("; ") else null,
                    if (spread != null) "Фото разворота сохранено" else null,
                    if (face != null) "Фото владельца найдено" else "Лицо на фото не найдено",
                ) + notesList).joinToString("\n"), msisdn = phoneNow)
        }
        showPhotos(spread, face, animate = true)
        io.execute {
            val old = sim
            val passportPath = spread?.let { SimPhotos.save(this, it, "passport") }
            val facePath = face?.let { SimPhotos.save(this, it, "face") }
            runOnUiThread {
                // Старые фото удаляются, только если карточку сохранят (иначе вернёмся к ним).
                sim = sim.copy(passportPhoto = passportPath ?: old.passportPhoto, facePhoto = facePath ?: old.facePhoto)
            }
        }
        ScanSession.clear()
    }

    private fun showPhotos(passport: android.graphics.Bitmap?, face: android.graphics.Bitmap?, animate: Boolean) {
        if (passport == null && face == null) return
        passport?.let { passportPhoto.setImageBitmap(it) }
        if (face != null) { facePhoto.setImageBitmap(face); facePhoto.visibility = View.VISIBLE }
        if (passport == null) passportPhoto.setImageBitmap(face)
        photoBox.visibility = View.VISIBLE
        if (animate) {
            photoBox.alpha = 0f; photoBox.scaleX = 0.96f; photoBox.scaleY = 0.96f
            photoBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        }
    }

    private fun showPhoto() {
        val d = passportPhoto.drawable ?: return
        val img = ImageView(this).apply { setImageDrawable(d); adjustViewBounds = true }
        MaterialAlertDialogBuilder(this).setView(img).setPositiveButton("Закрыть", null).show()
    }

    // ---- МТС ----

    private fun renderMtsInfo() {
        val s = sim
        val parts = buildList {
            if (s.accountNo.isNotEmpty()) add("Лицевой счёт ${s.accountNo}")
            if (s.region.isNotEmpty()) add(s.region)
            if (s.contractId.isNotEmpty() && !s.inMts) add("номера нет в последней загрузке из МТС")
        }
        mtsInfo.text = parts.joinToString(" · ")
        mtsInfo.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderStatus() {
        val s = sim
        val st = SimStatus.of(s)
        val color = ContextCompat.getColor(this, st.color)
        statusCard.strokeColor = color
        statusIcon.imageTintList = ColorStateList.valueOf(color)
        statusIcon.setImageResource(when (st) {
            SimStatus.CONFIRMED, SimStatus.DONE, SimStatus.IN_MTS -> R.drawable.ic_check
            SimStatus.SENT, SimStatus.WAITING -> R.drawable.ic_send
            SimStatus.PROBLEM -> R.drawable.ic_close
            else -> R.drawable.ic_badge
        })
        statusTitle.text = st.label
        statusTitle.setTextColor(color)
        statusText.text = buildList {
            when (st) {
                SimStatus.NO_PASSPORT -> add("Отсканируйте паспорт владельца или введите данные вручную, затем нажмите «Внести в МТС».")
                SimStatus.READY -> add("Паспорт заполнен, в МТС ещё не отправлялся.")
                SimStatus.NEEDED -> add("В МТС нет данных пользователя этого номера — их нужно внести.")
                SimStatus.IN_MTS -> add("Данные пользователя уже есть в МТС (загружены оттуда). Вносить заново не нужно, если они верны.")
                SimStatus.DONE -> add("МТС выполнил заявку: данные внесены.")
                else -> Unit
            }
            if (s.messageId.isNotEmpty()) {
                add("Заявка ${s.messageId}" + if (s.sentAt > 0) " от ${SimFormat.time(s.sentAt)}" else "")
                if (s.opStatus.isNotEmpty()) add("Заявка: " + (ru.mycheckin.passport.mts.OperationResult.OPERATION_TITLES[s.opStatus] ?: s.opStatus))
            }
            if (s.confirmation.isNotEmpty()) add("Госуслуги: " + ConfirmationStatus(s.confirmation).description)
            if (s.checkedAt > 0) add("Проверено ${SimFormat.time(s.checkedAt)}")
        }.joinToString("\n")
        btnCheck.visibility = if (s.id != 0L && (s.messageId.isNotEmpty() || s.inMts)) View.VISIBLE else View.GONE
        btnSend.text = if (s.messageId.isNotEmpty()) "Внести заново" else "Внести в МТС"
    }

    private fun setBusy(on: Boolean) {
        busyNow = on
        busy.visibility = if (on) View.VISIBLE else View.GONE
        btnSend.isEnabled = !on; btnSave.isEnabled = !on; btnCheck.isEnabled = !on
    }

    private fun checkStatus() {
        if (busyNow) return
        val s = saved ?: return
        setBusy(true)
        io.execute {
            val outcome = runCatching { SimActions.check(this, s.copy(contractId = contract.tag as? String ?: s.contractId)) }
            runOnUiThread {
                setBusy(false)
                outcome.onSuccess { c ->
                    sim = sim.copy(opStatus = c.sim.opStatus, confirmation = c.sim.confirmation, checkedAt = c.sim.checkedAt)
                    saved = saved?.copy(opStatus = c.sim.opStatus, confirmation = c.sim.confirmation, checkedAt = c.sim.checkedAt)
                    animateStatus()
                    Snackbar.make(scroll, c.text.lineSequence().first(), Snackbar.LENGTH_LONG).setAnchorView(R.id.bottom_bar)
                        .setAction("Подробнее") { error("Статус", c.text) }.show()
                }
                outcome.onFailure { e -> error("Не удалось проверить", e.message) }
            }
        }
    }

    private fun animateStatus() {
        statusCard.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).withEndAction {
            renderStatus()
            statusCard.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
    }

    private fun loadOwner() {
        if (busyNow) return
        if (msisdnOf(phone.text.toString()) == null) { validate(false); return }
        setBusy(true)
        val current = collect()
        io.execute {
            val outcome = runCatching { SimActions.loadOwner(this, current.copy(id = 0)) }
            runOnUiThread {
                setBusy(false)
                outcome.onSuccess { u ->
                    val before = current.toPassport()
                    bindPassport(u.toPassport(), overwrite = false)
                    if (current.address.isEmpty && !u.address.isEmpty) {
                        addrRegion.setText(u.address.region); addrCity.setText(u.address.city); addrStreet.setText(u.address.street)
                        addrHouse.setText(u.address.house); addrApartment.setText(u.address.apartment)
                        toggleAddress(open = true)
                    }
                    sim = sim.copy(confirmation = u.confirmation, checkedAt = u.checkedAt)
                    animateStatus()
                    val filled = u.toPassport().let { it.surname != before.surname || it.series != before.series }
                    Snackbar.make(scroll, if (filled) "Данные из МТС подставлены в пустые поля — сохраните карточку" else "В МТС нет новых данных по этому номеру",
                        Snackbar.LENGTH_LONG).setAnchorView(R.id.bottom_bar).show()
                }
                outcome.onFailure { e -> error("Не удалось получить данные", e.message) }
            }
        }
    }

    private fun askSend() {
        if (busyNow || !validate(forSend = true)) return
        val record = collect()
        val problems = SimActions.problems(record)
        if (problems.isNotEmpty()) {
            error("МТС не примет такие данные", problems.joinToString("\n") { "• $it" })
            return
        }
        val warnings = Validation.validate(record.toPassport()).issues.filter { it.level == Level.ERROR }
        val text = buildString {
            append("Номер: ${SimFormat.phone(record.msisdn)}\n")
            append("Владелец: ${record.fullName}\n")
            append("Паспорт: ${SimFormat.passport(record)}, выдан ${record.issueDate?.format(RU)}\n")
            if (warnings.isNotEmpty()) append("\nЗамечания проверки:\n" + warnings.joinToString("\n") { "• " + it.message } + "\n")
            if (record.messageId.isNotEmpty()) append("\nПо этому номеру уже была заявка ${record.messageId}. Будет отправлена новая.\n")
            append("\nПосле отправки владельцу придёт SMS: данные нужно подтвердить на Госуслугах.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Внести данные в МТС?")
            .setMessage(text)
            .setPositiveButton("Отправить") { _, _ -> save(close = false) { send(it) } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun send(record: SimRecord) {
        setBusy(true)
        btnSend.text = "Отправка…"
        io.execute {
            val outcome = runCatching { SimActions.send(this, record) }
            runOnUiThread {
                setBusy(false)
                outcome.onSuccess { r ->
                    if (r.accepted) { sim = r.sim; saved = r.sim }
                    animateStatus()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (r.accepted) "Заявка принята" else "МТС отклонил запрос")
                        .setMessage(r.message)
                        .setPositiveButton("OK", null)
                        .apply { if (r.accepted) setNeutralButton("Проверить статус") { _, _ -> checkStatus() } }
                        .show()
                }
                outcome.onFailure { e -> renderStatus(); error("Не удалось отправить", e.message) }
            }
        }
    }

    // ---- Мелочи интерфейса ----

    private fun toggleAddress(open: Boolean = addressBox.visibility != View.VISIBLE, animate: Boolean = true) {
        val chevron = findViewById<View>(R.id.address_chevron)
        addressBox.visibility = if (open) View.VISIBLE else View.GONE
        val target = if (open) 180f else 0f
        if (animate) chevron.animate().rotation(target).setDuration(220).start() else chevron.rotation = target
    }

    private fun pickDate(e: EditText, title: String) {
        val current = parseDate(e)
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .setInputMode(MaterialDatePicker.INPUT_MODE_CALENDAR)
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
            .apply { current?.let { setSelection(it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()) } }
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            e.setText(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().format(RU))
            flash(e)
        }
        picker.show(supportFragmentManager, title)
    }

    private fun openJournal() {
        val m = msisdnOf(phone.text.toString())
        openScreen(Intent(this, JournalActivity::class.java).apply { if (m != null) putExtra(JournalActivity.EXTRA_MSISDN, m) })
    }

    private fun error(title: String, text: String?) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text ?: "Неизвестная ошибка").setPositiveButton("OK", null)
            .setNeutralButton("Журнал") { _, _ -> openJournal() }.show()
    }
}


/** Маска даты ДД.ММ.ГГГГ: точки ставятся сами. */
object DateMask {
    fun attach(e: EditText) = e.addTextChangedListener(object : TextWatcher {
        private var self = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            if (self) return
            val d = s.filter { it.isDigit() }.toString().take(8)
            val f = buildString {
                d.forEachIndexed { i, c -> if (i == 2 || i == 4) append('.'); append(c) }
            }
            if (f != s.toString()) {
                self = true; s.replace(0, s.length, f); self = false
            }
        }
    })
}

/** Маска кода подразделения 000-000. */
object DivisionMask {
    fun attach(e: EditText) = e.addTextChangedListener(object : TextWatcher {
        private var self = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            if (self) return
            val d = s.filter { it.isDigit() }.toString().take(6)
            val f = if (d.length > 3) d.take(3) + "-" + d.drop(3) else d.toString()
            if (f != s.toString()) { self = true; s.replace(0, s.length, f); self = false }
        }
    })
}
