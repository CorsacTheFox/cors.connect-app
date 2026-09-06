package bypass.whitelist.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import bypass.whitelist.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases for a newer APK build. The repo, release tag and the
 * `.apk` asset names follow the convention used by
 * https://github.com/CorsacTheFox/cors.connect-app/releases — the API endpoint
 * returns `tag_name` plus every asset with a `browser_download_url`.
 */
object AppUpdater {

    private const val OWNER = "CorsacTheFox"
    private const val REPO = "cors.connect-app"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

    data class Release(
        val version: String,
        val changelog: String,
        val apkUrl: String?,
        val pageUrl: String,
    )

    fun isNewer(latest: String, current: String = BuildConfig.VERSION_NAME): Boolean =
        versionToInt(latest) > versionToInt(current)

    /** Parses "1.2.3" (optionally prefixed with "v", minor/patch optional) into a comparable Int. */
    private fun versionToInt(version: String): Int {
        val parts = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .split(".")
        var result = 0
        for (i in 0 until 3) {
            val n = parts.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            result = result * 1000 + n
        }
        return result
    }

    /**
     * Runs the release check off the main thread; [callback] receives the
     * parsed release (even when it is not newer — callers decide what to show)
     * or null on any failure.
     */
    fun checkLatest(callback: (Release?) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        executor.execute {
            val result = try {
                fetchLatest()
            } catch (_: IOException) {
                null
            } catch (_: org.json.JSONException) {
                null
            }
            main.post { callback(result) }
        }
    }

    private fun fetchLatest(): Release {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isEmpty()) throw IOException("no tag_name")
            val apkUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.optJSONObject(it) }
                    .firstOrNull { it?.optString("name", "")?.endsWith(".apk") == true }
                    ?.optString("browser_download_url")
            }
            return Release(
                version = tag,
                changelog = json.optString("body").trim(),
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url", RELEASES_URL),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Opens the release page in the browser — the zero-permission fallback. */
    fun openReleasesPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
}
