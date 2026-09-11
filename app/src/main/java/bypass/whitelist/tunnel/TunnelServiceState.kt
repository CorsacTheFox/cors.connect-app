package bypass.whitelist.tunnel

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.service.quicksettings.TileService
import bypass.whitelist.util.ParamCallback

object TunnelServiceState {

    @Volatile
    var vpnStatusCallback: ParamCallback<VpnStatus>? = null

    @Volatile
    var logCallback: ParamCallback<String>? = null

    fun isTunnelActive(context: Context): Boolean {
        // A stop that outlived its hard cap (hung native engine shutdown) is
        // not a running tunnel — counting it kept every reconnect attempt
        // seeing "tunnel is running" until the app was manually restarted.
        // ProxyService's stop is fully synchronous, so staleness can't apply.
        val vpnActive = TunnelVpnService.instance?.let {
            it.isRunning || it.startInProgress || (it.stopInProgress && !it.isStopStale())
        } == true
        val proxyActive = ProxyService.instance?.let { it.isRunning || it.stopInProgress } == true
        val xrayActive = XrayVpnService.instance?.let {
            it.isRunning || it.startInProgress || it.isAutoRecovering ||
                (it.stopInProgress && !it.isStopStale())
        } == true
        return vpnActive || proxyActive || xrayActive
    }

    fun isHeadlessSessionRunning(context: Context): Boolean {
        return HeadlessSessionService.hasLiveSession()
    }

    fun isAnyTunnelComponentRunning(context: Context): Boolean {
        return isTunnelActive(context) || isHeadlessSessionRunning(context)
    }

    fun hasForeignVpn(context: Context): Boolean {
        if (isTunnelActive(context)) return false
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
    }
}
