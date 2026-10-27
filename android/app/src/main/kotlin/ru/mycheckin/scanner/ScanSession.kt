package ru.mycheckin.scanner

import android.graphics.Bitmap
import ru.mycheckin.passport.PassportData

/** Результат последнего сканирования для передачи между экранами. */
object ScanSession {
    var passport: PassportData? = null
    var photo: Bitmap? = null

    fun clear() {
        passport = null
        photo = null
    }
}
