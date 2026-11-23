package bypass.whitelist.debug

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import bypass.whitelist.BuildConfig
import bypass.whitelist.MainActivity
import bypass.whitelist.tunnel.ConnectTarget
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.tunnel.TunnelServiceState
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.tunnel.XrayVpnService
import bypass.whitelist.util.ConnectionProber
import bypass.whitelist.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * DEBUG-only diagnostic control port: tiny HTTP/1.1 server on 127.0.0.1:8765.
 * Hosted by the process (started from App.onCreate), so it lives as long as
 * the VPN service keeps the process alive. Use via adb forward tcp:8765 tcp:8765.
 */
object DebugControlServer {
    private const val TAG = "DebugControl"
    const val PORT = 8765
    const val EXTRA_CMD = "debug_cmd"

    @Volatile private var started = false
    @Volatile private var activityRef: WeakReference<MainActivity>? = null
    @Volatile var captchaUrl: String? = null
    @Volatile var lastError: String? = null
    @Volatile var serviceStatus: VpnStatus? = null
    private val ring = ArrayDeque<String>()
    private const val RING_MAX = 3000
    private val main = Handler(Looper.getMainLooper())

    fun attach(a: MainActivity) { activityRef = WeakReference(a) }
    fun detach(a: MainActivity) { if (activityRef?.get() === a) activityRef = null }
    private fun activity(): MainActivity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun log(line: String) {
        if (!BuildConfig.DEBUG) return
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(ring) {
            ring.addLast("$ts $line")
            while (ring.size > RING_MAX) ring.removeFirst()
        }
    }

    fun start() {
        if (!BuildConfig.DEBUG || started) return
        started = true
        Thread({
            try {
                ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { ss ->
                    Log.i(TAG, "listening on 127.0.0.1:$PORT")
                    while (true) {
                        val s = ss.accept()
                        Thread({ handle(s) }, "debug-ctl-conn").apply { isDaemon = true }.start()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server stopped: $t")
                started = false
            }
        }, "debug-ctl").apply { isDaemon = true }.start()
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val target = requestLine.split(' ').getOrElse(1) { "/" }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                sock.soTimeout = 0
                val (code, body) = try { route(path, query) } catch (t: Throwable) {
                    500 to JSONObject().put("ok", false).put("error", t.toString())
                }
                val bytes = (body.toString(2) + "\n").toByteArray()
                val reason = if (code == 200) "OK" else "Error"
                val out = sock.getOutputStream()
                out.write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes)
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "request failed: $t")
            }
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun err(code: Int, msg: String) = code to JSONObject().put("ok", false).put("error", msg)

    private fun route(path: String, q: Map<String, String>): Pair<Int, JSONObject> = when (path.trimEnd('/').ifEmpty { "/" }) {
        "/status" -> 200 to status()
        "/targets" -> 200 to targets()
        "/connect" -> connect(q)
        "/disconnect" -> 200 to disconnect()
        "/probe" -> 200 to probe(q)
        "/logs" -> 200 to logs(q["n"]?.toIntOrNull() ?: 200)
        "/" -> 200 to JSONObject().put("endpoints", JSONArray(listOf("/status", "/targets", "/connect?service=ID|xray=ID", "/disconnect", "/probe?download=BYTES&duration=SEC&interval=SEC", "/logs?n=200")))
        else -> err(404, "not found: $path")
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        main.post { try { result = block() } finally { latch.countDown() } }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun status(): JSONObject {
        val ctx = bypass.whitelist.App.instance
        val a = activity()
        val st: VpnStatus? = (if (a != null) onMain { a.currentStatus() } else null) ?: serviceStatus
        val activityConnected = if (a != null) onMain { a.isTunnelActive() } ?: false else false
        val mode = Prefs.connectionMode
        val xrayUp = XrayVpnService.instance?.isRunning == true
        val tunnelUp = runCatching { TunnelServiceState.isTunnelActive(ctx) }.getOrDefault(false) || xrayUp
        val anyRunning = runCatching { TunnelServiceState.isAnyTunnelComponentRunning(ctx) }.getOrDefault(false)
        val o = JSONObject()
        o.put("mode", if (mode == ConnectionMode.XRAY) "xray" else if (Prefs.activeDestinationId.isNotEmpty()) "instance" else "whitelist")
        o.put("running", if (xrayUp) "xray" else if (anyRunning) "whitelist" else "none")
        if (mode == ConnectionMode.XRAY) {
            val s = Prefs.activeXrayServer
            o.put("target", JSONObject().put("type", "xray").put("id", s?.id ?: Prefs.xrayActiveServerId).put("name", s?.remark ?: JSONObject.NULL))
        } else {
            val id = Prefs.corsServiceId
            o.put("target", JSONObject().put("type", "service").put("id", id)
                .put("name", Prefs.corsServices.firstOrNull { it.id == id }?.name ?: JSONObject.NULL)
                .put("destinationId", Prefs.activeDestinationId))
        }
        o.put("vpnStatus", st?.name ?: JSONObject.NULL)
        o.put("activityAlive", a != null)
        o.put("activityConnected", activityConnected)
        o.put("tunnelUp", tunnelUp)
        o.put("corsInstanceId", Prefs.corsInstanceId)
        o.put("lastError", lastError ?: JSONObject.NULL)
        if (st == VpnStatus.ACTION_REQUIRED_CAPTCHA) o.put("captchaUrl", captchaUrl ?: JSONObject.NULL)
        return o
    }

    private fun targets(): JSONObject {
        val services = JSONArray()
        Prefs.corsServices.forEach { services.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val xray = JSONArray()
        Prefs.xraySavedServers.forEach { xray.put(JSONObject().put("id", it.id).put("name", it.remark).put("address", it.address).put("port", it.port)) }
        return JSONObject().put("services", services).put("xray", xray)
    }

    private fun connect(q: Map<String, String>): Pair<Int, JSONObject> {
        val svc = q["service"]
        val xr = q["xray"]
        val target: ConnectTarget = when {
            svc != null -> {
                val id = svc.toIntOrNull() ?: return err(400, "bad service id")
                ConnectTarget.WhitelistBypass(id, Prefs.corsServices.firstOrNull { it.id == id }?.name ?: "")
            }
            xr != null -> ConnectTarget.Xray(Prefs.xraySavedServers.firstOrNull { it.id == xr } ?: return err(404, "unknown xray id"))
            else -> return err(400, "need ?service= or ?xray=")
        }
        // Same Prefs side effects as MainFragment.onEntrySelected.
        when (target) {
            is ConnectTarget.WhitelistBypass -> {
                Prefs.corsServiceId = target.serviceId
                Prefs.activeDestinationId = ""
                Prefs.connectionMode = ConnectionMode.INSTANCE
            }
            is ConnectTarget.Xray -> {
                Prefs.xrayAutoBest = false
                Prefs.xrayActiveServerId = target.server.id
                Prefs.connectionMode = ConnectionMode.XRAY
            }
            else -> {}
        }
        lastError = null
        captchaUrl = null
        log("DEBUG-CTL connect $target")
        val a = activity()
        if (a != null) main.post { a.onConnectPressed(target) } else launch("connect")
        return 200 to JSONObject().put("ok", true).put("viaActivity", a != null)
    }

    private fun disconnect(): JSONObject {
        log("DEBUG-CTL disconnect")
        val a = activity()
        if (a != null) main.post { a.onDisconnectPressed() } else launch("disconnect")
        return JSONObject().put("ok", true).put("viaActivity", a != null)
    }

    private fun launch(cmd: String) {
        val ctx = bypass.whitelist.App.instance
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CMD, cmd))
    }

    private fun resultJson(r: ConnectionProber.ProbeResult): JSONObject {
        val o = JSONObject().put("name", r.name).put("ok", r.ok).put("ms", r.ms).put("detail", r.detail)
        Regex("""(\d+) B""").find(r.detail)?.groupValues?.get(1)?.toLongOrNull()?.let { b ->
            o.put("bytes", b)
            if (r.ms > 0) o.put("kbitPerSec", b * 8 / r.ms)
        }
        return o
    }

    private fun probe(q: Map<String, String>): JSONObject {
        val download = q["download"]?.toLongOrNull()
        val duration = q["duration"]?.toIntOrNull()?.coerceIn(1, 600)
        val interval = (q["interval"]?.toIntOrNull() ?: 3).coerceIn(1, 60)
        fun once(): JSONObject {
            val p = ConnectionProber.runAll()
            val arr = JSONArray()
            p.results.forEach { arr.put(resultJson(it)) }
            val j = JSONObject().put("ts", p.timestamp).put("socksPort", p.port ?: JSONObject.NULL).put("allOk", p.allOk).put("results", arr)
            if (download != null && download > 0) j.put("bigDownload", resultJson(ConnectionProber.downloadBytes(download)))
            return j
        }
        if (duration == null) return once()
        val series = JSONArray()
        val end = System.currentTimeMillis() + duration * 1000L
        while (System.currentTimeMillis() < end) {
            val t0 = System.currentTimeMillis()
            series.put(once())
            val sleep = interval * 1000L - (System.currentTimeMillis() - t0)
            if (System.currentTimeMillis() + sleep >= end) break
            if (sleep > 0) Thread.sleep(sleep)
        }
        return JSONObject().put("durationSec", duration).put("intervalSec", interval).put("samples", series.length()).put("series", series)
    }

    private fun logs(n: Int): JSONObject {
        val lines = synchronized(ring) { ring.toList().takeLast(n.coerceIn(1, RING_MAX)) }
        return JSONObject().put("count", lines.size).put("lines", JSONArray(lines))
    }
}
