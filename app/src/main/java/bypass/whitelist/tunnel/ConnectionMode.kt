package bypass.whitelist.tunnel

/**
 * Type of a connection entry in the unified Main screen list: the existing
 * on-demand "instance/call" flow (join a generated call, tunnel over it,
 * shown to the user as "Whitelist Bypass"), or a standard Xray connection to
 * an imported server. There's no separate mode switcher anymore — this only
 * tags which of [bypass.whitelist.util.Prefs.activeDestination] /
 * [bypass.whitelist.util.Prefs.activeXrayServer] is the currently selected
 * "active" entry.
 */
enum class ConnectionMode(val label: String) {
    INSTANCE("Whitelist Bypass"),
    XRAY("Xray"),
}
