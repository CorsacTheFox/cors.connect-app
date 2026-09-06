package bypass.whitelist.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption helper: a VPN service that lives in the
 * background needs to be excluded from Doze/app-standby or Android will
 * aggressively freeze the tunnel.
 */
object BatteryOptimizer {

    fun isIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog asking to exempt the app. Works on API 23+,
     * which covers our minSdk 24; no-op when already exempt.
     */
    fun requestIgnore(context: Context) {
        if (isIgnored(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // Some ROMs strip the standalone dialog — fall back to the list.
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }

    /** Guards against re-prompting twice inside one app process. */
    @Volatile
    var promptedThisProcess = false

    /**
     * True when the reminder still makes sense: the OS still optimizes us and
     * we haven't already asked in this process. Unlike before, this keeps
     * nudging on later cold starts until the exemption is actually granted —
     * the one-shot [Prefs.batteryReminderShown] made a dismissed prompt
     * permanent, which is why the check "disappeared".
     */
    fun shouldShowReminder(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !promptedThisProcess &&
            !isIgnored(context)
}
