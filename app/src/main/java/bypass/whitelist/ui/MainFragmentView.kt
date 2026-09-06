package bypass.whitelist.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import bypass.whitelist.R
import bypass.whitelist.tunnel.ConnectTarget
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.util.Callback
import bypass.whitelist.util.ParamCallback
import bypass.whitelist.util.Prefs
import bypass.whitelist.xray.XrayServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainFragmentView(private val root: View) {

    companion object {
        private const val PING_TIMEOUT_MS = 3_000
    }

    private val headerSub: TextView = root.findViewById(R.id.headerSub)
    private val addButton: View = root.findViewById(R.id.headerAddButton)
    private val scanButton: View = root.findViewById(R.id.headerScanButton)
    private val refreshButton: View = root.findViewById(R.id.refreshSubsButton)
    private val refreshIcon: View = root.findViewById(R.id.refreshSubsIcon)
    private val pingCheckButton: View = root.findViewById(R.id.pingAllButton)
    private val pingCheckIcon: View = root.findViewById(R.id.pingAllIcon)
    private val connectBar: View = root.findViewById(R.id.connectBar)
    private val connectBarLabel: TextView = root.findViewById(R.id.connectBarLabel)
    private val connectBarIcon: ImageView = root.findViewById(R.id.connectBarIcon)
    private val routeStatusLine: TextView = root.findViewById(R.id.routeStatusLine)
    private val routeCity: TextView = root.findViewById(R.id.routeCity)
    private val routeExchangeRow: View = root.findViewById(R.id.routeExchangeRow)
    private val routeDeviceValue: TextView = root.findViewById(R.id.routeDeviceValue)
    private val routeExitValue: TextView = root.findViewById(R.id.routeExitValue)
    private val routeStatsRow: View = root.findViewById(R.id.routeStatsRow)
    private val routeUptime: TextView = root.findViewById(R.id.routeUptime)
    private val routePing: TextView = root.findViewById(R.id.routePing)
    private val routeLeft: TextView = root.findViewById(R.id.routeLeft)
    private val routeLeftCell: View = root.findViewById(R.id.routeLeftCell)
    private val routeRx: TextView = root.findViewById(R.id.routeRx)
    private val routeTx: TextView = root.findViewById(R.id.routeTx)
    private val routeUsed: TextView = root.findViewById(R.id.routeUsed)
    private val routeUsedCell: View = root.findViewById(R.id.routeUsedCell)
    private val routeSpark: SparklineView = root.findViewById(R.id.routeSpark)
    private val statusDetail: TextView = root.findViewById(R.id.statusDetail)
    private val authHint: TextView = root.findViewById(R.id.authHint)
    private val authHintBotLink: TextView = root.findViewById(R.id.authHintBotLink)
    private val callsList: LinearLayout = root.findViewById(R.id.callsList)
    private val quickChipTunnel: View = root.findViewById(R.id.quickChipTunnel)
    private val quickChipSplit: View = root.findViewById(R.id.quickChipSplit)
    private val quickChipDns: View = root.findViewById(R.id.quickChipDns)
    private val quickChipBot: View = root.findViewById(R.id.quickChipBot)
    private val emptyCta: View = root.findViewById(R.id.emptyCta)
    private val serverActionsRow: View = root.findViewById(R.id.serverActionsRow)
    private val pingRow: LinearLayout = root.findViewById(R.id.pingRow)
    private val pingButton: View = root.findViewById(R.id.pingButton)
    private val pingButtonLabel: TextView = root.findViewById(R.id.pingButtonLabel)
    private val pingButtonDot: View = root.findViewById(R.id.pingButtonDot)
    private val routeAllServers: View = root.findViewById(R.id.routeAllServers)

    var onAddCallClicked: Callback? = null
    var onScanQrClicked: Callback? = null
    var onRefreshSubscriptionsClicked: Callback? = null
    var onPingCheckClicked: Callback? = null
    var onHeroPressed: Callback? = null
    var onServiceCheckPressed: Callback? = null
    var onEntrySelected: ParamCallback<ConnectTarget>? = null
    var onEntryLongPressed: ParamCallback<ConnectTarget>? = null
    var onQuickTunnelModeClicked: Callback? = null
    var onQuickSplitClicked: Callback? = null
    var onQuickDnsClicked: Callback? = null
    var onQuickBotClicked: Callback? = null
    var onAuthHintBotClicked: Callback? = null
    var onAllServersClicked: Callback? = null

    private var collapsedToActive: Boolean = false
    private var currentServers: List<XrayServer> = emptyList()
    private var activeServerId: String = ""

    /** TCP ping cache: server id → RTT ms (-1 = timeout), see [bindPing]. */
    private val pingCache = mutableMapOf<String, Int>()
    private val pingInFlight = mutableSetOf<String>()
    private var pingCheckSpinning: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        emptyCta.clipToOutline = true
        pingButton.clipToOutline = true
        addButton.setOnClickListener { onAddCallClicked?.invoke() }
        scanButton.setOnClickListener { onScanQrClicked?.invoke() }
        refreshButton.setOnClickListener { onRefreshSubscriptionsClicked?.invoke() }
        pingCheckButton.setOnClickListener { onPingCheckClicked?.invoke() }
        emptyCta.setOnClickListener { onAddCallClicked?.invoke() }
        connectBar.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onHeroPressed?.invoke()
        }
        pingButton.setOnClickListener { onServiceCheckPressed?.invoke() }
        quickChipTunnel.setOnClickListener { onQuickTunnelModeClicked?.invoke() }
        quickChipSplit.setOnClickListener { onQuickSplitClicked?.invoke() }
        quickChipDns.setOnClickListener { onQuickDnsClicked?.invoke() }
        quickChipBot.setOnClickListener { onQuickBotClicked?.invoke() }
        authHintBotLink.setOnClickListener { onAuthHintBotClicked?.invoke() }
        routeAllServers.setOnClickListener { onAllServersClicked?.invoke() }
    }

    /**
     * Re-renders the list's active/selected styling. Re-reads the servers and
     * the active server id from [Prefs] first: selection state changes outside
     * this view (a row tap writes Prefs directly), so the cached fields can be
     * stale — rendering with them made tapping an Xray row look like it did
     * nothing while the Whitelist Bypass row (checked against Prefs live in
     * [isActiveEntry]) re-styled correctly.
     */
    fun refresh() {
        currentServers = Prefs.xraySavedServers
        activeServerId = Prefs.xrayActiveServerId
        renderList()
        updateHeaderSub()
        // Keep the headline above the connect button on the just-picked target
        // (it used to stay stuck on the last connected server's name).
        applyRouteTarget()
    }

    fun bindXrayServers(servers: List<XrayServer>, activeId: String) {
        currentServers = servers
        activeServerId = activeId
        // Drop cache entries for servers that no longer exist.
        val liveIds = servers.map { it.id }.toSet()
        pingCache.keys.retainAll(liveIds)
        renderList()
        updateHeaderSub()
    }

    fun bindHero(connected: Boolean, status: VpnStatus?) {
        val context = root.context
        val accent = context.getColor(R.color.accent_emerald)
        connectBarIcon.setColorFilter(accent)
        connectBarLabel.setTextColor(accent)
        val connecting = status == VpnStatus.CONNECTING || status == VpnStatus.STARTING ||
            status == VpnStatus.STOPPING || status == VpnStatus.CALL_CONNECTED ||
            status == VpnStatus.DATACHANNEL_OPEN
        if (connected) {
            connectBarLabel.text = context.getString(R.string.hero_disconnect)
            routeStatusLine.text = context.getString(R.string.route_status_connected, routeUptime.text.ifBlank { "00:00:00" })
            routeStatusLine.setShadowLayer(14f, 0f, 0f, context.getColor(R.color.accent_glow))
            applyRouteTarget()
            headerSub.text = context.getString(R.string.main_sub_live)
            routeExchangeRow.visibility = View.VISIBLE
            routeStatsRow.visibility = View.VISIBLE
            routeSpark.visibility = View.VISIBLE
            routePing.text = activePingText()
            statusDetail.visibility = View.GONE
            // Connected: only the service-availability check stays; the
            // ping-all / refresh-subscription pair is hidden.
            serverActionsRow.visibility = View.GONE
            pingRow.visibility = View.VISIBLE
            updateAuthHint()
            collapsedToActive = true
            renderList()
        } else if (connecting) {
            connectBarLabel.text = context.getString(R.string.hero_cancel)
            routeStatusLine.text = context.getString(
                if (status == VpnStatus.STOPPING) R.string.status_headline_disconnected
                else R.string.status_headline_connecting
            )
            routeStatusLine.setShadowLayer(0f, 0f, 0f, 0)
            applyRouteTarget()
            routeExchangeRow.visibility = View.GONE
            routeStatsRow.visibility = View.GONE
            routeSpark.visibility = View.GONE
            routeSpark.reset()
            statusDetail.visibility = View.VISIBLE
            serverActionsRow.visibility = View.GONE
            pingRow.visibility = View.GONE
            authHint.visibility = View.GONE
            authHintBotLink.visibility = View.GONE
            collapsedToActive = true
            renderList()
        } else {
            connectBarLabel.text = context.getString(R.string.hero_connect)
            routeStatusLine.text = context.getString(R.string.status_headline_disconnected)
            routeStatusLine.setShadowLayer(0f, 0f, 0f, 0)
            applyRouteTarget()
            routeExchangeRow.visibility = View.GONE
            routeStatsRow.visibility = View.GONE
            routeSpark.visibility = View.GONE
            routeSpark.reset()
            statusDetail.visibility = View.VISIBLE
            statusDetail.text = disconnectedDetailText()
            serverActionsRow.visibility = View.VISIBLE
            pingRow.visibility = View.GONE
            authHint.visibility = View.GONE
            authHintBotLink.visibility = View.GONE
            collapsedToActive = false
            renderList()
            updateHeaderSub()
            resetPingState()
        }
    }

    /** City headline + exit endpoint from the currently selected connect target. */
    private fun applyRouteTarget() {
        val context = root.context
        if (Prefs.connectionMode == ConnectionMode.XRAY) {
            val server = Prefs.activeXrayServer
            if (server != null) {
                routeCity.text = styledName(server.remark)
                routeExitValue.text = server.address
            } else {
                routeCity.text = context.getString(R.string.status_headline_disconnected)
                routeExitValue.text = "—"
            }
        } else {
            routeCity.text = context.getString(R.string.connection_type_whitelist_bypass)
            routeExitValue.text = "cors.connect"
        }
        routeDeviceValue.text = deviceNetworkLabel()
    }

    /** "City · CODE" with the part after the last " · " dimmed (prototype 1b). */
    private fun styledName(remark: String): CharSequence {
        val idx = remark.lastIndexOf(" · ")
        if (idx < 0) return remark
        val span = android.text.SpannableString(remark)
        span.setSpan(
            android.text.style.ForegroundColorSpan(root.context.getColor(R.color.ink_3)),
            idx, remark.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return span
    }

    private fun deviceNetworkLabel(): String = try {
        val cm = root.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "—"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "—"
        }
    } catch (_: Exception) {
        "—"
    }

    private fun activePingText(): String {
        if (Prefs.connectionMode != ConnectionMode.XRAY) return "—"
        val rtt = pingCache[Prefs.xrayActiveServerId] ?: return root.context.getString(R.string.ping_measuring)
        return if (rtt >= 0) root.context.getString(R.string.ping_ms, rtt)
        else root.context.getString(R.string.ping_timeout)
    }

    // The "Whitelist Bypass" row is always present, so there's always at
    // least one selectable entry — the "nothing to pick" empty state
    // (status_detail_no_calls) can't happen anymore.
    private fun disconnectedDetailText(): String = root.context.getString(R.string.status_detail_pick_call)

    fun bindStatus(status: VpnStatus) {
        val labelRes = status.labelRes
        statusDetail.text = if (status == VpnStatus.PORT_BUSY) {
            root.context.getString(labelRes, Prefs.socksPort)
        } else {
            root.context.getString(labelRes)
        }
    }

    fun bindStatusText(text: String) {
        statusDetail.text = text
    }

    /**
     * The amber "authorization required" line under the "Connected"
     * headline. Shown whenever the tunnel is up but no Cors.Connect session
     * exists (anonymous temp instance); disappears the moment a subscription
     * sign-in fills [Prefs.corsUsername]. Also called from
     * [MainFragment.onXrayServersChanged] so a sign-in mid-session hides it
     * without waiting for the next connect.
     */
    fun updateAuthHint() {
        // Only meaningful for the Whitelist Bypass flow (anonymous temp
        // instance) — an Xray server carries no 5-minute cap. Shown while the
        // tunnel is up / coming up without a Cors.Connect sign-in.
        val show = collapsedToActive &&
            Prefs.connectionMode == ConnectionMode.INSTANCE &&
            Prefs.corsUsername.isBlank()
        authHint.visibility = if (show) View.VISIBLE else View.GONE
        authHintBotLink.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun setStats(uptimeText: String, mode: String) {
        routeUptime.text = uptimeText
        routePing.text = activePingText()
        routeStatusLine.text = root.context.getString(R.string.route_status_connected, uptimeText)
    }

    /** Live RX/TX byte rate — feeds the two speed cells and the sparkline. */
    fun setThroughput(rxBytesPerSec: Long, txBytesPerSec: Long) {
        routeRx.text = formatSpeed(rxBytesPerSec)
        routeTx.text = formatSpeed(txBytesPerSec)
        routeSpark.push(rxBytesPerSec + txBytesPerSec)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val bits = bytesPerSec * 8.0
        return when {
            bits >= 1_000_000 -> String.format("%.1f Mbps", bits / 1_000_000)
            bits >= 1_000 -> String.format("%.0f Kbps", bits / 1_000)
            else -> "0 Kbps"
        }
    }

    /**
     * Subscription quota, shown in the compact metric grid above the connect
     * button. Null arguments (no subscription / no `subscription-userinfo`
     * header yet) hide the affected cell.
     */
    fun bindSubscriptionStats(trafficText: String?, daysLeftText: String?, trafficFraction: Float? = null) {
        routeUsedCell.visibility = if (trafficText != null) View.VISIBLE else View.INVISIBLE
        routeLeftCell.visibility = if (daysLeftText != null) View.VISIBLE else View.INVISIBLE
        trafficText?.let { routeUsed.text = it }
        daysLeftText?.let { routeLeft.text = it }
    }

    /** The check now runs inside the centered [ServiceCheckDialog]. */
    fun showServicesRunning() {
        pingButtonLabel.text = root.context.getString(R.string.services_running)
        pingButtonDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
    }

    /**
     * Spins the refresh icon while a subscription refresh is running. Only the
     * inner [refreshIcon] rotates — animating the button itself used to spin
     * its rounded-rect background too.
     */
    fun showSubscriptionsRefreshing() {
        subscriptionsRefreshing = true
        refreshIcon.animate().rotationBy(360f).setDuration(800).withEndAction {
            // Keep spinning until stopSubscriptionsRefreshing() flips the flag.
            if (subscriptionsRefreshing) showSubscriptionsRefreshing()
        }.start()
    }

    fun stopSubscriptionsRefreshing() {
        subscriptionsRefreshing = false
    }

    private var subscriptionsRefreshing = false

    fun showServicesResults(results: List<MainFragment.ServiceStatus>) {
        pingButtonLabel.text = root.context.getString(R.string.services_run)
        // Left dot goes green once at least one service came back reachable
        // through the tunnel, amber if the check ran but nothing answered.
        val anyOk = results.any { it.ok }
        pingButtonDot.setBackgroundResource(
            if (anyOk) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot_warn,
        )
    }

    fun pauseAnimations() {
        pingButton.clearAnimation()
    }

    fun resumeAnimations() {
    }

    fun detach() {
        pingButton.clearAnimation()
        pingCheckSpinning = false
    }

    /**
     * Merged list: the single fixed "Whitelist Bypass" entry (always first —
     * see [ConnectTarget.WhitelistBypass]) followed by the imported Xray
     * servers. There's no per-item instance data or "add another instance"
     * concept anymore — Whitelist Bypass handles its entire connection flow
     * (auto-provision + join) as one action once selected and the hero
     * button is pressed (see [bypass.whitelist.MainActivity.onConnectPressed]).
     */
    private fun renderList() {
        callsList.removeAllViews()
        val entries: List<ConnectTarget> = listOf(ConnectTarget.WhitelistBypass) +
            currentServers.map { ConnectTarget.Xray(it) }
        val visibleEntries = if (collapsedToActive) {
            entries.filter { isActiveEntry(it) }
        } else {
            entries
        }
        emptyCta.visibility = View.GONE
        callsList.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(root.context)
        visibleEntries.forEach { target ->
            val row = inflater.inflate(R.layout.item_call_row, callsList, false)
            row.clipToOutline = true
            when (target) {
                is ConnectTarget.WhitelistBypass -> bindWhitelistBypassRow(row, isActive = isActiveEntry(target))
                is ConnectTarget.Xray -> bindServerRow(row, target.server, isActive = isActiveEntry(target))
                is ConnectTarget.Instance -> Unit // never appears in the list — see ConnectTarget.Instance's doc
            }
            row.setOnClickListener { onEntrySelected?.invoke(target) }
            row.setOnLongClickListener {
                onEntryLongPressed?.invoke(target)
                true
            }
            callsList.addView(row)
        }
    }

    /**
     * Only one entry can be "active" (selected as the hero button's connect
     * target) at a time — Whitelist Bypass and an Xray server can't both be
     * selected simultaneously, since [Prefs.connectionMode] picks exactly one
     * of them to actually connect. Both halves are read from Prefs live (not
     * the cached fields) so a selection write is reflected by the next
     * [renderList] no matter which path triggered it.
     */
    private fun isActiveEntry(target: ConnectTarget): Boolean = when (target) {
        is ConnectTarget.WhitelistBypass -> Prefs.connectionMode == ConnectionMode.INSTANCE
        is ConnectTarget.Xray -> Prefs.connectionMode == ConnectionMode.XRAY && target.server.id == Prefs.xrayActiveServerId
        is ConnectTarget.Instance -> false
    }

    private fun bindWhitelistBypassRow(row: View, isActive: Boolean) {
        val context = row.context
        val nameView = row.findViewById<TextView>(R.id.rowName)
        val linkView = row.findViewById<TextView>(R.id.rowLink)
        val protocolView = row.findViewById<TextView>(R.id.rowProtocol)
        val statusDot = row.findViewById<View>(R.id.rowStatusDot)

        nameView.text = root.context.getString(R.string.connection_type_whitelist_bypass)
        linkView.text = root.context.getString(R.string.whitelist_bypass_row_sub)
        // The protocol line would just duplicate the row name here.
        protocolView.visibility = View.GONE
        row.findViewById<View>(R.id.rowPing).visibility = View.GONE

        applyRowActiveState(row, nameView, linkView, protocolView, statusDot, isActive, context)
    }

    private fun bindServerRow(row: View, server: XrayServer, isActive: Boolean) {
        val context = row.context
        val nameView = row.findViewById<TextView>(R.id.rowName)
        val linkView = row.findViewById<TextView>(R.id.rowLink)
        val protocolView = row.findViewById<TextView>(R.id.rowProtocol)
        val statusDot = row.findViewById<View>(R.id.rowStatusDot)

        nameView.text = server.remark
        // Connection details line: protocol · transport · security (no
        // address — see the row layout), e.g. "VLESS · WS · TLS". Default
        // transport (TCP) and no-security rows collapse to just the protocol.
        val details = buildList {
            add(server.protocol.wireValue.uppercase())
            if (server.network != bypass.whitelist.xray.XrayNetwork.TCP) add(server.network.wireValue.uppercase())
            if (server.security != bypass.whitelist.xray.XraySecurity.NONE) add(server.security.wireValue.uppercase())
        }
        protocolView.text = details.joinToString(" · ")
        linkView.visibility = View.GONE

        // The ip:port line is gone; every server row shows a live TCP ping on
        // the right side instead (see [measurePing] and the header ping
        // button, which re-measures all rows on demand).
        val pingView = row.findViewById<TextView>(R.id.rowPing)
        pingView.visibility = View.VISIBLE
        bindPing(pingView, server)

        applyRowActiveState(row, nameView, linkView, protocolView, statusDot, isActive, context)
    }

    /**
     * Fills [linkView] with the server's ping, measuring it on a background
     * thread if there's no fresh cached value. Rows are re-inflated on every
     * [renderList] (selection change, connect/disconnect…), so results are
     * cached per server id to avoid re-probing on each render; the text is
     * only updated if the row is still attached to the window.
     */
    private fun bindPing(linkView: TextView, server: XrayServer) {
        val cached = pingCache[server.id]
        if (cached != null) {
            linkView.text = if (cached >= 0) {
                root.context.getString(R.string.ping_ms, cached)
            } else {
                root.context.getString(R.string.ping_timeout)
            }
            colorPing(linkView, cached)
            return
        }
        linkView.text = root.context.getString(R.string.ping_measuring)
        colorPing(linkView, null)
        if (!pingInFlight.add(server.id)) return
        updatePingCheckSpin()
        thread {
            val rtt = measurePing(server)
            pingCache[server.id] = rtt ?: -1
            pingInFlight.remove(server.id)
            mainHandler.post {
                updatePingCheckSpin()
                if (linkView.isAttachedToWindow && linkView.visibility == View.VISIBLE) {
                    linkView.text = if (rtt != null) {
                        root.context.getString(R.string.ping_ms, rtt)
                    } else {
                        root.context.getString(R.string.ping_timeout)
                    }
                    colorPing(linkView, rtt ?: -1)
                }
            }
        }
    }

    /**
     * Re-measures the ping of every listed server — the header ping button's
     * action. Rows re-show "Ping…" while probing (see [bindPing]).
     */
    fun invalidatePings() {
        pingCache.clear()
        renderList()
    }

    /** Spins the header ping icon while any server ping is being measured. */
    private fun updatePingCheckSpin() {
        val busy = pingInFlight.isNotEmpty()
        if (busy && !pingCheckSpinning) {
            pingCheckSpinning = true
            spinPingCheckIcon()
        } else if (!busy) {
            pingCheckSpinning = false
        }
    }

    private fun spinPingCheckIcon() {
        if (!pingCheckSpinning) return
        pingCheckIcon.animate().rotationBy(360f).setDuration(700).withEndAction {
            if (pingCheckSpinning) spinPingCheckIcon()
        }.start()
    }

    /** Prototype 1a: sub-60ms pings read in accent, everything else muted. */
    private fun colorPing(view: TextView, rttMs: Int?) {
        val color = if (rttMs != null && rttMs in 0..59) R.color.accent_emerald else R.color.ink_3
        view.setTextColor(root.context.getColor(color))
    }

    /** Plain TCP connect RTT — the classic "tcping" proxy latency estimate. */
    private fun measurePing(server: XrayServer): Int? = try {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.address, server.port), PING_TIMEOUT_MS)
        }
        ((System.nanoTime() - started) / 1_000_000).toInt()
    } catch (e: IOException) {
        null
    }

    private fun applyRowActiveState(
        row: View,
        nameView: TextView,
        linkView: TextView,
        protocolView: TextView,
        statusDot: View,
        isActive: Boolean,
        context: android.content.Context,
    ) {
        if (isActive) {
            row.setBackgroundResource(R.drawable.bg_destination_card_active)
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
        } else {
            row.setBackgroundResource(R.drawable.bg_destination_card)
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
            nameView.setTextColor(context.getColor(R.color.ink))
            linkView.setTextColor(context.getColor(R.color.ink_3))
        }
        protocolView.setTextColor(context.getColor(R.color.ink_3))
    }

    private fun updateHeaderSub() {
        if (collapsedToActive) return
        // +1 for the always-present Whitelist Bypass entry.
        val count = 1 + currentServers.size
        headerSub.text = root.context.resources.getQuantityString(R.plurals.main_sub_count, count, count)
    }

    private fun resetPingState() {
        pingButton.clearAnimation()
        pingButtonLabel.text = root.context.getString(R.string.services_run)
        pingButtonDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
    }
}
