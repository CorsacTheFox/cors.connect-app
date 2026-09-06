package bypass.whitelist

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.ThemeMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    private val subscriptionRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        applyTheme(Prefs.themeMode)
        installCrashLogger()
        bootstrapGoMobile()
        startSubscriptionAutoRefresh()
    }

    /**
     * Re-fetches every imported Xray subscription every 5 minutes while the
     * process lives (the VPN service keeps it alive while connected). The
     * refresh only rewrites the saved server list — the running tunnel keeps
     * its config, so this never resets an active connection.
     */
    private fun startSubscriptionAutoRefresh() {
        val tick = object : Runnable {
            override fun run() {
                if (Prefs.xraySubscriptions.isNotEmpty()) {
                    bypass.whitelist.xray.XraySubscriptionRefresher.refreshAll()
                }
                subscriptionRefreshHandler.postDelayed(this, SUBSCRIPTION_REFRESH_INTERVAL_MS)
            }
        }
        subscriptionRefreshHandler.postDelayed(tick, SUBSCRIPTION_REFRESH_INTERVAL_MS)
    }

    /**
     * gomobile-bound classes (everything under the `libv2ray`/`go` packages in
     * app/libs/libv2ray.aar) require `go.Seq.setContext(Context)` to be called
     * exactly once before any bound type is touched — it wires the Go runtime
     * to the Android process (JNI env, classloader, app context for asset
     * access, etc). Without it, the first call into `Libv2ray`/`CoreController`
     * either throws deep inside the JNI shim or, if the Go side panics before
     * that guard would normally catch it, brings down the whole process with a
     * native crash that never reaches a Kotlin try/catch or logcat's Java
     * exception formatting — which matches a "crashes with no log" report.
     * Reflection is used so a debug build missing/older libv2ray.aar (no
     * go.Seq class) still starts instead of failing at class-load time.
     */
    private fun bootstrapGoMobile() {
        try {
            val seqClass = Class.forName("go.Seq")
            val setContext = seqClass.getMethod("setContext", Context::class.java)
            setContext.invoke(null, applicationContext)
            Log.i(TAG, "go.Seq.setContext installed")
        } catch (t: Throwable) {
            Log.w(TAG, "go.Seq.setContext unavailable (libv2ray.aar missing/older?): ${t.message}")
        }
    }

    /**
     * Persists uncaught exceptions to a file under filesDir so they survive
     * past the crash (the app was reported to crash "without a log" — this at
     * minimum captures anything that *is* a catchable JVM exception; a true
     * native SIGSEGV/abort from the Go runtime cannot be caught here, but
     * combined with [bootstrapGoMobile] that class of crash should no longer
     * happen). Chains to the previous default handler so normal crash/ANR
     * reporting still happens.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "${java.util.Date()} thread=${thread.name}\n$sw\n"
                File(filesDir, "last_crash.txt").writeText(text)
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            } catch (_: Throwable) {
                // Best-effort only — never let the crash logger itself throw.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "App"
        private const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 5L * 60L * 1000L

        @JvmStatic
        lateinit var instance: Context
            private set

        /**
         * The app ships a single dark ("Nocturne") palette — values and
         * values-night are identical — so the UI is locked to night mode
         * regardless of the system setting. [mode] is kept only so existing
         * call sites compile; it is ignored.
         */
        fun applyTheme(mode: ThemeMode = ThemeMode.DARK) {
            @Suppress("UNUSED_EXPRESSION") mode
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        /** Reads back the last persisted crash (see [installCrashLogger]), if any. */
        fun lastCrashText(context: Context): String? {
            val file = File(context.filesDir, "last_crash.txt")
            return if (file.exists()) file.readText() else null
        }
    }
}
