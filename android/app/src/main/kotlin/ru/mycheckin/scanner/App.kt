package ru.mycheckin.scanner

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Приложение: при старте включает запись аварийных закрытий, защиту (блокировка, запрет скриншотов) и фоновое обновление сведений МТС. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AppSecurity.install(this)
        // Расписание фонового обновления сведений о номерах (идемпотентно: обновляет настройки).
        runCatching { LineSync.schedule(this) }
    }
}

/**
 * Если приложение упало, подробности (где и почему) пишутся в файл прямо в момент сбоя,
 * а при следующем запуске переносятся в журнал — так причину видно без компьютера и logcat.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(buildString {
                    appendLine("Время: ${SimFormat.time(System.currentTimeMillis())}")
                    appendLine("Версия приложения: ${version ?: "?"} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Поток: ${thread.name}")
                    appendLine("Ошибка: ${error.javaClass.name}: ${error.message}")
                    appendLine()
                    append(android.util.Log.getStackTraceString(error).take(12_000))
                })
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Отчёт о прошлом падении (и удалить его), если был. */
    fun takePending(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }
}
