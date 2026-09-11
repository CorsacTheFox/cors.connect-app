package bypass.whitelist

import android.Manifest
import android.animation.ArgbEvaluator
import android.content.ClipData
import android.content.ClipboardManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.ConnectTarget
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.tunnel.HeadlessJoinController
import bypass.whitelist.tunnel.HeadlessSessionService
import bypass.whitelist.tunnel.PortGuard
import bypass.whitelist.tunnel.ProxyService
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.TunnelServiceState
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.tunnel.XrayVpnService
import bypass.whitelist.ui.AddXraySubscriptionSheet
import bypass.whitelist.ui.CallsListener
import bypass.whitelist.ui.ConfirmActionSheet
import bypass.whitelist.ui.HeadlessVkFragment
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.ui.JoinSessionShutdown
import bypass.whitelist.ui.JsHookJoinFragment
import bypass.whitelist.ui.LogsFragment
import bypass.whitelist.ui.MainActivityHost
import bypass.whitelist.ui.MainFragment
import bypass.whitelist.ui.OnboardingFragment
import bypass.whitelist.ui.SettingsScreenFragment
import bypass.whitelist.ui.UpdateActionSheet
import bypass.whitelist.ui.XrayServersListener
import bypass.whitelist.ui.XraySubscriptionsScreenFragment
import bypass.whitelist.util.AppUpdater
import bypass.whitelist.util.BatteryOptimizer
import bypass.whitelist.util.LogWriter
import bypass.whitelist.util.Net
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.maskUrl
import bypass.whitelist.xray.XrayServer
import cc.cors.connect.api.CorsClient
import cc.cors.connect.cors.CorsInstanceController
import cc.cors.connect.cors.LinkAuth
import cc.cors.connect.cors.TelegramAuth
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity :
    AppCompatActivity(),
    JoinFragmentHost,
    MainActivityHost,
    MainFragment.Host,
    OnboardingFragment.Host,
    SettingsScreenFragment.Host,
    LogsFragment.Host,
    CallsListener,
    XrayServersListener,
    LinkAuth.Listener {

    private val logWriter by lazy { LogWriter(cacheDir) }

    private lateinit var bottomNav: View
    private lateinit var navMain: LinearLayout
    private lateinit var navServers: LinearLayout
    private lateinit var navSettings: LinearLayout
    private lateinit var navLogs: LinearLayout
    private lateinit var navMainIcon: ImageView
    private lateinit var navServersIcon: ImageView
    private lateinit var navSettingsIcon: ImageView
    private lateinit var navLogsIcon: ImageView
    private lateinit var navMainLabel: TextView
    private lateinit var navServersLabel: TextView
    private lateinit var navSettingsLabel: TextView
    private lateinit var navLogsLabel: TextView
    private lateinit var tabContainer: ViewPager2
    private lateinit var navIndicator: View
    private lateinit var subPageContainer: View
    private lateinit var joinOverlayContainer: View
    private lateinit var overlayLogs: View
    private lateinit var overlayLogsText: TextView
    private lateinit var overlayLogsScroll: ScrollView

    private var currentTabId: Int = 0
    private var lastStatus: VpnStatus? = null
    private var connected: Boolean = false
    private var activeJoinUrl: String = ""
    private var activeHeadlessController: HeadlessJoinController? = null
    private var corsClient: CorsClient = CorsClient()
    private var corsController: CorsInstanceController? = null
    private var corsPendingOutput: CallConfig? = null
    private var navPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var navScrollState: Int = ViewPager2.SCROLL_STATE_IDLE
    @Volatile private var resetInProgress: Boolean = false
    @Volatile private var overlayVisible: Boolean = false
    @Volatile private var resetGeneration: Long = 0L
    @Volatile private var restartScheduled: Boolean = false
    private var pendingConnectTarget: ConnectTarget? = null
    @Volatile private var pendingVpnStart: (() -> Unit)? = null
    private val navColorEvaluator = ArgbEvaluator()

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingVpnStart
        pendingVpnStart = null
        if (result.resultCode == RESULT_OK) (action ?: ::startVpnService)()
        else appendLog("VPN permission denied")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) appendLog("Notification permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()
        reportLastCrashIfAny()
        // Hold update / battery nags until the user is past first-run onboarding.
        // Deferred to a post so the sheet lands after the first frame — showing
        // it straight from onCreate used to no-op on some devices.
        if (Prefs.onboardingDone) {
            window.decorView.post {
                if (isFinishing || isDestroyed) return@post
                maybeCheckForUpdates()
                maybeRemindBatteryOptimization()
            }
        }

        bottomNav = findViewById(R.id.bottomNav)
        navMain = findViewById(R.id.navMain)
        navServers = findViewById(R.id.navServers)
        navSettings = findViewById(R.id.navSettings)
        navLogs = findViewById(R.id.navLogs)
        navMainIcon = findViewById(R.id.navMainIcon)
        navServersIcon = findViewById(R.id.navServersIcon)
        navSettingsIcon = findViewById(R.id.navSettingsIcon)
        navLogsIcon = findViewById(R.id.navLogsIcon)
        navMainLabel = findViewById(R.id.navMainLabel)
        navServersLabel = findViewById(R.id.navServersLabel)
        navSettingsLabel = findViewById(R.id.navSettingsLabel)
        navLogsLabel = findViewById(R.id.navLogsLabel)
        tabContainer = findViewById(R.id.tabContainer)
        navIndicator = findViewById(R.id.navIndicator)
        subPageContainer = findViewById(R.id.subPageContainer)
        // System Back pops the FragmentManager stack one entry at a time; the
        // listener keeps the sub-page layer in sync when it runs empty.
        supportFragmentManager.addOnBackStackChangedListener { syncSubPageVisibility() }
        joinOverlayContainer = findViewById(R.id.joinOverlayContainer)
        overlayLogs = findViewById(R.id.overlayLogs)
        overlayLogsText = findViewById(R.id.overlayLogsText)
        overlayLogsScroll = findViewById(R.id.overlayLogsScroll)

        tabContainer.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 4
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    TAB_MAIN -> MainFragment()
                    TAB_SERVERS -> XraySubscriptionsScreenFragment.newRoot()
                    TAB_LOGS -> LogsFragment()
                    else -> SettingsScreenFragment()
                }
            }
        }
        tabContainer.offscreenPageLimit = 4

        navPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                moveNavIndicatorForPager(position, positionOffset)
                interpolateNavSelection(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                // If a page change slips through while a sub-page is open
                // (e.g. programmatic), close the sub-page so it doesn't
                // linger on top of the newly selected tab.
                if (subPageContainer.visibility == View.VISIBLE) {
                    dismissSubPage()
                }
                currentTabId = when (position) {
                    TAB_MAIN -> R.id.navMain
                    TAB_SERVERS -> R.id.navServers
                    TAB_LOGS -> R.id.navLogs
                    TAB_SETTINGS -> R.id.navSettings
                    else -> return
                }
                if (navScrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    updateNavSelection(currentTabId)
                }
                settingsFragment()?.refresh()
            }

            override fun onPageScrollStateChanged(state: Int) {
                navScrollState = state
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    updateNavSelection(currentTabId)
                    moveNavIndicatorTo(currentTabId, animate = false)
                }
            }
        }.also(tabContainer::registerOnPageChangeCallback)

        findViewById<View>(R.id.overlayCopyButton).setOnClickListener { copyLogs() }
        findViewById<View>(R.id.overlayShareButton).setOnClickListener { shareLogs() }

        val baseTabPaddingTop = findViewById<View>(R.id.tabContainerWrap).paddingTop
        val bottomWrap = findViewById<View>(R.id.bottomWrap)
        val baseBottomWrapPaddingBottom = bottomWrap.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.tabContainerWrap).setPadding(
                bars.left,
                baseTabPaddingTop + bars.top,
                bars.right,
                0
            )
            subPageContainer.setPadding(bars.left, bars.top, bars.right, 0)
            joinOverlayContainer.setPadding(bars.left, bars.top, bars.right, 0)
            bottomWrap.setPadding(
                bars.left,
                0,
                bars.right,
                baseBottomWrapPaddingBottom + bars.bottom
            )
            insets
        }

        navMain.setOnClickListener { selectNavTab(R.id.navMain) }
        navServers.setOnClickListener { selectNavTab(R.id.navServers) }
        navLogs.setOnClickListener { selectNavTab(R.id.navLogs) }
        navSettings.setOnClickListener { selectNavTab(R.id.navSettings) }

        val restoredTabId =
            savedInstanceState?.getInt(STATE_CURRENT_TAB_ID, R.id.navMain) ?: R.id.navMain
        selectNavTab(restoredTabId, animatePager = false)
        findViewById<View>(R.id.navItemsRow).doOnLayout {
            moveNavIndicatorTo(currentTabId, animate = false)
        }

        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        XrayVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }

        if (!Prefs.onboardingDone && savedInstanceState == null) {
            showOnboarding()
        } else if (CALL_LINK.isNotEmpty() && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            startJoinFor(CallConfig.newWith(name = CallConfig.suggestNameFor(CALL_LINK), url = CALL_LINK))
        } else if (Prefs.connectOnStart && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            Prefs.activeDestination?.let(::startJoinFor)
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        XrayVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }

        TunnelServiceState.vpnStatusCallback = { status ->
            runOnUiThread {
                if (resetInProgress) {
                    mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
                    mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
                    return@runOnUiThread
                }
                lastStatus = status
                mainFragment()?.onStatusChanged(status)
                if (status == VpnStatus.TUNNEL_ACTIVE) {
                    if (!connected) {
                        connected = true
                        mainFragment()?.onConnectedChanged(true)
                    }
                } else if (status == VpnStatus.CALL_FAILED || status == VpnStatus.CALL_DISCONNECTED || status == VpnStatus.TUNNEL_LOST) {
                    if (connected) {
                        connected = false
                        mainFragment()?.onConnectedChanged(false)
                    }
                }
            }
        }

        TunnelServiceState.logCallback = { message ->
            runOnUiThread { appendLog(message) }
        }

        when {
            resetInProgress -> {
                connected = false
                lastStatus = VpnStatus.STOPPING
                mainFragment()?.onConnectedChanged(false)
                mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
                mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
            }
            TunnelServiceState.isTunnelActive(this) -> {
                if (!connected || lastStatus != VpnStatus.TUNNEL_ACTIVE) {
                    connected = true
                    lastStatus = VpnStatus.TUNNEL_ACTIVE
                    mainFragment()?.onStatusChanged(VpnStatus.TUNNEL_ACTIVE)
                    mainFragment()?.onConnectedChanged(true)
                }
            }
            TunnelServiceState.isHeadlessSessionRunning(this) -> {
                connected = false
                lastStatus = VpnStatus.CONNECTING
                mainFragment()?.onConnectedChanged(false)
                mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
            }
            connected && lastStatus == VpnStatus.TUNNEL_ACTIVE -> {
                onDisconnectFromService()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        TunnelServiceState.vpnStatusCallback = null
        TunnelServiceState.logCallback = null
    }

    override fun onDestroy() {
        navPageChangeCallback?.let(tabContainer::unregisterOnPageChangeCallback)
        navPageChangeCallback = null
        TunnelVpnService.onDisconnect = null
        ProxyService.onDisconnect = null
        XrayVpnService.onDisconnect = null
        logWriter.close()
        // Stop the Cors instance when the Activity is truly going away (back
        // press / process death), so the backend DELETEs it instead of leaving
        // an orphan that a later reconnect would duplicate. Skipped during a
        // configuration change (isFinishing == false), where the instance should
        // survive the recreation.
        if (isFinishing) {
            corsController?.stop()
            corsController = null
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB_ID, currentTabId)
    }

    override fun onConnectPressed(target: ConnectTarget) {
        when (target) {
            is ConnectTarget.WhitelistBypass -> startCorsConnect()
            is ConnectTarget.Instance -> onConnectPressed(target.config)
            is ConnectTarget.Xray -> startXrayConnect(target.server)
        }
    }

    private fun onConnectPressed(config: CallConfig) {
        if (resetInProgress) {
            pendingConnectTarget = ConnectTarget.Instance(config)
            appendLog("Queued connect after previous session stops")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
            return
        }
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
            pendingConnectTarget = ConnectTarget.Instance(config)
            appendLog("Waiting for previous local tunnel to stop")
            fullReset()
            return
        }
        startJoinFor(config)
    }

    override fun onDisconnectPressed() {
        pendingConnectTarget = null
        if (resetInProgress) {
            forceUnlockReset("Stopped waiting for previous session")
            return
        }
        fullReset()
    }

    override fun onServiceCheckPressed(callback: (List<MainFragment.ServiceStatus>) -> Unit) {
        thread {
            // Sequential on purpose: each service is reported as soon as its
            // probe finishes, so the dialog fills row by row instead of
            // dumping everything at the end. A bounded parallel pool was
            // tried before, but simultaneous TLS handshakes through a single
            // proxy outbound caused timeouts for perfectly reachable hosts.
            val results = mutableListOf<MainFragment.ServiceStatus>()
            for ((_, host) in MainFragment.SERVICE_TARGETS) {
                results.add(checkServiceWithRetry(host))
                val snapshot = results.toList()
                runOnUiThread { callback(snapshot) }
            }
        }
    }

    /**
     * One availability row: a real TCP ping to host:443 *through the active
     * tunnel*, up to three attempts with a short backoff. A single attempt
     * through a mobile proxy is noisy — slow TLS handshakes, momentary resets
     * and rate-limiting all fail the first try even when the service is
     * reachable — so the retries keep those from being reported as
     * "unavailable". The reported RTT is the time to complete the SOCKS5
     * CONNECT to the destination, i.e. an actual round-trip over the tunnel.
     */
    private fun checkServiceWithRetry(host: String): MainFragment.ServiceStatus {
        var rttMs: Int? = null
        repeat(3) { attempt ->
            if (rttMs == null) {
                if (attempt > 0) Thread.sleep(300)
                rttMs = measureServiceRtt(host)
            }
        }
        return MainFragment.ServiceStatus(name = hostDisplayName(host), ok = rttMs != null, rttMs = rttMs ?: 0)
    }

    /**
     * Real round-trip to `host:443` through whichever tunnel is up, measured at
     * the TCP level (SOCKS5 CONNECT), so the result answers exactly "can the
     * tunnel reach this site, and how fast" without HTTP semantics:
     *  - Xray mode: through the core's loopback SOCKS inbound (see
     *    [XrayConfigBuilder]; the app's UID is excluded from the VPN, so plain
     *    sockets here would bypass the tunnel). If that inbound isn't
     *    reachable at all, falls back to the core's own through-tunnel probe.
     *  - Instance/call mode: through the local relay's SOCKS5, which forwards
     *    the connection over the WebRTC data channel.
     */
    private fun measureServiceRtt(host: String): Int? {
        val viaSocks = try {
            probeViaSocks5(host = host, port = 443)
        } catch (_: Exception) {
            null
        }
        if (viaSocks != null) return viaSocks

        val xray = XrayVpnService.instance
        if (xray?.isRunning == true && !loopbackSocksReachable()) {
            // Loopback inbound never accepted the connection (e.g. a session
            // started before it existed) — let the core probe through itself.
            return xray.measureThroughTunnel("https://$host")?.toInt()
        }
        return null
    }

    private fun hostDisplayName(host: String): String =
        MainFragment.SERVICE_TARGETS.firstOrNull { it.second == host }?.first ?: host

    /** Whether the core's loopback SOCKS inbound accepts TCP connections. */
    private fun loopbackSocksReachable(): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(Net.LOCALHOST, Prefs.activeLoopbackSocksPort.toInt()), 1000)
            true
        }
    } catch (_: Exception) {
        false
    }

    override fun isTunnelActive(): Boolean = connected

    override fun currentStatus(): VpnStatus? = lastStatus

    // ---- Cors.Connect instance flow -------------------------------------

    fun startCorsConnect() {
        if (resetInProgress) {
            appendLog("Waiting for previous session to stop before Cors.Connect connect")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
            return
        }
        corsController?.stop()
        corsController = CorsInstanceController(applicationContext, corsClient, corsHost).also { it.start() }
        appendLog("Cors.Connect: requesting instance")
    }

    /**
     * Implicit sign-in succeeded (an added xray subscription turned out to be
     * a Remnawave subscription link — see [cc.cors.connect.cors.LinkAuth]).
     * There is no manual sign-in UI anymore: adding a subscription IS the
     * sign-in.
     */
    override fun onCorsSignedIn(username: String) {
        appendLog("Cors.Connect: signed in as $username")
        // Sign-in fills Prefs.corsUsername, which hides the always-on
        // "add a subscription" CTA on the connected Main screen.
        mainFragment()?.onXrayServersChanged()
        settingsFragment()?.refresh()
        // If a connect flow is mid-claim (anonymous temp instance created and
        // waiting for credentials), resume it with the fresh session.
        corsController?.resumeClaim()
    }

    fun forgetCorsInstance() {
        corsController?.stop()
        corsController = null
        Prefs.forgetCorsInstance()
        settingsFragment()?.refresh()
        Toast.makeText(this, R.string.cors_toast_instance_forgotten, Toast.LENGTH_SHORT).show()
    }

    private val corsHost = object : CorsInstanceController.Host {
        override fun onCorsStatus(text: String) {
            mainFragment()?.onStatusTextChanged(text)
        }

        override fun onCorsOutputReady(config: CallConfig) {
            // Whitelist Bypass is a single fixed list entry that "handles the
            // entire connection flow": selecting it and pressing the hero
            // button already committed the user to connecting, so once the
            // auto-provisioned instance is ready, join it immediately — no
            // second confirmation/press needed. startJoinFor() sets
            // Prefs.connectionMode = INSTANCE itself, so the mode display
            // can't get stuck on "Xray" even if this races with something
            // else touching Prefs.connectionMode.
            corsPendingOutput = config
            runOnUiThread {
                if (!isFinishing && !isDestroyed) startJoinFor(config)
            }
        }

        override fun onCorsNeedsTelegram() {
            // No credential available and none could be derived implicitly.
            // Surface the state — the fix is adding the subscription link,
            // the same one used for the xray servers.
            runOnUiThread {
                mainFragment()?.onStatusTextChanged(getString(R.string.cors_status_auth_required))
            }
        }

        override fun onCorsClaimed(username: String) {
            appendLog("Cors.Connect: authorized as $username")
            runOnUiThread {
                mainFragment()?.onStatusTextChanged(getString(R.string.cors_status_claimed, username))
                settingsFragment()?.refresh()
            }
        }

        override fun onCorsFailed(message: String) {
            appendLog("Cors.Connect: $message")
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestinationSelected(config: CallConfig) {
        Prefs.activeDestinationId = config.id
        mainFragment()?.onDestinationsChanged()
    }

    override fun onDestinationsChanged() {
        mainFragment()?.onDestinationsChanged()
    }

    override fun onXrayServersChanged() {
        // Broadcast to every live listener, not just the Main tab: the Servers
        // tab (a sibling pager page) and any pushed sub-page also need to
        // rebuild their lists right after a subscription import — otherwise
        // the new servers only showed up after an app restart.
        notifyXrayServersListeners()
    }

    private fun notifyXrayServersListeners() {
        val seen = HashSet<XrayServersListener>()
        fun visit(fm: androidx.fragment.app.FragmentManager) {
            for (f in fm.fragments) {
                (f as? XrayServersListener)?.let { if (seen.add(it)) it.onXrayServersChanged() }
                visit(f.childFragmentManager)
            }
        }
        visit(supportFragmentManager)
    }

    override fun onTunnelModeChanged(mode: TunnelMode) {
        fullReset()
    }

    override fun onForgetAllDestinations() {
        Prefs.savedDestinations = emptyList()
        Prefs.activeDestinationId = ""
        mainFragment()?.onDestinationsChanged()
        Toast.makeText(this, R.string.settings_toast_destinations_cleared, Toast.LENGTH_SHORT)
            .show()
    }

    override fun onForgetAllXrayServers() {
        Prefs.forgetAllXrayServers()
        mainFragment()?.onXrayServersChanged()
        Toast.makeText(this, R.string.settings_toast_xray_servers_cleared, Toast.LENGTH_SHORT)
            .show()
    }

    override fun onResetAllSettings() {
        Prefs.resetAllSettings()
        App.applyTheme(Prefs.themeMode)
        settingsFragment()?.refresh()
        Toast.makeText(this, R.string.settings_toast_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun onCorsForgetInstance() = forgetCorsInstance()

    override fun onCorsBaseUrlChanged() {
        // corsClient captured the base URL at construction; rebuild it so the
        // next Cors.Connect flow uses the newly configured server. An in-flight
        // controller keeps its old client and finishes against the prior server.
        corsClient = CorsClient()
        appendLog("Cors.Connect: server URL updated")
    }

    override fun activityLogLines(): List<String> {
        val text = logWriter.displayText()
        if (text.isEmpty()) return emptyList()
        return text.split('\n').filter { it.isNotBlank() }
    }

    override fun activityLogRevision(): Long = logWriter.revision()

    override fun copyLogs() {
        val contents =
            if (logWriter.file.exists()) logWriter.file.readText() else logWriter.displayText()
        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("relay.log", contents))
        Toast.makeText(this, R.string.copy_logs_toast, Toast.LENGTH_SHORT).show()
    }

    override fun shareLogs() {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            logWriter.file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_logs)))
    }

    override fun appendLog(message: String) {
        logWriter.append(message)
        if (overlayVisible) {
            runOnUiThread {
                overlayLogsText.text = logWriter.displayText()
                overlayLogsScroll.post { overlayLogsScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onJoinStatusText(text: String) {
        if (resetInProgress) return
        runOnUiThread { mainFragment()?.onStatusTextChanged(text) }
    }

    override fun onJoinStatus(status: VpnStatus) {
        if (resetInProgress && status != VpnStatus.CALL_FAILED) return
        TunnelVpnService.instance?.updateStatus(status)
        ProxyService.instance?.updateStatus(status)
        lastStatus = status
        runOnUiThread {
            if (status == VpnStatus.CALL_FAILED) {
                fullReset()
                lastStatus = VpnStatus.CALL_FAILED
                mainFragment()?.onStatusChanged(VpnStatus.CALL_FAILED)
                return@runOnUiThread
            }
            mainFragment()?.onStatusChanged(status)
            if (status == VpnStatus.TUNNEL_ACTIVE) {
                connected = true
                mainFragment()?.onConnectedChanged(true)
            }
        }
    }

    override fun pushSubPage(fragment: Fragment) {
        subPageContainer.visibility = View.VISIBLE
        tabContainer.isUserInputEnabled = false
        supportFragmentManager.beginTransaction()
            .replace(R.id.subPageContainer, fragment, SUB_PAGE_TAG)
            .addToBackStack(SUB_PAGE_TAG)
            .commit()
    }

    override fun openServersTab() {
        selectNavTab(R.id.navServers)
    }

    private fun showOnboarding() {
        joinOverlayContainer.visibility = View.VISIBLE
        bottomNav.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.joinOverlayContainer, OnboardingFragment())
            .commit()
    }

    override fun onOnboardingFinished() {
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        if (fragment is OnboardingFragment) {
            supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
        }
        joinOverlayContainer.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        // First run just finished onboarding, so the onCreate nags were skipped —
        // fire the battery-optimization reminder now.
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            maybeRemindBatteryOptimization()
        }
    }

    override fun popSubPage() {
        // Sub-pages are stacked (Advanced → Xray servers → …), so back must
        // pop exactly one level; popping the whole SUB_PAGE_TAG stack used to
        // dump the user on the main screen instead of the parent page.
        supportFragmentManager.popBackStackImmediate()
        syncSubPageVisibility()
    }

    /** Hides the sub-page layer once its back stack is empty (also fires on system Back). */
    private fun syncSubPageVisibility() {
        if (supportFragmentManager.backStackEntryCount == 0) {
            subPageContainer.visibility = View.GONE
            tabContainer.isUserInputEnabled = true
        }
    }

    override fun onJoinCancel() {
        pendingConnectTarget = null
        runOnUiThread { fullReset() }
    }

    override fun setJoinUiVisible(visible: Boolean) {
        runOnUiThread { setJoinOverlayVisible(visible) }
    }

    override fun requestVpn() {
        if (Prefs.proxyOnly) {
            appendLog("Proxy only mode, skipping VPN")
            startService(Intent(this, ProxyService::class.java))
            onJoinStatus(VpnStatus.TUNNEL_ACTIVE)
            return
        }
        if (TunnelServiceState.hasForeignVpn(this)) {
            appendLog("Another VPN is active, requesting system VPN switch")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_requesting_replacement))
        }
        pendingVpnStart = ::startVpnService
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startVpnService()
    }

    // ---- Standard Xray connection flow -----------------------------------

    private fun startXrayConnect(server: XrayServer) {
        if (resetInProgress) {
            pendingConnectTarget = ConnectTarget.Xray(server)
            appendLog("Queued connect after previous session stops")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
            return
        }
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.xraySocksPort)) {
            pendingConnectTarget = ConnectTarget.Xray(server)
            appendLog("Waiting for previous local tunnel to stop")
            fullReset()
            return
        }
        if (connected) {
            fullReset()
        }

        // See the matching comment in startJoinFor: keeps the Main screen's
        // "Mode" stat correct for every Xray-connect entry point, not just
        // list selection.
        Prefs.connectionMode = ConnectionMode.XRAY

        logWriter.reset()
        runOnUiThread { logsFragment()?.refresh() }
        appendLog("Connecting via Xray: ${server.summary}")
        lastStatus = VpnStatus.CONNECTING
        mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
        mainFragment()?.onConnectedChanged(false)
        setJoinOverlayVisible(false)

        if (TunnelServiceState.hasForeignVpn(this)) {
            appendLog("Another VPN is active, requesting system VPN switch")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_requesting_replacement))
        }
        pendingVpnStart = ::startXrayVpnService
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startXrayVpnService()
    }

    private fun startXrayVpnService() {
        startService(Intent(this, XrayVpnService::class.java))
        appendLog("Xray VPN start requested")
    }

    /** Surfaces the previous run's crash (if any, see [App.lastCrashText]) in the Logs tab so it's visible without adb. */
    private fun reportLastCrashIfAny() {
        val text = App.lastCrashText(this) ?: return
        File(filesDir, "last_crash.txt").delete()
        appendLog("=== Previous run crashed ===")
        text.lineSequence().forEach { appendLog(it) }
        appendLog("=== End of crash report ===")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Automatic update check: at most once a day, silently. When a newer
     * GitHub release exists the update sheet opens so the user can download.
     */
    private fun maybeCheckForUpdates() {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        Prefs.lastUpdateCheck = now
        AppUpdater.checkLatest { release ->
            if (release != null && AppUpdater.isNewer(release.version)) {
                val settings = settingsFragment() ?: return@checkLatest
                if (settings.isAdded) UpdateActionSheet.show(supportFragmentManager)
            }
        }
    }

    /**
     * One-time battery-optimization reminder: a background VPN gets frozen by
     * Doze unless exempted, so nudge the user once (they can also toggle it
     * later from Settings → App).
     */
    private fun maybeRemindBatteryOptimization() {
        if (!BatteryOptimizer.shouldShowReminder(this)) return
        BatteryOptimizer.promptedThisProcess = true
        Prefs.batteryReminderShown = true
        ConfirmActionSheet.show(
            manager = supportFragmentManager,
            title = getString(R.string.battery_reminder_title),
            subtitle = getString(R.string.battery_reminder_sub),
            confirmLabel = getString(R.string.battery_reminder_allow),
            cancelLabel = getString(R.string.battery_reminder_later),
        ) { BatteryOptimizer.requestIgnore(this) }
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        // Subscription import deep link: corsconnect://add/<url-encoded-subscription-link>
        if (intent != null && intent.action == Intent.ACTION_VIEW && data != null &&
            data.scheme.equals("corsconnect", ignoreCase = true) && data.host.equals("add", ignoreCase = true)
        ) {
            intent.action = null
            val encoded = data.schemeSpecificPart.removePrefix("//add/").removePrefix("//add")
            val subscriptionLink = Uri.decode(encoded).trim()
            if (subscriptionLink.isEmpty()) {
                Toast.makeText(this, R.string.xray_sheet_error_unrecognized, Toast.LENGTH_SHORT).show()
            } else {
                AddXraySubscriptionSheet.show(supportFragmentManager, subscriptionLink)
            }
            return
        }
        // Telegram initData App Link callback: https://<host>/tginit?initdata=...
        if (intent != null && intent.action == Intent.ACTION_VIEW && TelegramAuth.isCallback(data) && data != null) {
            intent.action = null
            val initData = TelegramAuth.extractInitData(data)
            if (initData == null) {
                appendLog("Cors.Connect: Telegram callback carried no initData")
                Toast.makeText(this, R.string.cors_status_auth_required, Toast.LENGTH_LONG).show()
            } else if (!TelegramAuth.looksLikeInitData(initData)) {
                appendLog("Cors.Connect: Telegram initData rejected (malformed)")
                Toast.makeText(this, R.string.cors_status_auth_required, Toast.LENGTH_LONG).show()
            } else {
                TelegramAuth.storeInitData(initData)
                appendLog("Cors.Connect: received Telegram initData")
                mainFragment()?.onStatusTextChanged(getString(R.string.cors_status_claiming))
                settingsFragment()?.refresh()
                // If a flow is in progress, resume its claim; otherwise the stored
                // initData will be used on the next connect.
                if (corsController != null) corsController?.resumeClaim()
            }
            return
        }
        if (intent?.action != ACTION_AUTO_START) return
        intent.action = null
        val isConnecting = lastStatus == VpnStatus.CONNECTING
        if (!connected && !isConnecting && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            Prefs.activeDestination?.let { onConnectPressed(it) } ?: run {
                Toast.makeText(this, R.string.error_no_destination, Toast.LENGTH_SHORT).show()
            }
        } else if (connected) {
            onDisconnectPressed()
        }
    }

    private fun selectNavTab(itemId: Int, animatePager: Boolean = true) {
        if (currentTabId == itemId) return
        currentTabId = itemId
        dismissSubPage()
        val index = navIndexFor(itemId)
        updateNavSelection(itemId)
        val animateIndicator = tabContainer.currentItem != index
        if (!animatePager || tabContainer.currentItem == index) {
            moveNavIndicatorTo(itemId, animate = animateIndicator)
        }
        tabContainer.setCurrentItem(index, animatePager)
    }

    private fun navIndexFor(itemId: Int): Int = when (itemId) {
        R.id.navMain -> TAB_MAIN
        R.id.navServers -> TAB_SERVERS
        R.id.navLogs -> TAB_LOGS
        R.id.navSettings -> TAB_SETTINGS
        else -> TAB_MAIN
    }

    private fun navItems(): List<LinearLayout> = listOf(navMain, navServers, navLogs, navSettings)

    private fun updateNavSelection(itemId: Int) {
        applyNavSelectionState(0f, navIndexFor(itemId))
    }

    private fun moveNavIndicatorTo(itemId: Int, animate: Boolean) {
        val target = when (itemId) {
            R.id.navMain -> navMain
            R.id.navServers -> navServers
            R.id.navLogs -> navLogs
            R.id.navSettings -> navSettings
            else -> null
        } ?: return

        navIndicator.layoutParams = navIndicator.layoutParams.apply {
            width = target.width
            height = target.height
        }
        navIndicator.requestLayout()
        val targetTranslation = target.left.toFloat()
        if (animate) {
            navIndicator.animate()
                .translationX(targetTranslation)
                .setDuration(220L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            navIndicator.animate().cancel()
            navIndicator.translationX = targetTranslation
        }
    }

    private fun moveNavIndicatorForPager(position: Int, positionOffset: Float) {
        val targets = navItems()
        val current = targets.getOrNull(position) ?: return
        val next = targets.getOrNull(position + 1)
        val targetLeft = if (next != null) {
            current.left + ((next.left - current.left) * positionOffset)
        } else {
            current.left.toFloat()
        }
        val lp = navIndicator.layoutParams
        if (lp.width != current.width || lp.height != current.height) {
            navIndicator.layoutParams = lp.apply {
                width = current.width
                height = current.height
            }
            navIndicator.requestLayout()
        }
        navIndicator.animate().cancel()
        navIndicator.translationX = targetLeft
    }

    private fun interpolateNavSelection(position: Int, positionOffset: Float) {
        if (navScrollState == ViewPager2.SCROLL_STATE_IDLE) return
        val clampedOffset = positionOffset.coerceIn(0f, 1f)
        applyNavSelectionState(clampedOffset, position)
    }

    private fun applyNavSelectionState(positionOffset: Float, position: Int) {
        val emphasis = floatArrayOf(0f, 0f, 0f, 0f)
        val baseIndex = position.coerceIn(0, emphasis.lastIndex)
        emphasis[baseIndex] = 1f - positionOffset
        val nextIndex = (baseIndex + 1).coerceAtMost(emphasis.lastIndex)
        if (nextIndex != baseIndex) {
            emphasis[nextIndex] = positionOffset
        }

        applyNavVisual(navMainIcon, navMainLabel, emphasis[0])
        applyNavVisual(navServersIcon, navServersLabel, emphasis[1])
        applyNavVisual(navLogsIcon, navLogsLabel, emphasis[2])
        applyNavVisual(navSettingsIcon, navSettingsLabel, emphasis[3])
    }

    private fun applyNavVisual(icon: ImageView, label: TextView, emphasis: Float) {
        val accent = getColor(R.color.accent_emerald)
        val ink = getColor(R.color.ink_3)
        val blended = navColorEvaluator.evaluate(emphasis, ink, accent) as Int
        icon.setColorFilter(blended)
        icon.alpha = 0.72f + (0.28f * emphasis)
        label.setTextColor(blended)
        label.alpha = 0.74f + (0.26f * emphasis)
        label.scaleX = 1f + (0.06f * emphasis)
        label.scaleY = 1f + (0.06f * emphasis)
        label.paint.isFakeBoldText = emphasis > 0.92f
    }

    private fun mainFragment(): MainFragment? =
        supportFragmentManager.fragments.firstOrNull { it is MainFragment } as? MainFragment

    private fun settingsFragment(): SettingsScreenFragment? =
        supportFragmentManager.fragments.firstOrNull { it is SettingsScreenFragment } as? SettingsScreenFragment

    private fun logsFragment(): LogsFragment? =
        supportFragmentManager.fragments.firstOrNull { it is LogsFragment } as? LogsFragment

    /**
     * Opens a SOCKS5 CONNECT to `host:port` through the active loopback proxy
     * and returns the round-trip time (ms) it took the proxy to report the
     * remote connection established — a real ping over the tunnel. Returns
     * null on any handshake failure, refusal or premature EOF.
     */
    private fun probeViaSocks5(host: String, port: Int): Int? {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(Net.LOCALHOST, Prefs.activeLoopbackSocksPort.toInt()), 5000)
            socket.soTimeout = 15000
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            fun readOrFail(n: Int): ByteArray? {
                val buf = ByteArray(n)
                var read = 0
                while (read < n) {
                    val r = input.read(buf, read, n - read)
                    if (r < 0) return null
                    read += r
                }
                return buf
            }

            // Greeting: version 5, one method — username/password (0x02).
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            val greeting = readOrFail(2) ?: return null
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x02) return null

            val userBytes = SocksAuth.user.toByteArray(Charsets.US_ASCII)
            val passBytes = SocksAuth.pass.toByteArray(Charsets.US_ASCII)
            val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
            authPacket[0] = 0x01
            authPacket[1] = userBytes.size.toByte()
            System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
            authPacket[2 + userBytes.size] = passBytes.size.toByte()
            System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
            output.write(authPacket)
            output.flush()
            val authReply = readOrFail(2) ?: return null
            if (authReply[1].toInt() != 0x00) return null

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(4 + 1 + hostBytes.size + 2)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03
            request[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
            request[5 + hostBytes.size] = ((port shr 8) and 0xff).toByte()
            request[6 + hostBytes.size] = (port and 0xff).toByte()
            output.write(request)
            output.flush()

            // Reply header: VER REP RSV ATYP, then a bound address we don't need.
            val reply = readOrFail(4) ?: return null
            if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) return null
            val addrLen = when (reply[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> (readOrFail(1) ?: return null)[0].toInt() and 0xff
                else -> return null
            }
            readOrFail(addrLen + 2) ?: return null

            return ((System.nanoTime() - started) / 1_000_000).toInt()
        }
    }

    private fun dismissSubPage() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                SUB_PAGE_TAG,
                FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }
        subPageContainer.visibility = View.GONE
        tabContainer.isUserInputEnabled = true
    }

    private fun setJoinOverlayVisible(visible: Boolean) {
        joinOverlayContainer.visibility = if (visible) View.VISIBLE else View.GONE
        overlayLogs.visibility = if (visible) View.VISIBLE else View.GONE
        bottomNav.visibility = if (visible) View.GONE else View.VISIBLE
        overlayVisible = visible
        if (visible) {
            overlayLogsText.text = logWriter.displayText()
            overlayLogsScroll.post { overlayLogsScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun startJoinFor(config: CallConfig) {
        if (resetInProgress) {
            pendingConnectTarget = ConnectTarget.Instance(config)
            appendLog("Queued connect after previous session stops")
            mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
            return
        }
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
            pendingConnectTarget = ConnectTarget.Instance(config)
            appendLog("Waiting for previous local tunnel to stop")
            fullReset()
            return
        }
        val url = config.url.trim()
        if (url.isEmpty()) return

        // Belt-and-suspenders: every instance connection funnels through here
        // (list selection + hero press, the Cors.Connect auto-provision flow,
        // queued reconnects after a reset, the auto-start intent), so fixing
        // the mode here guarantees the Main screen's "Mode" stat can never be
        // left showing "Xray" from a previously selected Xray server — even
        // if some future caller forgets to set it before calling this.
        Prefs.connectionMode = ConnectionMode.INSTANCE

        val platform = config.platform
        if (Prefs.activeTunnelMode == TunnelMode.DC &&
            (platform == CallPlatform.TELEMOST || platform == CallPlatform.DION)
        ) {
            Toast.makeText(this, R.string.dc_mode_not_supported, Toast.LENGTH_SHORT).show()
        }

        if (connected) {
            fullReset()
        }

        activeJoinUrl = url
        logWriter.reset()
        runOnUiThread { logsFragment()?.refresh() }
        appendLog("Loading: ${maskUrl(url)}")
        lastStatus = VpnStatus.CONNECTING
        mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
        mainFragment()?.onConnectedChanged(false)

        val headlessMode =
            Prefs.headless || platform == CallPlatform.WBSTREAM || platform == CallPlatform.DION

        if (headlessMode && platform != CallPlatform.VK) {
            setJoinOverlayVisible(false)
            activeHeadlessController = HeadlessJoinController(
                applicationInfo.nativeLibraryDir,
                this,
                platform,
                url,
            ).also { it.start() }
            return
        }

        val joinFragment = if (headlessMode) {
            HeadlessVkFragment.newInstance(url)
        } else {
            JsHookJoinFragment.newInstance(url)
        }

        setJoinOverlayVisible(!headlessMode)

        supportFragmentManager.beginTransaction()
            .replace(R.id.joinOverlayContainer, joinFragment)
            .commit()
    }

    private fun startVpnService() {
        startService(Intent(this, TunnelVpnService::class.java))
        appendLog("VPN start requested")
        onJoinStatus(VpnStatus.STARTING)
    }

    private fun onDisconnectFromService() {
        if (resetInProgress) {
            maybeFinishReset()
            return
        }
        connected = false
        lastStatus = null
        closeActiveHeadlessController()
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.CALL_DISCONNECTED)
    }

    private fun fullReset() {
        if (resetInProgress) return
        resetInProgress = true
        val resetId = ++resetGeneration
        connected = false
        lastStatus = VpnStatus.STOPPING
        val controller = activeHeadlessController
        activeHeadlessController = null
        activeJoinUrl = ""

        // Tell the server to stop the spawned Cors.Connect instance too.
        corsController?.stop()
        corsController = null
        corsPendingOutput = null

        removeJoinFragment()
        TunnelVpnService.requestStop(this)
        ProxyService.requestStop(this)
        XrayVpnService.requestStop(this)
        HeadlessSessionService.requestStop(this)
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
        mainFragment()?.onStatusTextChanged(getString(R.string.status_stopping_previous))
        thread(name = "full-reset-shutdown") {
            controller?.close()
            var attempts = 0
            // 90 x 100ms = 9s: covers the 8s hard stop cap in Tunnel/Xray
            // services, so a hung native engine shutdown resolves via its
            // staleness timeout *within* this poll instead of failing into
            // the "still shutting down, try again" dead end.
            while (
                attempts < 90 &&
                (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) ||
                    !PortGuard.isPortAvailable(Prefs.socksPort) ||
                    !PortGuard.isPortAvailable(Prefs.xraySocksPort))
            ) {
                if (!isResetCurrent(resetId)) return@thread
                Thread.sleep(100)
                attempts++
            }
            if (!isResetCurrent(resetId)) return@thread
            if (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) || !PortGuard.isPortAvailable(Prefs.socksPort) || !PortGuard.isPortAvailable(Prefs.xraySocksPort)) {
                // Force phase: repeat the stop requests and kill whatever
                // process still holds the local SOCKS ports (the relay
                // subprocess is the usual suspect — a graceful stop can hang
                // on a stuck WebRTC data channel). Several rounds, because a
                // kill needs a moment to actually release the socket.
                runOnUiThread {
                    if (isResetCurrent(resetId)) {
                        mainFragment()?.onStatusTextChanged(getString(R.string.status_force_stopping))
                        appendLog("Force-stopping previous session…")
                    }
                }
                var stuckAfterForce = true
                for (round in 1..3) {
                    TunnelVpnService.requestStop(this@MainActivity)
                    ProxyService.requestStop(this@MainActivity)
                    XrayVpnService.requestStop(this@MainActivity)
                    HeadlessSessionService.requestStop(this@MainActivity)
                    PortGuard.ensurePortFree(Prefs.socksPort)
                    PortGuard.ensurePortFree(Prefs.xraySocksPort)
                    Thread.sleep(300)
                    if (!isResetCurrent(resetId)) return@thread
                    // Ports free = the local tunnel (relay/bridge) is really
                    // dead; remaining "running" service flags clear on their
                    // own stop intents and don't block a fresh connect.
                    if (PortGuard.isPortAvailable(Prefs.socksPort) &&
                        PortGuard.isPortAvailable(Prefs.xraySocksPort)
                    ) {
                        stuckAfterForce = false
                        break
                    }
                }
                if (stuckAfterForce) {
                    runOnUiThread {
                        if (isResetCurrent(resetId)) {
                            forceUnlockOrRestart(getString(R.string.status_still_shutting_down))
                        }
                    }
                    return@thread
                }
            }
            if (!isResetCurrent(resetId)) return@thread
            if (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) || !PortGuard.isPortAvailable(Prefs.socksPort) || !PortGuard.isPortAvailable(Prefs.xraySocksPort)) {
                runOnUiThread {
                    if (isResetCurrent(resetId)) {
                        forceUnlockReset(getString(R.string.status_still_shutting_down))
                    }
                }
                return@thread
            }
            Thread.sleep(400)
            runOnUiThread {
                if (isResetCurrent(resetId)) {
                    maybeFinishReset(resetId)
                }
            }
        }
    }

    private fun maybeFinishReset(expectedResetId: Long? = null) {
        if (!resetInProgress) return
        if (expectedResetId != null && expectedResetId != resetGeneration) return
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort) || !PortGuard.isPortAvailable(Prefs.xraySocksPort)) return
        resetInProgress = false
        connected = false
        lastStatus = null
        activeJoinUrl = ""
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.CALL_DISCONNECTED)
        val pendingTarget = pendingConnectTarget
        pendingConnectTarget = null
        if (pendingTarget != null) {
            appendLog("Previous session stopped, starting new connection")
            when (pendingTarget) {
                is ConnectTarget.WhitelistBypass -> startCorsConnect()
                is ConnectTarget.Instance -> startJoinFor(pendingTarget.config)
                is ConnectTarget.Xray -> startXrayConnect(pendingTarget.server)
            }
        }
    }

    private fun forceUnlockReset(message: String) {
        resetInProgress = false
        pendingConnectTarget = null
        connected = false
        activeJoinUrl = ""
        lastStatus = if (PortGuard.isPortAvailable(Prefs.socksPort) && PortGuard.isPortAvailable(Prefs.xraySocksPort)) VpnStatus.CALL_DISCONNECTED else VpnStatus.PORT_BUSY
        closeActiveHeadlessController()
        removeJoinFragment()
        setJoinOverlayVisible(false)
        TunnelVpnService.requestStop(this)
        ProxyService.requestStop(this)
        XrayVpnService.requestStop(this)
        HeadlessSessionService.requestStop(this)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(lastStatus ?: VpnStatus.CALL_DISCONNECTED)
        mainFragment()?.onStatusTextChanged(message)
        appendLog(message)
        // Unlocking must not abandon the cleanup: the stuck relay may release
        // the port a moment later, and without a sweeper it would stay busy
        // until the next app restart (the exact bug this fixes).
        thread(name = "force-unlock-sweep") {
            TunnelVpnService.requestStop(this)
            ProxyService.requestStop(this)
            XrayVpnService.requestStop(this)
            HeadlessSessionService.requestStop(this)
            PortGuard.ensurePortFree(Prefs.socksPort)
            PortGuard.ensurePortFree(Prefs.xraySocksPort)
        }
    }

    /**
     * End state of a stuck [fullReset]: if the local SOCKS ports are still held
     * — the in-process WebRTC joiner (DC mode) can wedge on a stuck data
     * channel, and [PortGuard] cannot kill it because it shares our PID — a
     * plain unlock leaves every reconnect dead-ending on "previous session
     * still stopping" until the user kills the app by hand. Restart the process
     * ourselves in that case; otherwise just unlock.
     */
    private fun forceUnlockOrRestart(message: String) {
        val portsStuck = !PortGuard.isPortAvailable(Prefs.socksPort) ||
            !PortGuard.isPortAvailable(Prefs.xraySocksPort)
        if (portsStuck && !restartScheduled) {
            hardRestartProcess()
        } else {
            forceUnlockReset(message)
        }
    }

    private fun hardRestartProcess() {
        restartScheduled = true
        appendLog("Local tunnel port stuck after force-stop — restarting the app to release it")
        mainFragment()?.onStatusTextChanged(getString(R.string.status_still_shutting_down))
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (intent != null) {
                val pending = PendingIntent.getActivity(
                    this, RESTART_REQUEST_CODE, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val alarm = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                alarm.set(
                    android.app.AlarmManager.RTC,
                    System.currentTimeMillis() + 400L,
                    pending,
                )
            }
        } catch (_: Exception) {
            // Fall through to the kill regardless — a cold relaunch by the user
            // still frees the port, which is the whole point.
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(0)
    }

    private fun closeActiveHeadlessController() {
        val controller = activeHeadlessController
        activeHeadlessController = null
        if (controller != null) {
            thread(name = "headless-shutdown") { controller.close() }
        }
    }

    private fun isResetCurrent(resetId: Long): Boolean =
        resetInProgress && resetGeneration == resetId

    private fun shutdownJoinFragment() {
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        (fragment as? JoinSessionShutdown)?.shutdownSession()
    }

    private fun removeJoinFragment() {
        shutdownJoinFragment()
        if (isDestroyed || supportFragmentManager.isStateSaved) return
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        if (fragment != null) {
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitAllowingStateLoss()
        }
    }

    /** Feeds touch-down points to the animated backdrop's ripple effect. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            findViewById<bypass.whitelist.ui.GradientBackdropView>(R.id.gradientBackdrop)
                ?.rippleAt(ev.x, ev.y)
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val ACTION_AUTO_START = "bypass.whitelist.AUTO_START"
        private const val SUB_PAGE_TAG = "sub_page"
        private const val RESTART_REQUEST_CODE = 0x5245
        private const val STATE_CURRENT_TAB_ID = "current_tab_id"
        private const val CALL_LINK = ""
        private const val TAB_MAIN = 0
        private const val TAB_SERVERS = 1
        private const val TAB_LOGS = 2
        private const val TAB_SETTINGS = 3
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
