package bypass.whitelist.util

import androidx.annotation.StringRes
import bypass.whitelist.BuildConfig
import bypass.whitelist.R
import java.security.SecureRandom

object Net {
    const val LOCALHOST = "127.0.0.1"
}

object Ports {
    const val DEFAULT_SOCKS = 1080L
    const val DC_WS = 9000L
    const val PION_SIGNALING = 9001L
    /** Loopback SOCKS5 port Xray-core listens on for the standard Xray connection mode. */
    const val DEFAULT_XRAY_SOCKS = 1081L
}

enum class SocksAuthMode { AUTO, MANUAL }

object SocksAuth {
    private val autoUser: String
    private val autoPass: String

    init {
        val random = SecureRandom()
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        fun randomString(length: Int) = buildString {
            repeat(length) { append(chars[random.nextInt(chars.length)]) }
        }
        autoUser = randomString(16)
        autoPass = randomString(24)
    }

    val user: String
        get() = if (Prefs.socksAuthMode == SocksAuthMode.MANUAL) Prefs.socksUser else autoUser

    val pass: String
        get() = if (Prefs.socksAuthMode == SocksAuthMode.MANUAL) Prefs.socksPass else autoPass
}

enum class DnsMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.dns_mode_system),
    CUSTOM(R.string.dns_mode_custom),
}

enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_mode_system),
    LIGHT(R.string.theme_mode_light),
    DARK(R.string.theme_mode_dark),
}

object PrefsKeys {
    const val CONNECT_ON_START = "connect_on_start"
    const val ONBOARDING_DONE = "onboarding_done"
    const val TUNNEL_MODE = "tunnel_mode"
    const val SPLIT_TUNNELING_MODE = "split_tunneling_mode"
    const val SPLIT_TUNNELING_PACKAGES = "split_tunneling_packages"
    const val AUTOFILL_ENABLED = "autofill_enabled"
    const val AUTOFILL_NAME = "autofill_name"
    const val HEADLESS = "headless"
    const val SOCKS_HOST = "socks_host"
    const val SOCKS_PORT = "socks_port"
    const val SOCKS_AUTH_MODE = "socks_auth_mode"
    const val SOCKS_USER = "socks_user"
    const val SOCKS_PASS = "socks_pass"
    const val PROXY_ONLY = "proxy_only"
    const val DNS_MODE = "dns_mode"
    const val DNS_PRIMARY = "dns_primary"
    const val DNS_SECONDARY = "dns_secondary"
    const val VP8_FPS = "vp8_fps"
    const val VP8_BATCH = "vp8_batch"
    const val DUAL_TRACK = "dual_track"
    const val RELIABLE = "reliable"
    const val DEBUG = "debug"
    const val SAVED_DESTINATIONS = "saved_destinations"
    const val ACTIVE_DESTINATION_ID = "active_destination_id"
    const val THEME_MODE = "theme_mode"

    // Updater / housekeeping
    const val LAST_UPDATE_CHECK = "last_update_check"
    const val BATTERY_REMINDER_SHOWN = "battery_reminder_shown"

    // Standard Xray connections
    const val CONNECTION_MODE = "connection_mode"
    const val XRAY_SAVED_SERVERS = "xray_saved_servers"
    const val XRAY_ACTIVE_SERVER_ID = "xray_active_server_id"
    const val XRAY_SUBSCRIPTIONS = "xray_subscriptions"
    const val XRAY_SOCKS_PORT = "xray_socks_port"

    // Cors.Connect service
    const val CORS_BASE_URL = "cors_base_url"
    const val CORS_TG_INIT_DATA = "cors_tg_init_data"
    const val CORS_INSTANCE_ID = "cors_instance_id"
    const val CORS_CLAIM_TOKEN = "cors_claim_token"
    const val CORS_SESSION_TOKEN = "cors_session_token"
    const val CORS_USERNAME = "cors_username"
    const val CORS_SUBSCRIPTION_LINK = "cors_subscription_link"

    /** Stable per-install device id sent as `x-hwid` on subscription requests. */
    const val DEVICE_HWID = "device_hwid"
}

object VP8Defaults {
    const val FPS = 24
    const val BATCH = 30
}

const val BLANK_URL = "about:blank"

const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

object Vpn {
    const val ADDRESS = "10.0.0.2"
    const val PREFIX_LENGTH = 32
    const val ROUTE = "0.0.0.0"
    const val ADDRESS6 = "fd00::2"
    const val PREFIX_LENGTH6 = 128
    const val ROUTE6 = "::"
    const val MTU = 1500
    const val DNS_PRIMARY = "1.1.1.1"
    const val DNS_SECONDARY = "1.0.0.1"
    const val SESSION_NAME = "CorsConnect"
}
