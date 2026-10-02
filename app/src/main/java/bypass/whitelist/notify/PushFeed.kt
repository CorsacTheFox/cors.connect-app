package bypass.whitelist.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import bypass.whitelist.MainActivity
import bypass.whitelist.R
import bypass.whitelist.util.Prefs
import bypass.whitelist.xray.XraySubscriptionManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Self-hosted push: the `app-notify` service on panel.cors-fox.cc keeps a
 * short feed of announcements; we poll it (WorkManager, every 15 min, plus
 * on app start) and post anything newer than [Prefs.pushLastId] as a regular
 * notification. No Google services involved, so it works on Huawei too.
 */
object PushFeed {

    private const val FEED_URL = "https://panel.cors-fox.cc/notify/feed"
    private const val CHANNEL_ID = "announcements"
    private const val WORK_NAME = "push-feed"
    private const val TIMEOUT_MS = 10_000

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker_>(15, TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        Thread { runCatching { check(context) } }.start()
    }

    /** Blocking: fetches the feed and posts new items. */
    @Synchronized
    fun check(context: Context) {
        val since = Prefs.pushLastId
        val url = XraySubscriptionManager.proxiedSubscriptionUrl("$FEED_URL?since=$since")
        val conn = URL(url).openConnection() as HttpURLConnection
        val json = try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 200) return
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val items = json.optJSONArray("items") ?: return
        var maxId = since
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optInt("id")
            if (id <= since) continue
            show(context, id, item.optString("title"), item.optString("body"), item.optString("url"))
            maxId = maxOf(maxId, id)
        }
        // Feed was reset server-side (ids restarted): follow it down.
        val latest = json.optInt("latest", maxId)
        Prefs.pushLastId = if (latest < since) latest else maxId
    }

    private fun show(context: Context, id: Int, title: String, body: String, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = if (url.startsWith("http") || url.startsWith("tg:")) Intent(Intent.ACTION_VIEW, Uri.parse(url))
        else Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, id, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cors_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(PUSH_ID_BASE + id, n)
    }

    private const val PUSH_ID_BASE = 40_000

    class Worker_(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = try {
            check(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
