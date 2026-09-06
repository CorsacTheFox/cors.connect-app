package bypass.whitelist.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import bypass.whitelist.MainActivity
import bypass.whitelist.R
import bypass.whitelist.util.Callback
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.Vpn
import bypass.whitelist.xray.XrayConfigBuilder
import bypass.whitelist.xray.XrayEngine
import bypass.whitelist.xray.XrayServer
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * VpnService for the standard Xray connection mode. Captures the device TUN
 * the same way [TunnelVpnService] does (via [VpnBuilder], sharing the same
 * global split tunneling config — see [Prefs.splitTunnelingMode]) and hands
 * the resulting file descriptor straight to [XrayEngine], which runs
 * Xray-core in-process against the active [Prefs.activeXrayServer].
 *
 * Xray-core's own `protocol: "tun"` inbound (see [XrayConfigBuilder]) reads
 * and writes that fd directly — there's no separate tun2socks bridge or
 * local SOCKS5 listener involved.
 *
 * Because Xray-core reports a started inbound regardless of whether the
 * remote server actually accepts traffic, [start] alone can't tell a working
 * tunnel from a dead one (symptom: Android shows "connected to VPN" while
 * the device has no internet). [runTunnelHealthCheck] closes that gap with a
 * real request dialed through the running core; on failure the VPN is
 * disconnected and the reason surfaced via [TunnelServiceState.logCallback].
 */
class XrayVpnService : VpnService() {

    companion object {
        const val TAG = "XrayVPN"
        const val CHANNEL_ID = "xray_channel"
        const val NOTIFICATION_ID = 4
        const val ACTION_STOP = "bypass.whitelist.STOP_XRAY"

        /**
         * 204/200 endpoints used to verify the tunnel passes traffic. The
         * FIRST entry must be reachable from any exit location: an exit in a
         * sanctioned/filtered country (e.g. an Iranian server IP) cannot
         * reach Google endpoints, which would falsely fail a working tunnel —
         * so Microsoft's connectivity check runs before gstatic, and the
         * first success is enough.
         */
        private val HEALTH_CHECK_URLS = arrayOf(
            "http://www.msftconnecttest.com/connecttest.txt", // 200
            "http://www.gstatic.com/generate_204",             // 204
        )

        /** Grace period before the check so a just-started core settles first. */
        private const val HEALTH_CHECK_SETTLE_MS = 1_200L

        /** Timeout for the pre-flight raw TCP probe of the server itself. */
        private const val SERVER_PROBE_TIMEOUT_MS = 4_000

        /**
         * How often the keep-alive watchdog re-checks the tunnel with a cheap
         * 204 request through the running core. Deliberately minutes-scaled:
         * the request itself is tiny, but frequent wakeups keep the radio/CPU
         * out of deep sleep and drain the battery on long sessions.
         */
        private const val WATCHDOG_INTERVAL_MS = 120_000L

        /**
         * Consecutive watchdog failures before an automatic reconnect is
         * attempted. One missed check (radio idle, brief congestion) must not
         * tear down a working tunnel; three in a row (~6 minutes of silence)
         * means the tunnel is really stuck.
         */
        private const val WATCHDOG_FAILURES_TO_RESTART = 3

        /** Attempts per watchdog cycle before the cycle counts as failed. */
        private const val WATCHDOG_CHECK_RETRIES = 3

        /** Pause between the retries inside one watchdog cycle. */
        private const val WATCHDOG_RETRY_DELAY_MS = 5_000L

        /**
         * registerNetworkCallback fires onAvailable immediately for every
         * already-connected network — that instant callback races the initial
         * health check and used to trigger pointless verify/restart cycles
         * right after connect. Callbacks in this window only resume the
         * watchdog; real change events (Wi-Fi ↔ mobile switch) arrive later.
         */
        private const val NETWORK_CALLBACK_GRACE_MS = 5_000L

        /**
         * Hard cap on how long a stop may take (see [stop]): a hung native
         * `stopLoop` must not leave `stopInProgress` true forever — that made
         * `TunnelServiceState.isTunnelActive` report "tunnel running" until
         * the process was restarted.
         */
        internal const val STOP_TIMEOUT_MS = 8_000L

        @Volatile var instance: XrayVpnService? = null
        @Volatile var onDisconnect: Callback? = null

        fun requestStop(context: Context) {
            val running = instance?.let { it.isRunning || it.startInProgress || it.stopInProgress } == true
            val intent = Intent(context, XrayVpnService::class.java)
            try {
                if (running) {
                    context.startService(intent.apply { action = ACTION_STOP })
                } else {
                    context.stopService(intent)
                    TunnelServiceState.requestTileRefresh(context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "requestStop failed: ${t.message}")
            }
        }
    }

    @Volatile var isRunning: Boolean = false
    @Volatile internal var startInProgress: Boolean = false
    @Volatile internal var stopInProgress: Boolean = false

    /** When the current stop began (elapsedRealtime) — 0 while none is running. */
    @Volatile internal var stopStartedAtMs: Long = 0L

    /** True when a stop has outlived [STOP_TIMEOUT_MS] (hung engine shutdown). */
    fun isStopStale(): Boolean =
        stopInProgress && stopStartedAtMs > 0L &&
            SystemClock.elapsedRealtime() - stopStartedAtMs > STOP_TIMEOUT_MS

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopTimeoutRunnable: Runnable? = null

    /** Set by the stop thread once the engine shutdown actually returned. */
    @Volatile private var stopFinished = false
    private var rawTunFd: Int? = null

    /**
     * Set when the tunnel should come back up after [stop] finishes (watchdog
     * or network-change reconnect). Cleared by user-initiated stops — only
     * [requestRestart] ever sets it.
     */
    @Volatile private var pendingRestart: Boolean = false

    /** True while the device has no underlying network (watchdog pauses then). */
    @Volatile private var underlyingNetworkLost: Boolean = false

    private val healthFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private var watchdogThread: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** When registerNetworkCallback ran (elapsedRealtime) — see the grace window above. */
    private var networkCallbackRegisteredAtMs: Long = 0L

    private val engine: XrayEngine by lazy {
        XrayEngine(onLog = { message -> TunnelServiceState.logCallback?.invoke(message) })
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Explicit user disconnect always wins over a pending watchdog
            // reconnect that may have raced with the button press.
            pendingRestart = false
            if (!isRunning && !startInProgress && !stopInProgress) {
                safeStopSelf()
                return START_NOT_STICKY
            }
            stop()
            return START_NOT_STICKY
        }
        start()
        return START_STICKY
    }

    override fun onDestroy() {
        pendingRestart = false
        stopWatchdog()
        unregisterNetworkCallback()
        if ((isRunning || startInProgress) && !stopInProgress) {
            stop()
        }
        if (instance === this) {
            instance = null
        }
        onDisconnect = null
        super.onDestroy()
    }

    fun updateStatus(status: VpnStatus) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(getString(status.labelRes)))
        TunnelServiceState.vpnStatusCallback?.invoke(status)
        TunnelServiceState.requestTileRefresh(this)
    }

    @Synchronized
    fun stop() {
        // A previously timed-out stop left stale flags — recover them instead
        // of returning early forever.
        if (stopInProgress && !isStopStale()) return
        if (stopInProgress) {
            Log.w(TAG, "Previous stop is stale (engine shutdown hung) — forcing state cleanup")
        }
        if (!isRunning && !startInProgress) {
            safeStopSelf()
            return
        }
        isRunning = false
        startInProgress = false
        stopInProgress = true
        stopStartedAtMs = SystemClock.elapsedRealtime()
        stopFinished = false
        val disconnectCallback = onDisconnect
        // A user disconnect must never resurrect the tunnel — only the
        // watchdog/network path sets pendingRestart, and it stays set only
        // for the stop it scheduled itself.
        val restartAfterStop = pendingRestart
        pendingRestart = false
        stopWatchdog()
        unregisterNetworkCallback()
        scheduleStopTimeout(disconnectCallback)

        thread(name = "xray-vpn-stop") {
            try {
                engine.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Xray stop error: ${t.message}", t)
            }
            stopFinished = true
            cancelStopTimeout()
            rawTunFd?.let { closeRawFd(it) }
            rawTunFd = null

            Handler(Looper.getMainLooper()).post {
                try {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    if (restartAfterStop) {
                        // Reconnect path: re-enter start() on the main thread
                        // exactly as onStartCommand would. start() re-reads
                        // Prefs.activeXrayServer, so the reconnect always
                        // uses the currently selected profile, and its own
                        // health check disconnects (without another retry)
                        // if the server is genuinely gone — no restart loop.
                        Log.i(TAG, "Reconnecting Xray tunnel after watchdog/network failure")
                        TunnelServiceState.logCallback?.invoke("Reconnecting Xray tunnel…")
                        stopInProgress = false
                        start()
                    } else {
                        disconnectCallback?.invoke()
                        TunnelServiceState.requestTileRefresh(this@XrayVpnService)
                        stopSelf()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Crash during Xray VPN stop: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Safety net for a hung engine shutdown: if the stop hasn't completed
     * within [STOP_TIMEOUT_MS], declare it finished anyway — clear the flags,
     * close the TUN fd, stop the service and surface the disconnect — so the
     * app never gets stuck in "tunnel is stopping" until a process restart.
     */
    private fun scheduleStopTimeout(disconnectCallback: Callback?) {
        cancelStopTimeout()
        val runnable = Runnable {
            if (!stopInProgress || stopFinished) return@Runnable
            Log.e(TAG, "Xray VPN stop timed out after ${STOP_TIMEOUT_MS}ms — forcing cleanup")
            TunnelServiceState.logCallback?.invoke("Tunnel stop timed out — forcing cleanup")
            val fd = rawTunFd
            rawTunFd = null
            fd?.let { closeRawFd(it) }
            safeStopSelf()
            disconnectCallback?.invoke()
        }
        stopTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, STOP_TIMEOUT_MS)
    }

    private fun cancelStopTimeout() {
        mainHandler.post {
            stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            stopTimeoutRunnable = null
        }
    }

    /**
     * Tears the tunnel down and brings it straight back up. Safe to call from
     * any thread; if a stop is already in flight the restart piggybacks on it.
     */
    private fun requestRestart(reason: String) {
        if (!isRunning && !startInProgress) return
        Log.w(TAG, "Tunnel restart requested: $reason")
        TunnelServiceState.logCallback?.invoke("Tunnel unstable ($reason) — reconnecting")
        pendingRestart = true
        stop()
    }

    private fun start() {
        if (isRunning || startInProgress) return
        val server = Prefs.activeXrayServer
        if (server == null) {
            Log.e(TAG, "No active Xray server selected")
            TunnelServiceState.logCallback?.invoke("No active Xray server selected")
            TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
            stopSelf()
            return
        }
        startInProgress = true

        startForegroundNotification()

        val builder = Builder()
        // Split tunneling is shared globally across both connection types —
        // see Prefs.splitTunnelingMode/Packages — so instance and Xray
        // connections always apply the same per-app rules.
        VpnBuilder.configure(
            service = this,
            builder = builder,
            sessionName = "${Vpn.SESSION_NAME}Xray",
            ownPackageName = packageName,
            splitTunnelingMode = Prefs.splitTunnelingMode,
            splitTunnelingPackages = Prefs.splitTunnelingPackages,
            logTag = TAG,
        )

        val establishedFd = builder.establish()
        if (establishedFd == null) {
            Log.e(TAG, "Failed to establish VPN")
            startInProgress = false
            TunnelServiceState.logCallback?.invoke("Failed to establish VPN")
            TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
            stopSelf()
            return
        }
        val fd = establishedFd.detachFd()
        rawTunFd = fd

        // Everything below either touches the gomobile-bound libv2ray classes
        // or hands the fd to Xray-core's native tun inbound — both can throw
        // types that aren't plain Exception (UnsatisfiedLinkError,
        // NoSuchMethodError). Catching Throwable here turns any of that into
        // a recoverable "connection failed" state instead of a process crash.
        try {
            engine.initEnv(applicationContext.filesDir.resolve("xray"))
            val configJson = XrayConfigBuilder.buildForXrayServer(server, Vpn.MTU)
            if (!engine.start(configJson, fd)) {
                startInProgress = false
                closeRawFd(fd)
                rawTunFd = null
                TunnelServiceState.logCallback?.invoke("Failed to start Xray core")
                TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
                stopSelf()
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Xray engine start crashed: ${t.message}", t)
            startInProgress = false
            closeRawFd(fd)
            rawTunFd = null
            TunnelServiceState.logCallback?.invoke("Xray engine error: ${t.message}")
            TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
            stopSelf()
            return
        }

        isRunning = true
        startInProgress = false
        Log.i(TAG, "Xray VPN established, fd=$fd, server=${server.summary}")
        TunnelServiceState.logCallback?.invoke("Xray profile: ${profileSummary(server)}")
        updateStatus(VpnStatus.TUNNEL_ACTIVE)
        registerNetworkCallback()
        startWatchdog()
        runTunnelHealthCheck(server)
    }

    /**
     * One-line, secret-free summary of how the server was parsed (protocol /
     * transport / security plus which optional fields are set) — logged at
     * connect so a share-link field the parser dropped is visible in the Logs
     * tab when a handshake fails. Credentials (uuid/password/publicKey/shortId)
     * are never included, only whether they're present.
     */
    private fun profileSummary(server: XrayServer): String = buildString {
        append(server.protocol.wireValue)
        append('/').append(server.network.wireValue)
        append(' ').append(server.security.wireValue)
        server.flow.takeIf { it.isNotBlank() }?.let { append(" flow=").append(it) }
        server.sni.takeIf { it.isNotBlank() }?.let { append(" sni=").append(it) }
        server.fingerprint.takeIf { it.isNotBlank() }?.let { append(" fp=").append(it) }
        server.alpn.takeIf { it.isNotBlank() }?.let { append(" alpn=").append(it) }
        server.publicKey.takeIf { it.isNotBlank() }?.let { append(" pbk=present") }
        server.shortId.takeIf { it.isNotBlank() }?.let { append(" sid=present") }
        server.path.takeIf { it.isNotBlank() }?.let { append(" path=").append(it) }
        server.host.takeIf { it.isNotBlank() }?.let { append(" host=").append(it) }
        server.serviceName.takeIf { it.isNotBlank() }?.let { append(" svc=").append(it) }
        server.headerType.takeIf { it.isNotBlank() }?.let { append(" header=").append(it) }
        server.grpcMode.takeIf { it.isNotBlank() }?.let { append(" mode=").append(it) }
        server.extra.takeIf { it.isNotBlank() }?.let { append(" extra=").append(it) }
        server.allowInsecure.takeIf { it }?.let { append(" allowInsecure") }
    }

    /**
     * Measures the RTT of a full HTTPS GET dialed through the running core
     * (same mechanism as the health check / watchdog). Returns null when the
     * request fails or the tunnel isn't up — used by the UI's per-service
     * availability checks, which run in the app process (excluded from the
     * VPN) and therefore can't measure the tunnel with plain sockets.
     */
    fun measureThroughTunnel(url: String): Long? {
        if (!isRunning) return null
        return try {
            engine.measureDelay(url)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Verifies the just-established tunnel actually passes traffic, in two
     * stages with distinct log attribution:
     *
     * 1. A raw TCP probe of the server itself, from the physical network (the
     *    app's UID is excluded from the VPN, so this socket takes the exact
     *    path the core's outbound uses). Failure here means the server is
     *    unreachable from this device/network — nothing Xray-side can fix it.
     * 2. HTTP requests dialed *through the running core* (see
     *    [XrayEngine.measureDelay]) against [HEALTH_CHECK_URLS]; the first
     *    success passes. If the server is reachable but every URL fails, the
     *    proxy handshake or forwarding is broken (bad parameters, server
     *    rejecting the client, dead relay).
     *
     * Either stage failing disconnects the VPN instead of leaving the user
     * with Android showing "connected" over a silent black hole. The
     * isRunning/stopInProgress guards keep a user-initiated disconnect during
     * the probe from being reported as a failure.
     */
    private fun runTunnelHealthCheck(server: XrayServer) {
        thread(name = "xray-health-check") {
            try {
                Thread.sleep(HEALTH_CHECK_SETTLE_MS)
                if (!isRunning || stopInProgress) return@thread

                val probeError = probeServerTcp(server)
                if (probeError != null) {
                    failHealthCheck("server ${server.summary} unreachable from this network ($probeError)")
                    return@thread
                }
                TunnelServiceState.logCallback?.invoke("Xray server ${server.summary} reachable (TCP)")

                var errors = listOf<String>()
                for (url in HEALTH_CHECK_URLS) {
                    val rtt = try {
                        engine.measureDelay(url)
                    } catch (t: Throwable) {
                        if (!isRunning || stopInProgress) return@thread
                        errors += "${Uri.parse(url).host ?: url}: ${t.message ?: "request failed"}"
                        continue
                    }
                    if (!isRunning || stopInProgress) return@thread
                    Log.i(TAG, "Tunnel health check OK (${rtt}ms via $url)")
                    TunnelServiceState.logCallback?.invoke("Xray tunnel check OK: ${server.summary} (${rtt} ms)")
                    return@thread
                }
                failHealthCheck("tunnel passes no traffic via ${server.summary} — ${errors.joinToString("; ")}")
            } catch (t: Throwable) {
                if (!isRunning || stopInProgress) return@thread
                failHealthCheck(t.message ?: "health check crashed")
            }
        }
    }

    /**
     * Connects a plain TCP socket to the server (physical network — the app's
     * UID is excluded from the VPN). Returns null on success or the failure
     * reason, mirroring the app's existing probe style (see MainActivity's
     * `probeViaSocks5`).
     */
    private fun probeServerTcp(server: XrayServer): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.address, server.port), SERVER_PROBE_TIMEOUT_MS)
            null
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun failHealthCheck(reason: String) {
        if (!isRunning || stopInProgress) return
        Log.e(TAG, "Tunnel health check failed: $reason")
        TunnelServiceState.logCallback?.invoke("Xray tunnel check failed: $reason — disconnecting")
        updateStatus(VpnStatus.CALL_FAILED)
        stop()
    }

    /**
     * Keep-alive watchdog: periodically dials one cheap 204 request through
     * the running core. This catches the "connected but silently dead" state
     * (server dropped the session, NAT mapping expired mid-connection) that
     * Android never notices on its own — without it the user sees a live VPN
     * notification over a black hole until they manually reconnect.
     *
     * Checks are skipped while the device has no underlying network at all
     * ([underlyingNetworkLost]) so a tunnel idle during a network gap isn't
     * torn down for the wrong reason; the moment connectivity returns, the
     * callback clears the flag and normal checking resumes.
     */
    private fun startWatchdog() {
        stopWatchdog()
        healthFailures.set(0)
        watchdogThread = thread(name = "xray-watchdog") {
            while (isRunning && !stopInProgress) {
                // stopWatchdog() interrupts this sleep on disconnect — that
                // must exit the loop, not propagate an uncaught
                // InterruptedException (any uncaught exception in any thread
                // kills the whole app, which is exactly the disconnect crash).
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!isRunning || stopInProgress) return@thread
                if (underlyingNetworkLost) continue

                val ok = checkTunnelOnceWithRetry()
                if (!isRunning || stopInProgress) return@thread

                if (ok) {
                    healthFailures.set(0)
                } else {
                    val failures = healthFailures.incrementAndGet()
                    Log.w(TAG, "Watchdog check failed ($failures/$WATCHDOG_FAILURES_TO_RESTART)")
                    if (failures >= WATCHDOG_FAILURES_TO_RESTART) {
                        requestRestart("no traffic for ${failures} checks")
                        return@thread
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /**
     * One watchdog verdict with a short retry: a single transient
     * [XrayEngine.measureDelay] hiccup (radio idle, brief congestion, a
     * gomobile binding stall) must not count towards the restart threshold —
     * those single blips accumulated into "random" disconnects.
     */
    private fun checkTunnelOnceWithRetry(): Boolean {
        repeat(WATCHDOG_CHECK_RETRIES) { attempt ->
            val ok = try {
                engine.measureDelay(HEALTH_CHECK_URLS[0])
                true
            } catch (t: Throwable) {
                false
            }
            if (ok) return true
            if (!isRunning || stopInProgress || attempt == WATCHDOG_CHECK_RETRIES - 1) return false
            try {
                Thread.sleep(WATCHDOG_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Tracks the *physical* network under the VPN. Two jobs:
     *  - onLost: pause the watchdog (nothing can pass traffic without an
     *    underlying network — restarting then would just churn).
     *  - onAvailable: connectivity came back (or switched Wi-Fi ↔ mobile);
     *    existing Xray connections are bound to the dead path, so probe the
     *    server and reconnect the tunnel if the core can't re-establish on
     *    its own.
     */
    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlyingNetworkLost = false
                if (SystemClock.elapsedRealtime() - networkCallbackRegisteredAtMs < NETWORK_CALLBACK_GRACE_MS) {
                    Log.i(TAG, "Underlying network available (initial callback) — skipping verify")
                    return
                }
                Log.i(TAG, "Underlying network available — resuming watchdog checks")
                verifyTunnelAfterNetworkChange()
            }

            override fun onLost(network: Network) {
                underlyingNetworkLost = true
                healthFailures.set(0)
                Log.w(TAG, "Underlying network lost — watchdog paused")
                TunnelServiceState.logCallback?.invoke("Network lost — waiting for connectivity")
            }
        }
        try {
            networkCallbackRegisteredAtMs = SystemClock.elapsedRealtime()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        underlyingNetworkLost = false
    }

    /**
     * Runs shortly after connectivity returns: gives the core a moment to
     * re-dial, then checks the tunnel end-to-end once and reconnects if it
     * stayed dead. Only meaningful while the tunnel is up.
     */
    private fun verifyTunnelAfterNetworkChange() {
        thread(name = "xray-net-verify") {
            Thread.sleep(HEALTH_CHECK_SETTLE_MS)
            if (!isRunning || stopInProgress) return@thread
            val server = Prefs.activeXrayServer ?: return@thread
            if (probeServerTcp(server) != null) {
                Log.w(TAG, "Server unreachable on new network — reconnecting")
                requestRestart("network changed, server unreachable")
                return@thread
            }
            // The core may still be re-dialing on the new path — retry a few
            // times before tearing the tunnel down, a Wi-Fi ↔ mobile flap
            // doesn't have to kill an otherwise recoverable session.
            var ok = false
            var attempts = 0
            while (!ok && attempts < 3 && (isRunning && !stopInProgress)) {
                if (attempts > 0) Thread.sleep(2_000L)
                ok = try {
                    engine.measureDelay(HEALTH_CHECK_URLS[0])
                    true
                } catch (t: Throwable) {
                    false
                }
                attempts++
            }
            if (!isRunning || stopInProgress) return@thread
            if (!ok) {
                requestRestart("network changed, tunnel passes no traffic")
            } else {
                TunnelServiceState.logCallback?.invoke("Network changed — tunnel still OK")
            }
        }
    }

    private fun closeRawFd(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            .onFailure { Log.w(TAG, "Failed to close tun fd=$fd: ${it.message}") }
    }

    private fun safeStopSelf() {
        stopInProgress = false
        stopStartedAtMs = 0L
        stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        stopTimeoutRunnable = null
        isRunning = false
        startInProgress = false
        runCatching {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        TunnelServiceState.requestTileRefresh(this)
        stopSelf()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Xray Tunnel", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = buildNotification(getString(VpnStatus.STARTING.labelRes))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, XrayVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_xray_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_disconnect), stopPending).build())
            .build()
    }
}
