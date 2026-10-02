package bypass.whitelist.xray

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Node load feed served next to the Remnawave panel by the `node-load`
 * service (`GET /stats/nodes?host=<server host>`). The service holds the
 * panel token and matches the host to a node by resolved IP, so the app only
 * ever sees aggregate figures.
 */
object NodeLoadClient {

    private const val ENDPOINT = "https://panel.cors-fox.cc/stats/nodes"
    private const val TAG = "NodeLoad"
    private const val TIMEOUT_MS = 8_000

    /** All loads are percent 0..100; [load] is the max of the three. */
    data class NodeLoad(
        val load: Int,
        val bandwidthLoad: Int,
        val cpuLoad: Int,
        val clientsLoad: Int,
        val rxMBps: Double,
        val txMBps: Double,
        val clients: Int,
    )

    /** Blocking; returns null on any failure or when no node serves [host]. */
    fun fetch(host: String): NodeLoad? {
        if (host.isBlank()) return null
        val url = XraySubscriptionManager.proxiedSubscriptionUrl(
            ENDPOINT + "?host=" + URLEncoder.encode(host, "UTF-8")
        )
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", XraySubscriptionManager.USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val n = JSONObject(text).optJSONObject("node") ?: return null
            parse(n)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking batch variant: host → load for every host the feed knows; empty on failure. */
    fun fetchAll(hosts: Collection<String>): Map<String, NodeLoad> {
        val list = hosts.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return emptyMap()
        val url = XraySubscriptionManager.proxiedSubscriptionUrl(
            ENDPOINT + "?hosts=" + URLEncoder.encode(list.joinToString(","), "UTF-8")
        )
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return emptyMap()
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", XraySubscriptionManager.USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 200) {
                Log.w(TAG, "fetchAll: HTTP ${conn.responseCode}")
                return emptyMap()
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val byHost = JSONObject(text).optJSONObject("byHost") ?: return emptyMap()
            byHost.keys().asSequence().associateWith { parse(byHost.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAll failed", e)
            emptyMap()
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(n: JSONObject) = NodeLoad(
        load = n.optInt("load"),
        bandwidthLoad = n.optInt("bandwidthLoad"),
        cpuLoad = n.optInt("cpuLoad"),
        clientsLoad = n.optInt("clientsLoad"),
        rxMBps = n.optDouble("rxMBps", 0.0),
        txMBps = n.optDouble("txMBps", 0.0),
        clients = n.optInt("clients"),
    )
}
