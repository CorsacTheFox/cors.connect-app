package bypass.whitelist.tunnel

import bypass.whitelist.xray.XrayServer

/** What the Main screen's hero button is about to connect (or is connected) to. */
sealed class ConnectTarget {
    /**
     * The single, fixed "Whitelist Bypass" list entry: selecting it and
     * pressing the hero button runs the entire flow —
     * auto-provisioning a Cors.Connect instance and joining it (see
     * [bypass.whitelist.MainActivity.startCorsConnect]) — as one action.
     * There's no user-supplied link/config to pick between anymore, so
     * unlike [Xray] this carries no per-item data.
     */
    object WhitelistBypass : ConnectTarget()

    /**
     * A manually added call link (VK / Telemost / WB Stream / DION), saved via
     * [bypass.whitelist.util.Prefs.addDestination] — see
     * [bypass.whitelist.ui.AddXraySubscriptionSheet], which auto-detects a
     * pasted/scanned call link and routes it here instead of the Xray import
     * path. Also used internally for the auto-provisioned call once
     * [WhitelistBypass] has produced one (see
     * [bypass.whitelist.MainActivity.startJoinFor]'s `pendingConnectTarget`
     * retry-after-reset path).
     */
    data class Instance(val config: CallConfig) : ConnectTarget()

    data class Xray(val server: XrayServer) : ConnectTarget()
}
