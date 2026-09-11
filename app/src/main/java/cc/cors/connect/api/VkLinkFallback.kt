package cc.cors.connect.api

import bypass.whitelist.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Last-resort fallback for the anonymous Whitelist Bypass flow: when neither
 * [BuildConfig.CORS_BASE_URL] nor the Yandex Function proxy is reachable
 * (some carrier whitelists block both), the app instead reads a temporary
 * 5-minute link that the backend pushes into a private VK dialog via
 * `messages.send` (see `vk_relay.py` on the server) — `api.vk.com` stays
 * reachable on most such whitelists.
 *
 * One-way and read-only in intent: this only calls `messages.getHistory` and
 * takes the most recent message's text as the link. The dialog is private
 * (community -> one admin account), never posted to the community wall.
 */
object VkLinkFallback {

    private const val VK_API_BASE = "https://api.vk.com/method"
    private const val TIMEOUT_MS = 10_000

    val isConfigured: Boolean
        get() = BuildConfig.VK_COMMUNITY_TOKEN.isNotBlank() &&
            BuildConfig.VK_RELAY_PEER_ID.toLongOrNull()?.let { it > 0 } == true

    /**
     * Short human-readable reason the last [fetchLatestLink] call returned
     * null — surfaced in the app's Logs tab via
     * [cc.cors.connect.cors.CorsInstanceController] so a failure can be
     * diagnosed without adb. Not thread-safe beyond "last call wins"; good
     * enough since only one connect attempt runs at a time.
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /**
     * Fetches the most recently relayed link, or null if VK is unreachable,
     * unconfigured, or the relay hasn't pushed anything yet — see
     * [lastFailureReason] for why. Blocking — call off the main thread.
     */
    fun fetchLatestLink(): String? {
        lastFailureReason = null
        if (!isConfigured) {
            lastFailureReason = "not configured (token/peer id blank in this build)"
            return null
        }
        return try {
            val params = "user_id=${BuildConfig.VK_RELAY_PEER_ID}" +
                "&count=1" +
                "&access_token=${urlEncode(BuildConfig.VK_COMMUNITY_TOKEN)}" +
                "&v=${urlEncode(BuildConfig.VK_API_VERSION)}"
            val url = URL("$VK_API_BASE/messages.getHistory?$params")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val text = try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: run {
                    lastFailureReason = "HTTP $code, empty body"
                    return null
                }
            } finally {
                conn.disconnect()
            }
            val obj = JSONObject(text)
            if (obj.has("error")) {
                lastFailureReason = "VK API error: ${obj.optJSONObject("error")?.optString("error_msg") ?: text}"
                return null
            }
            val items = obj.optJSONObject("response")?.optJSONArray("items")
            if (items == null) {
                lastFailureReason = "unexpected response shape: $text"
                return null
            }
            if (items.length() == 0) {
                lastFailureReason = "dialog history is empty — relay hasn't pushed a link yet"
                return null
            }
            val message = items.getJSONObject(0).optString("text").trim()
            if (message.isEmpty()) {
                lastFailureReason = "latest message has no text"
                null
            } else {
                message
            }
        } catch (e: Exception) {
            lastFailureReason = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
