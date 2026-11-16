package bypass.whitelist.tunnel

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import bypass.whitelist.MainActivity
import bypass.whitelist.R
import bypass.whitelist.util.Prefs

/**
 * Quick-settings tile: one tap reconnects the *last used* connection
 * (Xray server, saved instance or Whitelist Bypass) or disconnects whatever
 * is running. The tile shows the target name and the current state.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            stopAll()
            return
        }
        if (isLocked) {
            unlockAndRun { connectLast() }
        } else {
            connectLast()
        }
    }

    /** Restores the last Xray server as the active one (auto-best may have moved the selection). */
    private fun lastXrayServer() = Prefs.xrayLastConnectedId
        .takeIf { id -> id.isNotEmpty() && Prefs.xraySavedServers.any { it.id == id } }
        ?.let { id -> Prefs.xraySavedServers.first { it.id == id } }
        ?: Prefs.activeXrayServer

    private fun connectLast() {
        when {
            Prefs.connectionMode == ConnectionMode.XRAY && lastXrayServer() != null -> {
                val server = lastXrayServer()!!
                Prefs.xrayActiveServerId = server.id
                if (VpnService.prepare(this) != null) {
                    // No VPN consent yet — only the activity can ask for it.
                    openApp(autoStart = true)
                    return
                }
                try {
                    ContextCompat.startForegroundService(this, Intent(this, XrayVpnService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start XrayVpnService: ${e.message}", e)
                    openApp(autoStart = true)
                    return
                }
                showConnecting()
            }
            Prefs.connectionMode == ConnectionMode.INSTANCE && Prefs.activeDestination != null -> {
                try {
                    ContextCompat.startForegroundService(this, Intent(this, HeadlessSessionService::class.java))
                    showConnecting()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start HeadlessSessionService: ${e.message}", e)
                }
            }
            // Whitelist Bypass provisions an instance through the app's UI flow.
            else -> openApp(autoStart = true)
        }
    }

    private fun openApp(autoStart: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (autoStart) intent.action = MainActivity.ACTION_AUTO_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun stopAll() {
        HeadlessSessionService.requestStop(this)
        TunnelVpnService.requestStop(this)
        XrayVpnService.requestStop(this)
        ProxyService.requestStop(this)
        render(Tile.STATE_INACTIVE, getString(R.string.tile_disconnected))
    }

    private fun showConnecting() = render(Tile.STATE_ACTIVE, getString(R.string.tile_connecting))

    /** Name of what a tap would connect to / is connected to. */
    private fun targetName(): String = when {
        Prefs.connectionMode == ConnectionMode.XRAY -> lastXrayServer()?.remark
        else -> Prefs.activeDestination?.name ?: getString(R.string.tile_whitelist_bypass)
    } ?: getString(R.string.app_name)

    private fun updateTile() {
        when {
            TunnelServiceState.isTunnelActive(this) ->
                render(Tile.STATE_ACTIVE, getString(R.string.tile_active))
            TunnelServiceState.isHeadlessSessionRunning(this) ->
                render(Tile.STATE_ACTIVE, getString(R.string.tile_connecting))
            else -> render(Tile.STATE_INACTIVE, getString(R.string.tile_disconnected))
        }
    }

    private fun render(state: Int, status: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.icon = Icon.createWithResource(this, R.drawable.ic_power)
        val name = targetName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = name
            tile.subtitle = status
        } else {
            tile.label = if (state == Tile.STATE_ACTIVE) "$name · $status" else name
        }
        tile.contentDescription = "$name, $status"
        tile.updateTile()
    }
}
