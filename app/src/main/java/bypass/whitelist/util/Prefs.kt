package bypass.whitelist.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import bypass.whitelist.BuildConfig
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.xray.XrayServer
import bypass.whitelist.xray.XraySubscription

object Prefs {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    var connectOnStart: Boolean
        get() = prefs.getBoolean(PrefsKeys.CONNECT_ON_START, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.CONNECT_ON_START, value) }

    var onboardingDone: Boolean
        get() = prefs.getBoolean(PrefsKeys.ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.ONBOARDING_DONE, value) }

    var tunnelMode: TunnelMode
        get() {
            val name = prefs.getString(PrefsKeys.TUNNEL_MODE, TunnelMode.DC.name)!!
            return try {
                TunnelMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                TunnelMode.DC
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.TUNNEL_MODE, value.name) }

    var splitTunnelingMode: SplitTunnelingMode
        get() {
            val title = prefs.getString(PrefsKeys.SPLIT_TUNNELING_MODE, SplitTunnelingMode.NONE.name)!!
            return try {
                SplitTunnelingMode.valueOf(title)
            } catch (_: IllegalArgumentException) {
                SplitTunnelingMode.NONE
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.SPLIT_TUNNELING_MODE, value.name) }

    var splitTunnelingPackages: Set<String>
        get() = prefs.getStringSet(PrefsKeys.SPLIT_TUNNELING_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(PrefsKeys.SPLIT_TUNNELING_PACKAGES, value) }

    var autofillEnabled: Boolean
        get() = prefs.getBoolean(PrefsKeys.AUTOFILL_ENABLED, true)
        set(value) = prefs.edit { putBoolean(PrefsKeys.AUTOFILL_ENABLED, value) }

    var autofillName: String
        get() = prefs.getString(PrefsKeys.AUTOFILL_NAME, "Hello")!!
        set(value) = prefs.edit { putString(PrefsKeys.AUTOFILL_NAME, value) }

    var headless: Boolean
        get() = prefs.getBoolean(PrefsKeys.HEADLESS, true)
        set(value) = prefs.edit { putBoolean(PrefsKeys.HEADLESS, value) }

    var socksHost: String
        get() = prefs.getString(PrefsKeys.SOCKS_HOST, Net.LOCALHOST) ?: Net.LOCALHOST
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_HOST, value) }

    var socksPort: Long
        get() = prefs.getLong(PrefsKeys.SOCKS_PORT, Ports.DEFAULT_SOCKS)
        set(value) = prefs.edit { putLong(PrefsKeys.SOCKS_PORT, value) }

    var socksAuthMode: SocksAuthMode
        get() {
            val name = prefs.getString(PrefsKeys.SOCKS_AUTH_MODE, SocksAuthMode.AUTO.name)!!
            return try {
                SocksAuthMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                SocksAuthMode.AUTO
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_AUTH_MODE, value.name) }

    var socksUser: String
        get() = prefs.getString(PrefsKeys.SOCKS_USER, "")!!
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_USER, value) }

    var socksPass: String
        get() = prefs.getString(PrefsKeys.SOCKS_PASS, "")!!
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_PASS, value) }

    var proxyOnly: Boolean
        get() = prefs.getBoolean(PrefsKeys.PROXY_ONLY, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.PROXY_ONLY, value) }

    var dnsMode: DnsMode
        get() {
            val name = prefs.getString(PrefsKeys.DNS_MODE, DnsMode.CUSTOM.name)!!
            return try {
                DnsMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                DnsMode.CUSTOM
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.DNS_MODE, value.name) }

    var dnsPrimary: String
        get() = prefs.getString(PrefsKeys.DNS_PRIMARY, Vpn.DNS_PRIMARY)!!
        set(value) = prefs.edit { putString(PrefsKeys.DNS_PRIMARY, value) }

    var dnsSecondary: String
        get() = prefs.getString(PrefsKeys.DNS_SECONDARY, Vpn.DNS_SECONDARY)!!
        set(value) = prefs.edit { putString(PrefsKeys.DNS_SECONDARY, value) }

    var vp8Fps: Int
        get() = prefs.getInt(PrefsKeys.VP8_FPS, VP8Defaults.FPS)
        set(value) = prefs.edit { putInt(PrefsKeys.VP8_FPS, value) }

    var vp8Batch: Int
        get() = prefs.getInt(PrefsKeys.VP8_BATCH, VP8Defaults.BATCH)
        set(value) = prefs.edit { putInt(PrefsKeys.VP8_BATCH, value) }

    var dualTrack: Boolean
        get() = prefs.getBoolean(PrefsKeys.DUAL_TRACK, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.DUAL_TRACK, value) }

    var reliable: Boolean
        get() = prefs.getBoolean(PrefsKeys.RELIABLE, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.RELIABLE, value) }

    var debug: Boolean
        get() = prefs.getBoolean(PrefsKeys.DEBUG, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.DEBUG, value) }

    var savedDestinations: List<CallConfig>
        get() = CallConfig.listFromJson(prefs.getString(PrefsKeys.SAVED_DESTINATIONS, "") ?: "")
        set(value) = prefs.edit { putString(PrefsKeys.SAVED_DESTINATIONS, CallConfig.listToJson(value)) }

    var activeDestinationId: String
        get() = prefs.getString(PrefsKeys.ACTIVE_DESTINATION_ID, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.ACTIVE_DESTINATION_ID, value) }

    // ---- Standard Xray connections ---------------------------------------

    /**
     * Type of whichever connection is currently selected as "active" in the
     * unified Main screen list (updated whenever the user taps a row, either
     * a call/instance entry or an Xray server entry). Used to disambiguate
     * [activeDestinationId] vs [xrayActiveServerId] as *the* active connection
     * now that there's a single list instead of separate tabs.
     */
    var connectionMode: ConnectionMode
        get() {
            val name = prefs.getString(PrefsKeys.CONNECTION_MODE, ConnectionMode.INSTANCE.name)!!
            return try {
                ConnectionMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                ConnectionMode.INSTANCE
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.CONNECTION_MODE, value.name) }

    var xraySavedServers: List<XrayServer>
        get() = XrayServer.listFromJson(prefs.getString(PrefsKeys.XRAY_SAVED_SERVERS, "") ?: "")
        set(value) = prefs.edit { putString(PrefsKeys.XRAY_SAVED_SERVERS, XrayServer.listToJson(value)) }

    var xrayActiveServerId: String
        get() = prefs.getString(PrefsKeys.XRAY_ACTIVE_SERVER_ID, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.XRAY_ACTIVE_SERVER_ID, value) }

    var xraySubscriptions: List<XraySubscription>
        get() = XraySubscription.listFromJson(prefs.getString(PrefsKeys.XRAY_SUBSCRIPTIONS, "") ?: "")
        set(value) = prefs.edit { putString(PrefsKeys.XRAY_SUBSCRIPTIONS, XraySubscription.listToJson(value)) }

    var xraySocksPort: Long
        get() = prefs.getLong(PrefsKeys.XRAY_SOCKS_PORT, Ports.DEFAULT_XRAY_SOCKS)
        set(value) = prefs.edit { putLong(PrefsKeys.XRAY_SOCKS_PORT, value) }

    /**
     * Loopback SOCKS5 port the *currently selected* connection's core exposes:
     * the Whitelist Bypass relay uses [socksPort] (1080), the standard Xray
     * core uses [xraySocksPort] (1081). The two are distinct so a still-
     * releasing relay can't block the other mode's next connect.
     */
    val activeLoopbackSocksPort: Long
        get() = if (connectionMode == ConnectionMode.XRAY) xraySocksPort else socksPort

    // Split tunneling is shared globally: see [splitTunnelingMode]/[splitTunnelingPackages]
    // above, used by both TunnelVpnService (instance/call) and XrayVpnService.

    val activeXrayServer: XrayServer?
        get() {
            val id = xrayActiveServerId
            if (id.isEmpty()) return null
            return xraySavedServers.firstOrNull { it.id == id }
        }

    /** Adds (or replaces, by id) a manually-added server and makes it the active connection. */
    fun addXrayServer(server: XrayServer) {
        val list = xraySavedServers.toMutableList()
        list.removeAll { it.id == server.id }
        list.add(0, server)
        xraySavedServers = list
        xrayActiveServerId = server.id
        connectionMode = ConnectionMode.XRAY
    }

    /**
     * Merges servers expanded from a subscription refresh: replaces every
     * previously-saved server tagged with [subscriptionId], keeping manually
     * added servers and other subscriptions untouched. The active selection
     * is only reassigned when the previously active server belonged to this
     * subscription and disappeared from the new set — adding a subscription
     * must never steal the selection from the user's current connection.
     */
    fun replaceSubscriptionServers(subscriptionId: String, servers: List<XrayServer>) {
        val keepActive = xrayActiveServerId
        val activeBelongedToThisSub =
            keepActive.isNotEmpty() &&
                xraySavedServers.any { it.id == keepActive && it.subscriptionId == subscriptionId }
        val list = xraySavedServers.filter { it.subscriptionId != subscriptionId }.toMutableList()
        list.addAll(servers)
        xraySavedServers = list
        if (activeBelongedToThisSub && list.none { it.id == keepActive }) {
            xrayActiveServerId = list.firstOrNull()?.id ?: ""
        }
    }

    fun removeXrayServer(id: String) {
        val list = xraySavedServers.filter { it.id != id }
        xraySavedServers = list
        if (xrayActiveServerId == id) {
            xrayActiveServerId = list.firstOrNull()?.id ?: ""
        }
    }

    fun renameXrayServer(id: String, newName: String) {
        xraySavedServers = xraySavedServers.map { if (it.id == id) it.copy(remark = newName) else it }
    }

    fun addOrUpdateXraySubscription(subscription: XraySubscription) {
        val list = xraySubscriptions.toMutableList()
        val index = list.indexOfFirst { it.id == subscription.id }
        if (index != -1) list[index] = subscription else list.add(0, subscription)
        xraySubscriptions = list
    }

    /** Removes a subscription and every server it expanded to. */
    fun removeXraySubscription(id: String) {
        xraySubscriptions = xraySubscriptions.filter { it.id != id }
        val remainingServers = xraySavedServers.filter { it.subscriptionId != id }
        val removedActive = xraySavedServers.any { it.id == xrayActiveServerId && it.subscriptionId == id }
        xraySavedServers = remainingServers
        if (removedActive) {
            xrayActiveServerId = remainingServers.firstOrNull()?.id ?: ""
        }
    }

    // ---- Cors.Connect service state -------------------------------------

    /**
     * Base URL of the Cors.Connect instance API. Overridable at runtime from
     * Settings; falls back to the build-time default (`CORS_BASE_URL`). Note the
     * Telegram App Link callback host is *not* derived from this — it is fixed
     * by the manifest intent-filter (see [cc.cors.connect.cors.TelegramAuth]).
     */
    var corsBaseUrl: String
        get() = prefs.getString(PrefsKeys.CORS_BASE_URL, BuildConfig.CORS_BASE_URL)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.CORS_BASE_URL
        set(value) = prefs.edit { putString(PrefsKeys.CORS_BASE_URL, value.trim().trimEnd('/')) }

    /** Telegram WebApp initData for the claim/login flow (replay window ~24h). */
    var corsTgInitData: String
        get() = prefs.getString(PrefsKeys.CORS_TG_INIT_DATA, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.CORS_TG_INIT_DATA, value) }

    /** Active temp-instance id (before/after claim), 0 if none. */
    var corsInstanceId: Int
        get() = prefs.getInt(PrefsKeys.CORS_INSTANCE_ID, 0)
        set(value) = prefs.edit { putInt(PrefsKeys.CORS_INSTANCE_ID, value) }

    /** One-time claim token returned when the temp instance is created. */
    var corsClaimToken: String
        get() = prefs.getString(PrefsKeys.CORS_CLAIM_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.CORS_CLAIM_TOKEN, value) }

    /** Bearer session token returned by claim/login (persisted across sessions). */
    var corsSessionToken: String
        get() = prefs.getString(PrefsKeys.CORS_SESSION_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.CORS_SESSION_TOKEN, value) }

    /**
     * The Remnawave subscription link used for sign-in (link-auth). Stored so
     * the session can be silently re-established after the session token
     * expires, without asking the user to paste the link again.
     */
    var corsSubscriptionLink: String
        get() = prefs.getString(PrefsKeys.CORS_SUBSCRIPTION_LINK, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.CORS_SUBSCRIPTION_LINK, value.trim()) }

    /** Telegram username resolved by the server during claim/login. */
    var corsUsername: String
        get() = prefs.getString(PrefsKeys.CORS_USERNAME, "") ?: ""
        set(value) = prefs.edit { putString(PrefsKeys.CORS_USERNAME, value) }

    val corsSignedIn: Boolean get() = corsUsername.isNotEmpty()

    /**
     * Stable per-install device identifier sent as the `x-hwid` header on
     * subscription requests. Remnawave (and other panels) only record a device
     * in hwid-inspector / per-device stats when this header is present, so it
     * must stay constant for the life of the install. Generated lazily on first
     * read and persisted; cleared only on full app data wipe.
     */
    val deviceHwid: String
        get() {
            prefs.getString(PrefsKeys.DEVICE_HWID, null)?.let { return it }
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit { putString(PrefsKeys.DEVICE_HWID, generated) }
            return generated
        }

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
            return try { ThemeMode.valueOf(name) } catch (_: IllegalArgumentException) { ThemeMode.SYSTEM }
        }
        set(value) = prefs.edit { putString(PrefsKeys.THEME_MODE, value.name) }

    /** Epoch ms of the last automatic update check (0 = never). */
    var lastUpdateCheck: Long
        get() = prefs.getLong(PrefsKeys.LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit { putLong(PrefsKeys.LAST_UPDATE_CHECK, value) }

    /** Whether the battery-optimization reminder was already shown once. */
    var batteryReminderShown: Boolean
        get() = prefs.getBoolean(PrefsKeys.BATTERY_REMINDER_SHOWN, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.BATTERY_REMINDER_SHOWN, value) }

    val activeDestination: CallConfig?
        get() {
            val id = activeDestinationId
            if (id.isEmpty()) return null
            return savedDestinations.firstOrNull { it.id == id }
        }

    val activeTunnelMode: TunnelMode
        get() = activeDestination?.tunnelMode ?: tunnelMode

    val activeVp8Fps: Int
        get() = activeDestination?.vp8Fps ?: vp8Fps

    val activeVp8Batch: Int
        get() = activeDestination?.vp8Batch ?: vp8Batch

    val activeDualTrack: Boolean
        get() = activeDestination?.dualTrack ?: dualTrack

    val activeReliable: Boolean
        get() = activeDestination?.reliable ?: reliable

    fun updateDestination(config: CallConfig) {
        val list = savedDestinations.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index != -1) {
            list[index] = config
            savedDestinations = list
        }
    }

    fun addDestination(config: CallConfig) {
        val list = savedDestinations.toMutableList()
        list.removeAll { it.id == config.id }
        list.add(0, config)
        savedDestinations = list
        activeDestinationId = config.id
        connectionMode = ConnectionMode.INSTANCE
    }

    fun removeDestination(id: String) {
        val list = savedDestinations.filter { it.id != id }
        savedDestinations = list
        if (activeDestinationId == id) {
            activeDestinationId = list.firstOrNull()?.id ?: ""
        }
    }

    fun renameDestination(id: String, newName: String) {
        val list = savedDestinations.map { if (it.id == id) it.copy(name = newName) else it }
        savedDestinations = list
    }

    fun resetAllSettings() {
        val keepDestinations = prefs.getString(PrefsKeys.SAVED_DESTINATIONS, null)
        val keepActiveId = prefs.getString(PrefsKeys.ACTIVE_DESTINATION_ID, null)
        val keepXrayServers = prefs.getString(PrefsKeys.XRAY_SAVED_SERVERS, null)
        val keepXrayActiveId = prefs.getString(PrefsKeys.XRAY_ACTIVE_SERVER_ID, null)
        val keepXraySubscriptions = prefs.getString(PrefsKeys.XRAY_SUBSCRIPTIONS, null)
        prefs.edit {
            clear()
            if (keepDestinations != null) putString(PrefsKeys.SAVED_DESTINATIONS, keepDestinations)
            if (keepActiveId != null) putString(PrefsKeys.ACTIVE_DESTINATION_ID, keepActiveId)
            if (keepXrayServers != null) putString(PrefsKeys.XRAY_SAVED_SERVERS, keepXrayServers)
            if (keepXrayActiveId != null) putString(PrefsKeys.XRAY_ACTIVE_SERVER_ID, keepXrayActiveId)
            if (keepXraySubscriptions != null) putString(PrefsKeys.XRAY_SUBSCRIPTIONS, keepXraySubscriptions)
        }
    }

    /** Clears every imported Xray server and subscription (keeps other settings). */
    fun forgetAllXrayServers() {
        xraySavedServers = emptyList()
        xraySubscriptions = emptyList()
        xrayActiveServerId = ""
    }

    /** Clears the stored Cors.Connect instance + session (keeps Telegram initData). */
    fun forgetCorsInstance() {
        prefs.edit {
            remove(PrefsKeys.CORS_INSTANCE_ID)
            remove(PrefsKeys.CORS_CLAIM_TOKEN)
            remove(PrefsKeys.CORS_SESSION_TOKEN)
            remove(PrefsKeys.CORS_USERNAME)
        }
    }

    /** Full sign-out: clears session, username, subscription link and initData. */
    fun corsSignOut() {
        prefs.edit {
            remove(PrefsKeys.CORS_SESSION_TOKEN)
            remove(PrefsKeys.CORS_USERNAME)
            remove(PrefsKeys.CORS_SUBSCRIPTION_LINK)
            remove(PrefsKeys.CORS_TG_INIT_DATA)
            remove(PrefsKeys.CORS_INSTANCE_ID)
            remove(PrefsKeys.CORS_CLAIM_TOKEN)
        }
    }
}
