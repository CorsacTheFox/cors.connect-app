package bypass.whitelist.xray

import bypass.whitelist.util.ParamCallback
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import kotlin.concurrent.thread

/**
 * Thin wrapper around the Xray-core Android bindings produced by
 * `2dust/AndroidLibXrayLite` (gomobile bind output, Java package `libv2ray`).
 * See `/BUILD_XRAY_ENGINE.md` at the repo root for how `app/libs/libv2ray.aar`
 * is built and dropped in — this file will not compile until that aar is
 * present.
 *
 * Used by both [bypass.whitelist.tunnel.XrayVpnService] (standard Xray
 * connections) and [bypass.whitelist.tunnel.TunnelVpnService] (instance/call
 * connections — Xray-core there is configured as a plain SOCKS5 client
 * pointed at the already-running relay, see
 * [XrayConfigBuilder.buildForLocalUpstream]). Both hand [start] the VPN's real
 * TUN file descriptor: Xray-core's own `protocol: "tun"` inbound (gVisor
 * netstack, part of Xray-core itself) reads and writes it directly, so there
 * is no separate native tun2socks step or local SOCKS5 listener involved —
 * see [XrayConfigBuilder]'s doc comment for the full picture, and
 * `CoreController.startLoop`'s `tunFd` parameter for how the fd reaches it.
 *
 * Xray's outbound sockets never need an explicit `VpnService.protect()` call:
 * they're opened in-process, and this app's own package is always excluded
 * from (or simply never added to) the VPN's captured UID set (see
 * `VpnBuilder.configure`'s `addDisallowedApplication` / allow-list handling).
 *
 * A note on generated types: gomobile maps Go's platform-sized `int` to Java
 * `long` for most fields, but `tunFd` (a Go `int32`) maps to Java `int`, and
 * lower-cases the first letter of exported Go identifiers for Java method
 * names (types keep their Go name). If the aar you build exposes different
 * types (gomobile version dependent), adjust the [CoreCallbackHandler]
 * overrides below to match — everything else here is independent of that
 * detail.
 */
class XrayEngine(
    private val onLog: ParamCallback<String>,
) {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var controller: CoreController? = null

    private val callbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long {
            onLog("Xray core started")
            return 0L
        }

        override fun shutdown(): Long {
            onLog("Xray core stopped")
            isRunning = false
            return 0L
        }

        override fun onEmitStatus(code: Long, message: String): Long {
            if (message.isNotBlank()) onLog(message)
            return 0L
        }
    }

    /** Initializes the Xray asset/cert environment. Safe to call more than once. */
    fun initEnv(assetsDir: File) {
        try {
            if (!assetsDir.exists()) assetsDir.mkdirs()
            Libv2ray.initCoreEnv(assetsDir.absolutePath, "")
        } catch (t: Throwable) {
            // Catches Throwable (not just Exception): a mismatched/incomplete
            // gomobile aar can surface as UnsatisfiedLinkError/NoSuchMethodError
            // here, which are Errors, not Exceptions — letting those propagate
            // uncaught is what turns a bad engine build into a hard app crash
            // instead of a recoverable "Xray env init failed" log line.
            onLog("Xray env init failed: ${t.message}")
        }
    }

    /**
     * Starts Xray-core with [configJson] (see [XrayConfigBuilder]), handing it
     * [tunFd] — the VPN's TUN device file descriptor — directly.
     *
     * `CoreController.startLoop(config, tunFd)` sets the `xray.tun.fd` env var
     * that Xray-core's built-in `protocol: "tun"` inbound (gVisor-backed,
     * `proxy/tun` in XTLS/Xray-core) reads from and consumes packets on
     * directly — there is no separate tun2socks step: Xray-core *is* the
     * tun2socks bridge here. Pass `0` (or any non-fd value) only when
     * [configJson] has no `tun` inbound (there's currently no such caller).
     * Xray-core does not take ownership of the fd — the caller must keep the
     * underlying `ParcelFileDescriptor`/int alive for as long as the loop
     * runs and close it itself after [stop] returns.
     */
    /**
     * Detaches the current controller (if any) and returns it for disposal.
     * `CoreController.stopLoop()` is a synchronous gomobile/native call that
     * can hang indefinitely on a stuck core shutdown; running it while
     * holding this object's monitor (as the old `@Synchronized stop` did)
     * meant one hung stop blocked every future [start]/[stop] for the rest
     * of the process lifetime. The monitor is now held only for the pointer
     * swap — stopLoop runs on the caller's or its own thread without it.
     */
    private fun detachController(): CoreController? = synchronized(this) {
        val core = controller
        controller = null
        isRunning = false
        core
    }

    private fun disposeCore(core: CoreController?) {
        if (core == null) return
        try {
            core.stopLoop()
        } catch (t: Throwable) {
            onLog("Xray stop error: ${t.message}")
        }
    }

    fun start(configJson: String, tunFd: Int): Boolean {
        // A previous core that a hung stopLoop() never finished with must not
        // block a reconnect: park its disposal on a throwaway thread (it may
        // never return — fine, it owns nothing shared anymore) and start the
        // fresh controller immediately.
        val oldCore = detachController()
        if (oldCore != null) {
            onLog("Previous Xray core still winding down — starting a new one")
            thread(name = "xray-engine-dispose") { disposeCore(oldCore) }
        }
        val core = try {
            Libv2ray.newCoreController(callbackHandler)
        } catch (t: Throwable) {
            onLog("Xray start failed: ${t.message}")
            return false
        }
        return try {
            core.startLoop(configJson, tunFd)
            synchronized(this) { controller = core }
            isRunning = true
            true
        } catch (t: Throwable) {
            onLog("Xray start failed: ${t.message}")
            disposeCore(core)
            false
        }
    }

    fun stop() {
        disposeCore(detachController())
    }

    /**
     * Measures the round-trip for an HTTP GET of [url] dialed *through the
     * running core* (the request is dispatched via routing → the proxy
     * outbound, so it exercises the full path: outbound dial, DNS, TLS, HTTP).
     * The binding retries once internally within a 12s window.
     *
     * Returns the RTT in ms, or throws with the underlying failure — which is
     * the only way to learn *why* a "connected" tunnel passes no traffic
     * (Xray's own log output goes to stdout/logcat, invisible to this app —
     * see [bypass.whitelist.xray.XrayConfigBuilder] and the binding's console
     * log writer). Returns -1 per the binding's contract on failure paths
     * that don't throw, normalized here to a throw for a single caller shape.
     */
    fun measureDelay(url: String): Long {
        val core = controller ?: throw IllegalStateException("Xray core is not running")
        val rtt = try {
            core.measureDelay(url)
        } catch (t: Throwable) {
            throw IllegalStateException(t.message ?: "delay measurement failed", t)
        }
        if (rtt < 0) throw IllegalStateException("no successful response through the tunnel")
        return rtt
    }

    companion object {
        /** Real-delay probe target: tiny 204 response, reachable from any exit. */
        private const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"

        @Volatile private var envReady = false

        private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=3000000"
        private const val SPEED_TEST_MS = 5_000L
        /** Parallel downloads would split the device's bandwidth and skew results. */
        private val speedSlots = java.util.concurrent.Semaphore(2)

        private val quietHandler = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(code: Long, message: String): Long = 0L
        }

        private fun ensureEnv(assetsDir: File): Boolean {
            if (envReady) return true
            return try {
                assetsDir.mkdirs()
                Libv2ray.initCoreEnv(assetsDir.absolutePath, "")
                envReady = true
                true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Short download test through [server]: a throwaway core with only a
         * loopback SOCKS inbound (no TUN) → this server's outbound, then up to
         * [SPEED_TEST_MS] of an HTTP download through it. Returns Mbit/s, or
         * null when the server can't carry real traffic (handshake ok but the
         * download is refused/stalls — exactly the "pings but doesn't work" case).
         */
        fun measureServerSpeed(server: XrayServer, assetsDir: File): Double? {
            if (!ensureEnv(assetsDir)) return null
            speedSlots.acquire()
            var core: CoreController? = null
            try {
                val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val config = org.json.JSONObject().apply {
                    put("log", org.json.JSONObject().put("loglevel", "none"))
                    put("inbounds", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("tag", "speed-in")
                        put("protocol", "socks")
                        put("listen", "127.0.0.1")
                        put("port", port)
                        put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false))
                    }))
                    put("outbounds", org.json.JSONArray().put(server.toOutboundJson("proxy")))
                }.toString()
                core = Libv2ray.newCoreController(quietHandler)
                core.startLoop(config, 0)
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                val conn = java.net.URL(SPEED_TEST_URL).openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 4_000
                conn.useCaches = false
                try {
                    if (conn.responseCode !in 200..299) return null
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    val start = System.nanoTime()
                    val deadline = start + SPEED_TEST_MS * 1_000_000
                    conn.inputStream.use { input ->
                        while (System.nanoTime() < deadline) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                        }
                    }
                    val secs = (System.nanoTime() - start) / 1e9
                    if (total < 64 * 1024 || secs <= 0) return null
                    return total * 8 / secs / 1_000_000
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                return null
            } finally {
                val c = core
                if (c != null) thread(name = "xray-speed-dispose") { try { c.stopLoop() } catch (_: Throwable) {} }
                speedSlots.release()
            }
        }

        private const val DELAY_HARD_TIMEOUT_MS = 6_000L
        private val delayExecutor = java.util.concurrent.Executors.newCachedThreadPool()

        /**
         * "Real delay" of [server]: spins up a throwaway core with just this
         * server's outbound and times an HTTP GET through it — the full path
         * (dial, TLS/REALITY/QUIC handshake, proxy auth, HTTP), unlike a TCP
         * connect which only proves the port is open (and can't probe UDP
         * protocols like Hysteria2 at all). Runs in the app process, which is
         * excluded from the VPN, so it works while connected too.
         *
         * Blocking; returns the RTT in ms or null on failure.
         */
        fun measureServerDelay(server: XrayServer, assetsDir: File): Int? {
            if (!envReady) {
                try {
                    assetsDir.mkdirs()
                    Libv2ray.initCoreEnv(assetsDir.absolutePath, "")
                    envReady = true
                } catch (_: Throwable) {
                    return null
                }
            }
            val config = org.json.JSONObject().apply {
                put("log", org.json.JSONObject().put("loglevel", "none"))
                put("outbounds", org.json.JSONArray().put(server.toOutboundJson("proxy")))
            }.toString()
            // The binding retries internally inside a ~12s window; a server
            // that hasn't finished the handshake + HTTP round-trip in
            // DELAY_HARD_TIMEOUT_MS is useless anyway, so report a timeout
            // early instead of holding the row on "Ping…".
            val future = delayExecutor.submit<Int?> {
                try {
                    Libv2ray.measureOutboundDelay(config, DELAY_TEST_URL).toInt().takeIf { it >= 0 }
                } catch (_: Throwable) {
                    null
                }
            }
            return try {
                future.get(DELAY_HARD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
