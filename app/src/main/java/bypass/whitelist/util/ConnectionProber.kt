package bypass.whitelist.util

import bypass.whitelist.tunnel.XrayVpnService
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Active connectivity probes made from inside the app process through the
 * running tunnel's loopback SOCKS5 inbound (whitelist relay: Prefs.socksPort,
 * Xray: Prefs.xraySocksPort). Credentials come from TunnelSocksAuth (the same
 * Authenticator CorsClient uses). Blocking: call from a background thread.
 */
object ConnectionProber {

    data class ProbeResult(val name: String, val ok: Boolean, val ms: Long, val detail: String)

    data class ProbeSet(val timestamp: Long, val port: Int?, val results: List<ProbeResult>) {
        val allOk: Boolean get() = results.isNotEmpty() && results.all { it.ok }
        fun format(): String {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestamp))
            val sb = StringBuilder("[$ts] socks :" + (port ?: "-"))
            for (r in results) sb.append("\n  ").append(if (r.ok) "OK  " else "FAIL").append(' ').append(r.name).append(" ").append(r.ms).append("ms ").append(r.detail)
            return sb.toString()
        }
        fun short(): String = results.joinToString(" ") { (if (it.ok) "+" else "-") + it.name }
    }

    private const val TIMEOUT_MS = 10_000
    private const val DOWNLOAD_LIMIT_MS = 15_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

    private fun candidatePorts(): List<Int> {
        val xrayUp = XrayVpnService.instance?.isRunning == true
        val primary = if (xrayUp) Prefs.xraySocksPort else Prefs.socksPort
        val other = if (xrayUp) Prefs.socksPort else Prefs.xraySocksPort
        return listOf(primary.toInt(), other.toInt()).distinct()
    }

    private fun portOpen(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress(Net.LOCALHOST, port), 1000); true }
    } catch (_: Exception) { false }

    private fun open(url: String, proxy: Proxy): HttpURLConnection {
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun timedGet(name: String, url: String, proxy: Proxy): ProbeResult {
        val t0 = System.nanoTime()
        return try {
            val c = open(url, proxy)
            try {
                val code = c.responseCode
                runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } }
                ProbeResult(name, true, (System.nanoTime() - t0) / 1_000_000, "HTTP $code")
            } finally { c.disconnect() }
        } catch (e: Exception) {
            ProbeResult(name, false, (System.nanoTime() - t0) / 1_000_000, e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    private fun download(proxy: Proxy, size: Long = 1_000_000, limitMs: Long = DOWNLOAD_LIMIT_MS): ProbeResult {
        val t0 = System.nanoTime()
        var bytes = 0L
        return try {
            val c = open("https://speed.cloudflare.com/__down?bytes=$size", proxy)
            try {
                val code = c.responseCode
                if (code != 200) return ProbeResult("download", false, (System.nanoTime() - t0) / 1_000_000, "HTTP $code")
                val buf = ByteArray(16 * 1024)
                c.inputStream.use { input ->
                    while (true) {
                        if ((System.nanoTime() - t0) / 1_000_000 > limitMs) break
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes += n
                    }
                }
            } finally { c.disconnect() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val kbps = if (ms > 0) bytes * 1000 / 1024 / ms else 0
            ProbeResult("download", bytes >= size, ms, "$bytes B, $kbps KB/s")
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            ProbeResult("download", false, ms, "$bytes B, " + e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    private fun parallel(proxy: Proxy): ProbeResult {
        val t0 = System.nanoTime()
        val pool = Executors.newFixedThreadPool(5)
        return try {
            val futures = (0 until 5).map { i ->
                pool.submit(Callable { timedGet("p$i", "https://www.gstatic.com/generate_204?n=$i", proxy).ok })
            }
            val okCount = futures.count { runCatching { it.get(TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS) }.getOrDefault(false) }
            ProbeResult("parallel", okCount == 5, (System.nanoTime() - t0) / 1_000_000, "$okCount/5")
        } finally { pool.shutdownNow() }
    }

    /** Single download of [size] bytes through the tunnel (debug control port). */
    fun downloadBytes(size: Long): ProbeResult {
        cc.cors.connect.api.TunnelSocksAuth.install()
        val port = candidatePorts().firstOrNull { portOpen(it) }
            ?: return ProbeResult("bigDownload", false, 0, "no local SOCKS port open")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(Net.LOCALHOST, port))
        return download(proxy, size, 120_000L).copy(name = "bigDownload")
    }

    fun runAll(): ProbeSet {
        cc.cors.connect.api.TunnelSocksAuth.install()
        val port = candidatePorts().firstOrNull { portOpen(it) }
            ?: return ProbeSet(System.currentTimeMillis(), null, listOf(ProbeResult("socks", false, 0, "no local SOCKS port open")))
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(Net.LOCALHOST, port))
        val results = listOf(
            timedGet("gstatic204", "https://www.gstatic.com/generate_204", proxy),
            timedGet("telegram", "https://api.telegram.org", proxy),
            download(proxy),
            parallel(proxy),
        )
        return ProbeSet(System.currentTimeMillis(), port, results)
    }
}
