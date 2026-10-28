package ru.mycheckin.scanner

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Защита приложения на телефоне:
 *  - вход по отпечатку / лицу / PIN-коду телефона при запуске и после 2 минут в фоне;
 *  - запрет скриншотов, записи экрана и показа содержимого в списке недавних приложений.
 */
object AppSecurity {
    private const val PREFS = "security"
    private const val LOCK = "lock_enabled"
    private const val SECURE = "no_screenshots"
    const val LOCK_AFTER_MS = 2 * 60_000L

    @Volatile private var unlockedAt = 0L
    @Volatile private var backgroundSince = 0L
    private var started = 0

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lockEnabled(c: Context) = prefs(c).getBoolean(LOCK, true)
    fun screenshotsBlocked(c: Context) = prefs(c).getBoolean(SECURE, true)
    fun setLockEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(LOCK, on).apply()
    fun setScreenshotsBlocked(c: Context, on: Boolean) = prefs(c).edit().putBoolean(SECURE, on).apply()

    /** На телефоне задана блокировка экрана (PIN, графический ключ, пароль) — без неё защищать нечем. */
    fun deviceSecure(c: Context) = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun canAuthenticate(c: Context) =
        BiometricManager.from(c).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    internal fun unlocked() { unlockedAt = SystemClock.elapsedRealtime(); backgroundSince = 0 }

    fun lockNow() { unlockedAt = 0 }

    private fun needsLock(c: Context): Boolean {
        if (!lockEnabled(c) || !canAuthenticate(c)) return false
        if (unlockedAt == 0L) return true
        return backgroundSince != 0L && SystemClock.elapsedRealtime() - backgroundSince > LOCK_AFTER_MS
    }

    fun applyWindowFlags(a: Activity) {
        if (screenshotsBlocked(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = applyWindowFlags(a)
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityResumed(a: Activity) {
                applyWindowFlags(a)
                if (a !is LockActivity && needsLock(a)) {
                    unlockedAt = 0
                    a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                } else if (a !is LockActivity) {
                    backgroundSince = 0
                }
            }
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0 && !a.isChangingConfigurations) { started = 0; backgroundSince = SystemClock.elapsedRealtime() }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}

/** Экран блокировки: содержимое приложения скрыто, пока владелец не подтвердит личность. */
class LockActivity : AppCompatActivity() {
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val bg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(ui.px(32), ui.px(32), ui.px(32), ui.px(32))
            addView(ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
                layoutParams = LinearLayout.LayoutParams(ui.px(88), ui.px(88)).apply { bottomMargin = ui.px(20) }
            })
            addView(ui.text("Учёт SIM заблокирован", 22f, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text("Здесь паспортные данные. Подтвердите, что это вы.", 15f, secondary = true).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.px(8), 0, ui.px(24))
            })
            addView(ui.button("Разблокировать", filled = true) { prompt() }.apply {
                setIconResource(R.drawable.ic_lock)
                layoutParams = LinearLayout.LayoutParams(-2, ui.px(56))
            })
        })
        // «Назад» не открывает приложение — уходим на главный экран телефона.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!AppSecurity.canAuthenticate(this)) { AppSecurity.unlocked(); finish(); return }
        if (!prompting) window.decorView.postDelayed({ prompt() }, 250)
    }

    private fun prompt() {
        if (prompting || isFinishing) return
        prompting = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в «Учёт SIM»")
            .setSubtitle("Отпечаток, лицо или код телефона")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                AppSecurity.unlocked()
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { prompting = false }
        }).authenticate(info)
    }
}
