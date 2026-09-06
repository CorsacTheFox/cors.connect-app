package bypass.whitelist.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a release APK ourselves (plain HttpURLConnection on a worker
 * thread) and fires the installer intent when it lands.
 *
 * The previous implementation delegated to the system DownloadManager, which
 * turned out to silently do nothing on a large slice of devices: no
 * WRITE_EXTERNAL_STORAGE on API 24-28, OEM ROMs that reject a re-download of an
 * existing file name, and users who freeze the "Download Manager" system app.
 * The check passed, the toast showed, and the download never ran. Doing the
 * fetch in-process removes every one of those failure modes; the only runtime
 * dependency left is INTERNET.
 */
object ApkInstaller {

    private const val CHANNEL_ID = "app_updates"
    private const val NOTIF_ID = 4711
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    /** Downloads [url] to the app cache and launches the installer when done. */
    fun download(context: Context, url: String, title: String) {
        val appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            toast(appContext, "Обновление уже загружается")
            return
        }
        io.execute {
            try {
                val file = fetch(appContext, url)
                notifyDone(appContext, title)
                main.post { launchInstaller(appContext, file, url) }
            } catch (t: Throwable) {
                notifyFailed(appContext)
                main.post { fallBackToBrowser(appContext, url) }
            } finally {
                running.set(false)
            }
        }
    }

    private fun fetch(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One stable name — a stale copy is simply overwritten.
        val out = File(dir, "cors-connect-update.apk")

        var current = url
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                current = URL(URL(current), next).toString()
                redirects++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            break
        }

        val total = conn.contentLengthLong
        try {
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                notifyProgress(context, pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() == 0L) throw IllegalStateException("empty download")
        return out
    }

    private fun launchInstaller(context: Context, file: File, url: String) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            fallBackToBrowser(context, url)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            fallBackToBrowser(context, url)
        }
    }

    private fun fallBackToBrowser(context: Context, url: String) {
        toast(context, "Не удалось скачать обновление, открываю страницу релиза")
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (_: Exception) {
            AppUpdater.openReleasesPage(context)
        }
    }

    // ---- notifications --------------------------------------------------------

    private fun manager(context: Context): NotificationManager? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return nm
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun baseNotif(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка обновления")
            .setOnlyAlertOnce(true)

    private fun notifyProgress(context: Context, pct: Int) {
        if (!canNotify(context)) return
        manager(context)?.notify(
            NOTIF_ID,
            baseNotif(context).setProgress(100, pct, false).setOngoing(true).build(),
        )
    }

    private fun notifyDone(context: Context, title: String) {
        if (!canNotify(context)) return
        manager(context)?.notify(
            NOTIF_ID,
            baseNotif(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText("Загрузка завершена, нажмите для установки")
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifyFailed(context: Context) {
        if (!canNotify(context)) return
        manager(context)?.notify(
            NOTIF_ID,
            baseNotif(context)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Не удалось скачать обновление")
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
