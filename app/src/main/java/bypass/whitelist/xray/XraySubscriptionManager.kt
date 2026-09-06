package bypass.whitelist.xray

import android.os.Build
import android.util.Base64
import bypass.whitelist.BuildConfig
import bypass.whitelist.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Thrown when a subscription URL can't be fetched or contains no usable servers. */
class XraySubscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Fetches a subscription URL and parses it into [XrayServer] entries. Follows
 * the de-facto "v2ray subscription" convention used by most public Xray/V2Ray
 * subscription providers: the response body is base64 (standard or URL-safe,
 * padding optional) of newline-separated share links. Plain-text link lists
 * (no base64 wrapper) are also accepted.
 *
 * Blocking — call off the main thread, matching [cc.cors.connect.api.CorsClient]'s
 * convention elsewhere in the app.
 */
object XraySubscriptionManager {

    private const val TIMEOUT_MS = 15_000

    /**
     * Sent on every subscription request. Panels (Remnawave, Marzban, 3x-ui…)
     * read this header to (a) attribute the connection to a client app in their
     * dashboard/stats and (b) pick the response format. A bare token like
     * "Cors.Connect/xray-subscription" is logged as an unknown client; the
     * conventional `Name/Version (Platform)` shape below is what panels parse.
     * To have Remnawave show it as a named app with an icon, add this same
     * string (or an `AppName/` prefix match) to the panel's subscription-page
     * app list.
     */
    val USER_AGENT: String =
        "Cors.Connect/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})"

    /**
     * Quota info from the standard `subscription-userinfo` response header
     * most panels (Remnawave, marzban, 3x-ui…) send with the feed:
     * `upload=123; download=456; total=789; expire=1700000000` (bytes and a
     * unix expiry timestamp). Zero means "not reported" (total=0 → unlimited).
     */
    data class SubscriptionUserInfo(
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val expireAtSec: Long = 0L,
    ) {
        val usedBytes: Long get() = uploadBytes + downloadBytes

        companion object {
            fun parse(header: String?): SubscriptionUserInfo? {
                if (header.isNullOrBlank()) return null
                var upload = 0L
                var download = 0L
                var total = 0L
                var expire = 0L
                header.split(';').forEach { part ->
                    val key = part.substringBefore('=').trim().lowercase()
                    val value = part.substringAfter('=', "").trim().toLongOrNull() ?: return@forEach
                    when (key) {
                        "upload" -> upload = value
                        "download" -> download = value
                        "total" -> total = value
                        "expire" -> expire = value
                    }
                }
                if (upload == 0L && download == 0L && total == 0L && expire == 0L) return null
                return SubscriptionUserInfo(upload, download, total, expire)
            }
        }
    }

    /** Parsed feed: the expanded servers plus the panel's quota header, if sent. */
    data class FetchResult(
        val servers: List<XrayServer>,
        val userInfo: SubscriptionUserInfo?,
    )

    /** Fetches [url] and returns the parsed servers, tagged with [subscriptionId]. */
    fun fetch(url: String, subscriptionId: String): List<XrayServer> =
        fetchWithUserInfo(url, subscriptionId).servers

    fun fetchWithUserInfo(url: String, subscriptionId: String): FetchResult {
        val (body, userInfo) = download(url)
        val decoded = decodeBody(body)
        val servers = decoded
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { XrayServer.parseShareLink(it, subscriptionId) }
            .toList()
        return FetchResult(servers, userInfo)
    }

    private fun download(url: String): Pair<String, SubscriptionUserInfo?> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            // Remnawave HWID device tracking: the panel only creates a device row
            // (hwid-inspector, per-device stats, device-limit enforcement) when the
            // subscription request carries these headers. `x-hwid` must be stable
            // for the life of the install — see [Prefs.deviceHwid].
            setRequestProperty("x-hwid", Prefs.deviceHwid)
            setRequestProperty("x-device-os", "Android")
            setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "")
            setRequestProperty("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            doInput = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw XraySubscriptionException("Subscription fetch failed (HTTP $code)")
            }
            return text to SubscriptionUserInfo.parse(conn.getHeaderField("subscription-userinfo"))
        } catch (e: XraySubscriptionException) {
            throw e
        } catch (e: Exception) {
            throw XraySubscriptionException(e.message ?: "network error", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return trimmed
        // Already a plain link list — no base64 wrapper.
        if (looksLikePlainLinks(trimmed)) return trimmed
        return try {
            val flags = Base64.URL_SAFE or Base64.NO_WRAP
            val normalized = trimmed.replace("\n", "").replace("\r", "")
            val bytes = try {
                Base64.decode(normalized, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                Base64.decode(normalized, flags)
            }
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun looksLikePlainLinks(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return firstLine.contains("://")
    }
}
