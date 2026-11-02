package ru.mycheckin.scanner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Работа в фоне без ограничений системы. На Xiaomi (HyperOS/MIUI) и ряде других телефонов приложение
 * в фоне теряет сеть и выгружается — из-за этого 02.11.2026 первичная загрузка прерывалась
 * («Unable to resolve host», «Software caused connection abort»).
 */
object Background {
    val isXiaomi: Boolean get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** Система может усыплять приложение (оптимизация батареи включена). */
    fun restricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun advice(): String = "Телефон может обрывать загрузку в фоне. Разрешите приложению работать без ограничений батареи" +
        if (isXiaomi) ". На Xiaomi ещё: Настройки → Приложения → «Учёт SIM» → Автозапуск — вкл., Контроль активности — «Нет ограничений»." else "."

    @SuppressLint("BatteryLife")
    fun ask(a: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))
        for (i in listOf(direct, list, details)) {
            if (runCatching { a.startActivity(i) }.isSuccess) return
        }
    }
}
