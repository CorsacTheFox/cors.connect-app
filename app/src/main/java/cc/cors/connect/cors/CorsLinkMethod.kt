package cc.cors.connect.cors

import bypass.whitelist.R

/**
 * How [CorsInstanceController] obtains the Whitelist Bypass output_link.
 * Method 1 is the normal request against [bypass.whitelist.BuildConfig.CORS_BASE_URL]
 * (direct, or via the Yandex Function proxy / operator-DNS candidates — see
 * [cc.cors.connect.api.CorsClient]). Method 2 is the VK relay fallback (see
 * [cc.cors.connect.api.VkLinkFallback]) — a link the backend pushes into a
 * private VK dialog, reachable on whitelists that block both of Method 1's
 * endpoints. VK delivers only a shared anonymous 5-minute link (it's a single
 * broadcast channel, not per-user), so forcing [VK_ONLY] always gets that,
 * even for an otherwise-authorized account.
 */
enum class CorsLinkMethod(val labelRes: Int) {
    /** Method 1, then Method 2 only if Method 1 is completely unreachable (default). */
    AUTO(R.string.cors_link_method_auto),
    /** Method 1 only — never falls back to VK. */
    SERVER_ONLY(R.string.cors_link_method_server),
    /** Method 2 only — skips Method 1 entirely, always anonymous/temporary. */
    VK_ONLY(R.string.cors_link_method_vk),
}
