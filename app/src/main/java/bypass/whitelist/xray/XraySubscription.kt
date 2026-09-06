package bypass.whitelist.xray

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A subscription URL the user imported. The servers it expands to are stored
 * as normal [XrayServer] entries (tagged with [XrayServer.subscriptionId]) so
 * the main-screen list, row menu, etc. don't need to know subscriptions exist.
 */
data class XraySubscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedMs: Long = 0L,
    val lastServerCount: Int = 0,
    /** Traffic used (upload+download, bytes) from the panel's quota header; 0 = unknown. */
    val usedBytes: Long = 0L,
    /** Traffic quota (bytes); 0 = unlimited/unknown. */
    val totalBytes: Long = 0L,
    /** Subscription expiry (unix seconds); 0 = unknown. */
    val expireAtSec: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("lastUpdatedMs", lastUpdatedMs)
        put("lastServerCount", lastServerCount)
        put("usedBytes", usedBytes)
        put("totalBytes", totalBytes)
        put("expireAtSec", expireAtSec)
    }

    companion object {
        fun newWith(name: String, url: String): XraySubscription =
            XraySubscription(id = UUID.randomUUID().toString(), name = name, url = url)

        fun fromJson(o: JSONObject): XraySubscription = XraySubscription(
            id = o.getString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            lastUpdatedMs = o.optLong("lastUpdatedMs", 0L),
            lastServerCount = o.optInt("lastServerCount", 0),
            usedBytes = o.optLong("usedBytes", 0L),
            totalBytes = o.optLong("totalBytes", 0L),
            expireAtSec = o.optLong("expireAtSec", 0L),
        )

        fun listToJson(items: List<XraySubscription>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String): List<XraySubscription> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun suggestNameFor(url: String): String = try {
            java.net.URL(url).host ?: "Subscription"
        } catch (_: Exception) {
            "Subscription"
        }
    }
}
