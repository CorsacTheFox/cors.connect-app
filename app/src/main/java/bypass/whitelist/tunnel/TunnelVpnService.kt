package bypass.whitelist.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.Vpn
import bypass.whitelist.xray.XrayConfigBuilder
import bypass.whitelist.xray.XrayEngine
import kotlin.concurrent.thread

/**
 * VpnService for the instance/call flow. The WebRTC join and its local
 * SOCKS5 relay (see `HeadlessRelayController` / `RelayController`) are
 * already running and reachable at `Prefs.socksHost:Prefs.socksPort` by the
 * time this service is asked to start (see [MainActivity.requestVpn]) — this
 * class only owns the device-wide TUN capture and bridges it into that
 * already-running local SOCKS5 proxy.
 *
 * That bridge is [XrayEngine] (the same standard-Xray engine used by
 * [XrayVpnService]) configured with a `socks` outbound pointed at the relay
 * (see [XrayConfigBuilder.buildForLocalUpstream]) — Xray-core's own
 * `protocol: "tun"` inbound reads/writes the TUN fd directly, so no separate
 * native tun2socks binary is needed. (An earlier version of this class called
 * `androidbind.Androidbind.startTun2Socks`, a JNI method whose backing shared
 * library was never actually part of this app's `mobile.aar` — only a
 * subprocess-mode executable, `librelay.so`, ships there, which is what
 * `HeadlessRelayController` runs as a child process for the relay/joiner
 * itself. That JNI call always failed with `UnsatisfiedLinkError`.)
 */
class TunnelVpnService : VpnService() {

    companion object {
        const val TAG = "TunnelVPN"
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "bypass.whitelist.STOP_VPN"

        /**
         * Hard cap on how long a stop may take. `XrayEngine.stop()` ends in a
         * native gomobile call (`CoreController.stopLoop`) that can hang
         * indefinitely on a stuck core shutdown; before this timeout existed
         * a hung stop left `stopInProgress` true forever, which made
         * [TunnelServiceState.isTunnelActive] report "tunnel running" for the
         * rest of the process lifetime — the "only an app restart helps"
         * state. When the cap fires, the stop is declared finished anyway:
         * flags cleared, fd closed, service stopped, disconnect surfaced.
         */
        internal const val STOP_TIMEOUT_MS = 8_000L

        @Volatile var instance: TunnelVpnService? = null
        @Volatile var onDisconnect: Callback? = null

        fun requestStop(context: Context) {
            val running = instance?.let { it.isRunning || it.startInProgress || it.stopInProgress } == true
            val intent = Intent(context, TunnelVpnService::class.java)
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

    /**
     * True when a stop has been in flight for longer than [STOP_TIMEOUT_MS] —
     * i.e. the engine shutdown hung and the flags no longer reflect reality.
     * [TunnelServiceState] treats such a service as not running so a stuck
     * stop can never block the next connect.
     */
    fun isStopStale(): Boolean =
        stopInProgress && stopStartedAtMs > 0L &&
            SystemClock.elapsedRealtime() - stopStartedAtMs > STOP_TIMEOUT_MS

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopTimeoutRunnable: Runnable? = null

    /** Set by the stop thread once the engine shutdown actually returned. */
    @Volatile private var stopFinished = false
    private var rawTunFd: Int? = null

    private val engine: XrayEngine by lazy {
        XrayEngine(onLog = { message -> TunnelServiceState.logCallback?.invoke(message) })
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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
        scheduleStopTimeout(disconnectCallback)

        thread(name = "vpn-stop") {
            try {
                engine.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Xray bridge stop error: ${t.message}", t)
            }
            stopFinished = true
            cancelStopTimeout()
            rawTunFd?.let { closeRawFd(it) }
            rawTunFd = null

            Handler(Looper.getMainLooper()).post {
                try {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    disconnectCallback?.invoke()
                    TunnelServiceState.requestTileRefresh(this@TunnelVpnService)
                    stopSelf()
                } catch (t: Throwable) {
                    Log.e(TAG, "Crash during VPN stop: ${t.message}", t)
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
            Log.e(TAG, "VPN stop timed out after ${STOP_TIMEOUT_MS}ms — forcing cleanup")
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

    private fun start() {
        if (isRunning || startInProgress) return
        startInProgress = true

        startForegroundNotification()

        val builder = Builder()
        VpnBuilder.configure(
            service = this,
            builder = builder,
            sessionName = Vpn.SESSION_NAME,
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

        // Catching Throwable (not just Exception): a broken/missing gomobile
        // native library surfaces as UnsatisfiedLinkError — an Error, not an
        // Exception — which would otherwise crash the whole app here.
        try {
            engine.initEnv(applicationContext.filesDir.resolve("xray"))
            val configJson = XrayConfigBuilder.buildForLocalUpstream(
                host = Prefs.socksHost,
                port = Prefs.socksPort.toInt(),
                user = SocksAuth.user,
                pass = SocksAuth.pass,
                mtu = Vpn.MTU,
            )
            if (!engine.start(configJson, fd)) {
                startInProgress = false
                closeRawFd(fd)
                rawTunFd = null
                TunnelServiceState.logCallback?.invoke("Failed to start tunnel bridge")
                TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
                stopSelf()
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Tunnel bridge start crashed: ${t.message}", t)
            startInProgress = false
            closeRawFd(fd)
            rawTunFd = null
            TunnelServiceState.logCallback?.invoke("Tunnel bridge error: ${t.message}")
            TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_FAILED)
            stopSelf()
            return
        }

        isRunning = true
        startInProgress = false
        Log.i(TAG, "VPN established, fd=$fd, SOCKS5 ${SocksAuth.user}:${SocksAuth.pass}@${Prefs.socksHost}:${Prefs.socksPort}")
        updateStatus(VpnStatus.TUNNEL_ACTIVE)
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
                CHANNEL_ID, "VPN Tunnel", NotificationManager.IMPORTANCE_LOW
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
        val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
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
            .setContentTitle(getString(R.string.notification_vpn_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_disconnect), stopPending).build())
            .build()
    }
}
