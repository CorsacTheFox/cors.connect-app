package cc.cors.connect.api

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import bypass.whitelist.App
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Hostname resolution that falls back to the mobile operator's own DNS servers.
 *
 * The Cors.Connect bootstrap requests (health / create-instance / poll / claim)
 * are normally sent to a Yandex Cloud Function (`functions.yandexcloud.net`).
 * Before the tunnel is up the app still resolves names through the DNS
 * configured in Settings (e.g. `1.1.1.1`), which many RU operators hijack or
 * black-hole — so that host fails to resolve and the whole connect pipeline
 * stalls before it can start. The carrier's / Wi-Fi gateway's own resolvers,
 * advertised on the active network, answer for Yandex infrastructure normally.
 *
 * [resolveViaOperators] performs a minimal plaintext UDP DNS query against each
 * of those resolvers in turn. It is used ONLY for the pre-tunnel bootstrap and
 * ONLY when the platform resolver already failed; once connected the pipeline
 * resolves through the settings DNS like everything else (see [CorsClient]).
 */
object OperatorDns {

    private const val TAG = "OperatorDns"
    private const val QUERY_TIMEOUT_MS = 3_000
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private val context: Context get() = App.instance
    private val rng = SecureRandom()

    /** True when the platform resolver can already resolve [host]. */
    fun platformResolves(host: String): Boolean =
        runCatching { InetAddress.getByName(host) }.isSuccess

    /**
     * DNS servers advertised by every currently-active network (cellular first,
     * then Wi-Fi / others), de-duplicated in discovery order.
     */
    @Suppress("DEPRECATION") // ConnectivityManager.allNetworks: no sync replacement, fine on minSdk 24
    fun operatorResolvers(): List<InetAddress> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val ordered = LinkedHashSet<InetAddress>()
        val networks = runCatching { cm.allNetworks }.getOrNull() ?: return emptyList()
        // Put the network backing the default route first — it is the one our
        // sockets will actually use, so its resolver is the most relevant.
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val sorted = networks.sortedByDescending { it == active }
        for (n in sorted) {
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            ordered.addAll(lp.dnsServers)
        }
        return ordered.toList()
    }

    /**
     * Best-effort resolution of [host] against the operator resolvers. Never
     * throws; returns an empty list when nothing answered.
     */
    fun resolveViaOperators(host: String): List<InetAddress> {
        val resolvers = operatorResolvers()
        if (resolvers.isEmpty()) {
            Log.w(TAG, "no operator DNS servers on any active network")
            return emptyList()
        }
        for (server in resolvers) {
            val answers = buildList {
                addAll(runCatching { query(host, server, TYPE_A) }.getOrDefault(emptyList()))
                addAll(runCatching { query(host, server, TYPE_AAAA) }.getOrDefault(emptyList()))
            }
            if (answers.isNotEmpty()) {
                Log.i(TAG, "resolved $host via ${server.hostAddress}: ${answers.map { it.hostAddress }}")
                // IPv4 first — more likely to be reachable pre-tunnel.
                return answers.sortedByDescending { it is Inet4Address }
            }
        }
        Log.w(TAG, "operator DNS could not resolve $host (${resolvers.size} servers tried)")
        return emptyList()
    }

    // ---- minimal DNS client -------------------------------------------------

    private fun query(host: String, server: InetAddress, type: Int): List<InetAddress> {
        val id = rng.nextInt(0xFFFF)
        val request = buildQuery(id, host, type)
        DatagramSocket().use { sock ->
            sock.soTimeout = QUERY_TIMEOUT_MS
            sock.send(DatagramPacket(request, request.size, InetSocketAddress(server, 53)))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)
            return parseAnswers(buf, packet.length, id, type)
        }
    }

    private fun buildQuery(id: Int, host: String, type: Int): ByteArray {
        val out = ArrayList<Byte>(32)
        fun u16(v: Int) { out.add((v ushr 8).toByte()); out.add(v.toByte()) }
        u16(id)
        u16(0x0100)          // flags: standard query, recursion desired
        u16(1); u16(0); u16(0); u16(0)   // qd=1, an=0, ns=0, ar=0
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw IOException("bad label")
            out.add(bytes.size.toByte())
            out.addAll(bytes.toList())
        }
        out.add(0)           // root label
        u16(type)
        u16(1)               // class IN
        return out.toByteArray()
    }

    private fun parseAnswers(buf: ByteArray, len: Int, expectedId: Int, wantType: Int): List<InetAddress> {
        if (len < 12) return emptyList()
        fun u16(off: Int) = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
        if (u16(0) != expectedId) return emptyList()
        val flags = u16(2)
        if (flags and 0x000F != 0) return emptyList()   // rcode != NOERROR
        val qd = u16(4)
        val an = u16(6)
        var p = 12
        // skip question section
        repeat(qd) {
            p = skipName(buf, p)
            p += 4   // qtype + qclass
        }
        val result = ArrayList<InetAddress>()
        repeat(an) {
            p = skipName(buf, p)
            if (p + 10 > len) return result
            val type = u16(p)
            val rdlen = u16(p + 8)
            p += 10
            if (p + rdlen > len) return result
            if (type == wantType && (rdlen == 4 || rdlen == 16)) {
                val raw = buf.copyOfRange(p, p + rdlen)
                runCatching { InetAddress.getByAddress(raw) }.getOrNull()?.let { result.add(it) }
            }
            p += rdlen
        }
        return result
    }

    /** Advances past a (possibly compressed) domain name, returning the offset after it. */
    private fun skipName(buf: ByteArray, start: Int): Int {
        var p = start
        while (p < buf.size) {
            val b = buf[p].toInt() and 0xFF
            when {
                b == 0 -> return p + 1
                b and 0xC0 == 0xC0 -> return p + 2      // compression pointer terminates the name
                else -> p += b + 1
            }
        }
        return p
    }

    // ---- SNI-preserving socket factory ------------------------------------

    /**
     * Wraps a delegate [SSLSocketFactory] so that TLS handshakes carry
     * [sniHost] as the Server Name Indication and certificate-matching name,
     * even though the socket is being opened to a bare IP address. Needed
     * because a URL like `https://<ip>/...` would otherwise send the IP as SNI
     * (or none), which Yandex's frontend rejects.
     */
    class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val sniHost: String,
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun fixup(socket: Socket?): Socket {
            if (socket is SSLSocket) {
                val params: SSLParameters = socket.sslParameters
                runCatching { params.serverNames = listOf(SNIHostName(sniHost)) }
                socket.sslParameters = params
            }
            return socket ?: throw IOException("null socket")
        }

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            fixup(delegate.createSocket(s, sniHost, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            fixup(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            fixup(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            fixup(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            fixup(delegate.createSocket(address, port, localAddress, localPort))
    }
}
