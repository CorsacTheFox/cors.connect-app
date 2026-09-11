package bypass.whitelist.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.tunnel.ConnectTarget
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.util.Prefs
import bypass.whitelist.xray.XrayServer
import bypass.whitelist.xray.XraySubscriptionRefresher
import bypass.whitelist.xray.isSupportedXrayShareLink
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainFragment : Fragment(R.layout.fragment_main_screen), XrayServersListener {

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim().orEmpty()
        if (scanned.isEmpty()) return@registerForActivityResult
        when {
            // Xray share link -> the "add server" sheet as before.
            scanned.isSupportedXrayShareLink() ->
                AddXraySubscriptionSheet.show(parentFragmentManager, scanned)
            // A call link (VK / Telemost / WB Stream / DION) -> same sheet,
            // which auto-detects the type and saves it as a destination.
            bypass.whitelist.tunnel.CallPlatform.isCallLink(scanned) ->
                AddXraySubscriptionSheet.show(parentFragmentManager, scanned)
            // Remnawave subscription link (…/sub/<token> or a custom sub
            // domain): it doubles as an xray subscription feed — the add
            // sheet imports the servers AND triggers the implicit
            // Cors.Connect sign-in with the same link.
            scanned.startsWith("http", ignoreCase = true) && scanned.contains("/sub/") ->
                AddXraySubscriptionSheet.show(parentFragmentManager, scanned)
            else ->
                Toast.makeText(requireContext(), R.string.scan_qr_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private var content: MainFragmentView? = null
    private var pendingStatus: VpnStatus? = null
    private var connectedSinceMs: Long = 0L
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastSampleMs: Long = 0L
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    /** One row of the "service availability" check: outcome + RTT when up. */
    data class ServiceStatus(val name: String, val ok: Boolean, val rttMs: Int)

    interface Host {
        fun onConnectPressed(target: ConnectTarget)
        fun onDisconnectPressed()
        fun onServiceCheckPressed(callback: (List<ServiceStatus>) -> Unit)
        fun isTunnelActive(): Boolean
        fun currentStatus(): VpnStatus?
    }

    companion object {
        /**
         * Popular services probed by the availability button, in display
         * order: display name → host used for the probe (HTTPS :443).
         */
        val SERVICE_TARGETS: List<Pair<String, String>> = listOf(
            "Instagram" to "instagram.com",
            "Facebook" to "facebook.com",
            "X / Twitter" to "x.com",
            "YouTube" to "youtube.com",
            "Discord" to "discord.com",
            "Signal" to "signal.org",
            "LinkedIn" to "linkedin.com",
            "ChatGPT" to "chatgpt.com",
        )
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        val container = MainFragmentView(rootView)
        content = container

        container.bindXrayServers(Prefs.xraySavedServers, Prefs.xrayActiveServerId)
        container.bindHero(connected = isHostConnected(), status = hostStatus())
        if (!isResumed) container.pauseAnimations()

        // The only thing left to "add" is an Xray subscription/server — the
        // Whitelist Bypass entry is fixed and always in the list.
        container.onAddCallClicked = { AddXraySubscriptionSheet.show(parentFragmentManager) }
        container.onRefreshSubscriptionsClicked = { refreshSubscriptions() }
        container.onPingCheckClicked = { content?.invalidatePings() }
        container.onScanQrClicked = {
            scanQrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_qr_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setCaptureActivity(QrCaptureActivity::class.java)
            )
        }
        container.onHeroPressed = {
            if (isHostConnected() || isHostConnecting()) {
                host()?.onDisconnectPressed()
            } else {
                when (Prefs.connectionMode) {
                    ConnectionMode.INSTANCE -> {
                        val destination = Prefs.activeDestination
                        if (destination != null) {
                            host()?.onConnectPressed(ConnectTarget.Instance(destination))
                        } else {
                            confirmWhitelistThenConnect {
                                host()?.onConnectPressed(ConnectTarget.WhitelistBypass)
                            }
                        }
                    }
                    ConnectionMode.XRAY -> Prefs.activeXrayServer?.let {
                        host()?.onConnectPressed(ConnectTarget.Xray(it))
                    }
                }
            }
        }
        container.onServiceCheckPressed = {
            container.showServicesRunning()
            ServiceCheckDialog.open(parentFragmentManager)
            host()?.onServiceCheckPressed { results ->
                // The check is sequential — every callback carries one more
                // row; only the final batch resets the button label.
                if (results.size >= SERVICE_TARGETS.size) {
                    container.showServicesResults(results)
                }
                ServiceCheckDialog.publish(results)
            }
        }
        // Quick-access chips: the settings a user tweaks often, right on the
        // main screen instead of buried in the settings tab.
        container.onQuickTunnelModeClicked = {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_tunnel_mode),
                options = TunnelMode.entries.map { ChoiceActionSheet.Option(it.name, getString(it.labelRes)) },
                selectedId = Prefs.tunnelMode.name,
            ) { picked ->
                val newMode = TunnelMode.valueOf(picked.id)
                if (newMode != Prefs.tunnelMode) {
                    Prefs.tunnelMode = newMode
                    (activity as? SettingsScreenFragment.Host)?.onTunnelModeChanged(newMode)
                }
            }
        }
        container.onQuickSplitClicked = {
            (activity as? MainActivityHost)?.pushSubPage(SplitTunnelingScreenFragment())
        }
        container.onQuickDnsClicked = { DnsActionSheet.show(parentFragmentManager) { } }
        // Subscription bot chip — opens @corsxray2bot in Telegram (or the
        // web profile as fallback) to check / buy a subscription.
        container.onQuickBotClicked = {
            openSubscriptionBot()
        }
        // The "5-minute limit" warning links straight to the bot to get a subscription.
        container.onAuthHintBotClicked = {
            openSubscriptionBot()
        }
        container.onAllServersClicked = {
            (activity as? MainActivityHost)?.openServersTab()
        }
        container.onEntrySelected = { target ->
            when (target) {
                is ConnectTarget.WhitelistBypass -> {
                    Prefs.activeDestinationId = ""
                    Prefs.connectionMode = ConnectionMode.INSTANCE
                }
                is ConnectTarget.Xray -> {
                    Prefs.xrayActiveServerId = target.server.id
                    Prefs.connectionMode = ConnectionMode.XRAY
                }
                is ConnectTarget.Instance -> {
                    Prefs.activeDestinationId = target.config.id
                    Prefs.connectionMode = ConnectionMode.INSTANCE
                }
            }
            container.refresh()
        }
        container.onEntryLongPressed = { target ->
            when (target) {
                is ConnectTarget.Xray -> showServerRowMenu(target.server)
                is ConnectTarget.Instance -> showDestinationRowMenu(target.config)
                is ConnectTarget.WhitelistBypass -> Unit // no per-row menu
            }
        }

        updateSubscriptionStats()

        pendingStatus?.let { container.bindStatus(it) }
        pendingStatus = null
    }

    override fun onResume() {
        super.onResume()
        content?.bindXrayServers(Prefs.xraySavedServers, Prefs.xrayActiveServerId)
        content?.bindHero(connected = isHostConnected(), status = hostStatus())
        updateSubscriptionStats()
        content?.resumeAnimations()
        // Auto-refresh the on-screen pings every 10s, both in the list and the
        // connected route view.
        content?.startPingUpdates()
        if (isHostConnected()) {
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000L)
        }
    }

    override fun onPause() {
        super.onPause()
        content?.pauseAnimations()
        content?.stopPingUpdates()
        tickHandler.removeCallbacks(tickRunnable)
    }

    override fun onDestroyView() {
        tickHandler.removeCallbacks(tickRunnable)
        content?.detach()
        content = null
        super.onDestroyView()
    }

    fun onStatusChanged(status: VpnStatus) {
        val container = content
        if (container != null) {
            container.bindStatus(status)
        } else {
            pendingStatus = status
        }
        if (isHostConnected()) refreshStats()
    }

    fun onStatusTextChanged(text: String) {
        content?.bindStatusText(text)
    }

    fun onConnectedChanged(connected: Boolean) {
        if (connected) {
            if (connectedSinceMs == 0L) connectedSinceMs = System.currentTimeMillis()
        } else {
            connectedSinceMs = 0L
        }
        lastSampleMs = 0L
        if (!isResumed) return
        content?.bindHero(connected = connected, status = hostStatus())
        if (connected) {
            refreshStats()
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000L)
        } else {
            tickHandler.removeCallbacks(tickRunnable)
        }
    }

    /**
     * Still called from [bypass.whitelist.MainActivity.onForgetAllDestinations]
     * (Settings screen) — kept as a plain refresh now that the list no longer
     * renders per-instance rows to bind.
     */
    fun onDestinationsChanged() {
        content?.refresh()
    }

    override fun onXrayServersChanged() {
        content?.bindXrayServers(Prefs.xraySavedServers, Prefs.xrayActiveServerId)
        // A subscription import can complete the implicit Cors.Connect
        // sign-in — re-evaluate the "authorization required" hint.
        content?.updateAuthHint()
        // A refresh/import may have brought fresh quota numbers.
        content?.let { updateSubscriptionStats() }
    }

    /**
     * Manual subscription refresh from the header button: re-fetches every
     * subscription and swaps the server list without touching the running
     * tunnel (see [XraySubscriptionRefresher]). Toasts the outcome and
     * re-renders the main list with the fresh servers.
     */
    private fun refreshSubscriptions() {
        val view = content ?: return
        if (Prefs.xraySubscriptions.isEmpty()) {
            Toast.makeText(requireContext(), R.string.xray_refresh_none, Toast.LENGTH_SHORT).show()
            return
        }
        view.showSubscriptionsRefreshing()
        XraySubscriptionRefresher.refreshAll { result ->
            if (!isAdded) return@refreshAll
            view.stopSubscriptionsRefreshing()
            view.bindXrayServers(Prefs.xraySavedServers, Prefs.xrayActiveServerId)
            // The refresh may have brought fresh quota numbers (traffic/expiry).
            updateSubscriptionStats()
            val res = when {
                result.okSubscriptions == 0 -> getString(R.string.xray_refresh_failed)
                else -> getString(
                    R.string.xray_refresh_done,
                    result.okSubscriptions,
                    result.totalSubscriptions,
                    result.totalServers,
                )
            }
            Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showServerRowMenu(server: XrayServer) {
        MenuActionSheet.show(
            manager = parentFragmentManager,
            title = server.remark,
            subtitle = server.summary,
            items = listOf(
                MenuActionSheet.MenuItem("active", getString(R.string.xray_server_menu_set_active), R.drawable.ic_check),
                MenuActionSheet.MenuItem("rename", getString(R.string.xray_server_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("delete", getString(R.string.xray_server_menu_delete), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "active" -> {
                    Prefs.xrayActiveServerId = server.id
                    onXrayServersChanged()
                }
                "rename" -> promptRenameServer(server)
                "delete" -> confirmDeleteServer(server)
            }
        }
    }

    private fun showDestinationRowMenu(config: bypass.whitelist.tunnel.CallConfig) {
        MenuActionSheet.show(
            manager = parentFragmentManager,
            title = config.name,
            subtitle = config.url,
            items = listOf(
                MenuActionSheet.MenuItem("active", getString(R.string.xray_server_menu_set_active), R.drawable.ic_check),
                MenuActionSheet.MenuItem("rename", getString(R.string.xray_server_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("delete", getString(R.string.xray_server_menu_delete), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "active" -> {
                    Prefs.activeDestinationId = config.id
                    Prefs.connectionMode = ConnectionMode.INSTANCE
                    onDestinationsChanged()
                }
                "rename" -> promptRenameDestination(config)
                "delete" -> confirmDeleteDestination(config)
            }
        }
    }

    private fun promptRenameDestination(config: bypass.whitelist.tunnel.CallConfig) {
        InputActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.xray_server_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = config.name,
        ) { newName ->
            if (newName != config.name) {
                Prefs.renameDestination(config.id, newName)
                onDestinationsChanged()
            }
        }
    }

    private fun confirmDeleteDestination(config: bypass.whitelist.tunnel.CallConfig) {
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.xray_server_delete_title),
            subtitle = getString(R.string.xray_server_delete_confirm, config.name),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeDestination(config.id)
            onDestinationsChanged()
        }
    }

    private fun promptRenameServer(server: XrayServer) {
        InputActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.xray_server_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = server.remark,
        ) { newName ->
            if (newName != server.remark) {
                Prefs.renameXrayServer(server.id, newName)
                onXrayServersChanged()
            }
        }
    }

    private fun confirmDeleteServer(server: XrayServer) {
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.xray_server_delete_title),
            subtitle = getString(R.string.xray_server_delete_confirm, server.remark),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeXrayServer(server.id)
            onXrayServersChanged()
        }
    }

    private fun refreshStats() {
        val view = content ?: return
        val uptimeMs = if (connectedSinceMs > 0L) System.currentTimeMillis() - connectedSinceMs else 0L
        val modeLabel = when (Prefs.connectionMode) {
            ConnectionMode.INSTANCE -> {
                val active = Prefs.activeDestination
                val mode = if (active != null) Prefs.activeTunnelMode.forPlatform(active.platform) else Prefs.tunnelMode
                getString(mode.labelRes)
            }
            ConnectionMode.XRAY -> getString(R.string.stat_mode_xray)
        }
        view.setStats(uptimeText = formatUptime(uptimeMs), mode = modeLabel)
        sampleThroughput(view)
    }

    /**
     * Device-wide RX/TX byte deltas since the last tick. While the tunnel is
     * up virtually all of it is tunnel traffic, so it's a fair real-time
     * read-out for the speed cells and the sparkline.
     */
    private fun sampleThroughput(view: MainFragmentView) {
        val rx = android.net.TrafficStats.getTotalRxBytes()
        val tx = android.net.TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        val invalid = rx == android.net.TrafficStats.UNSUPPORTED.toLong() ||
            tx == android.net.TrafficStats.UNSUPPORTED.toLong()
        if (!invalid && lastSampleMs > 0L && now > lastSampleMs) {
            val secs = (now - lastSampleMs) / 1000.0
            val rxRate = ((rx - lastRxBytes).coerceAtLeast(0) / secs).toLong()
            val txRate = ((tx - lastTxBytes).coerceAtLeast(0) / secs).toLong()
            view.setThroughput(rxRate, txRate)
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastSampleMs = now
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "00:00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    /** Telegram bot for checking / buying a subscription: @corsxray2bot. */
    private fun openSubscriptionBot() {
        val ctx = context ?: return
        // tg:// first (opens the app directly), web profile as fallback.
        val tgUri = android.net.Uri.parse("tg://resolve?domain=corsxray2bot")
        val webUri = android.net.Uri.parse("https://t.me/corsxray2bot")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, tgUri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, webUri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }

    /**
     * Fills the subscription quota card: traffic used (of total, or "used X"
     * for unlimited) and whole days left before the earliest expiry. Values
     * come from the `subscription-userinfo` header the panel sends with the
     * feed, captured by each subscription refresh. Aggregated across all
     * subscriptions; the card hides while nothing has reported anything.
     */
    private fun updateSubscriptionStats() {
        val view = content ?: return
        val subs = Prefs.xraySubscriptions
        val usedTotal = subs.sumOf { it.usedBytes }
        val quotaTotal = subs.sumOf { it.totalBytes }
        val expires = subs.map { it.expireAtSec }.filter { it > 0L }
        val earliestExpire = expires.minOrNull()

        val trafficText = when {
            usedTotal <= 0L && quotaTotal <= 0L -> null
            quotaTotal > 0L -> getString(
                R.string.stat_traffic_of,
                formatBytesCompact(usedTotal, quotaTotal),
                formatBytes(quotaTotal),
            )
            else -> getString(R.string.stat_traffic_unlimited, formatBytes(usedTotal))
        }
        val daysText = when {
            earliestExpire == null -> if (trafficText == null) null else getString(R.string.stat_no_expiry)
            else -> {
                val daysLeft = ((earliestExpire * 1000L - System.currentTimeMillis()) / 86_400_000L).toInt()
                if (daysLeft < 0) getString(R.string.stat_expired) else daysLeft.toString()
            }
        }
        val trafficFraction = if (quotaTotal > 0L) (usedTotal.toFloat() / quotaTotal) else null
        view.bindSubscriptionStats(trafficText, daysText, trafficFraction)
    }

    /**
     * Formats [bytes] but drops the unit suffix when it matches the unit that
     * [reference] would render in, so a "used / total" pair reads as
     * "10 / 100 GB" instead of "10.0 GB / 100.0 GB".
     */
    private fun formatBytesCompact(bytes: Long, reference: Long): String {
        val full = formatBytes(bytes)
        val unit = formatBytes(reference).substringAfterLast(' ', "")
        return if (unit.isNotEmpty() && full.endsWith(" $unit")) full.removeSuffix(" $unit") else full
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        var value = bytes.toDouble()
        var unit = 0
        val units = listOf("B", "KB", "MB", "GB", "TB")
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes.toInt()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
    }

    private fun host(): Host? = activity as? Host

    private fun isHostConnected(): Boolean = host()?.isTunnelActive() ?: false

    private fun isHostConnecting(): Boolean = when (hostStatus()) {
        VpnStatus.STOPPING,
        VpnStatus.CONNECTING,
        VpnStatus.STARTING,
        VpnStatus.CALL_CONNECTED,
        VpnStatus.DATACHANNEL_OPEN -> true
        else -> false
    }

    private fun hostStatus(): VpnStatus? = host()?.currentStatus()

    /**
     * Whitelist Bypass works around carrier-level (LTE) blocking, so on a
     * Wi-Fi / Ethernet network it usually does nothing useful. Ask for
     * confirmation first when the active network isn't cellular; [connect] runs
     * on confirm, or straight away when we're on mobile data or can't tell.
     */
    private fun confirmWhitelistThenConnect(connect: () -> Unit) {
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val onCellular = caps == null ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        if (onCellular) {
            connect()
            return
        }
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.wb_confirm_title),
            subtitle = getString(R.string.wb_confirm_sub),
            confirmLabel = getString(R.string.wb_confirm_yes),
            cancelLabel = getString(R.string.wb_confirm_no),
            onConfirm = { connect() },
        )
    }
}
