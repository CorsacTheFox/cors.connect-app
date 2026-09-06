package cc.cors.connect.cors

import bypass.whitelist.util.Prefs
import cc.cors.connect.api.CorsClient
import cc.cors.connect.api.CorsException
import cc.cors.connect.api.LoginOut

/**
 * Remnawave subscription link-auth helpers.
 *
 * For most users their Remnawave subscription link IS one of the xray
 * subscription URLs already stored in this app (they paste it once for the
 * VPN servers). This object turns that overlap into automatic sign-in: no
 * separate "Sign in" step, no extra secret to manage.
 *
 * All methods are blocking (network) — call off the main thread. Safe to call
 * repeatedly: URLs the server firmly rejects (401/403/404) are remembered for
 * this process run and never retried; transient failures (network, panel
 * down) are retried on the next call.
 */
object LinkAuth {

    /** Notified on the main thread when an implicit sign-in succeeded. */
    interface Listener {
        fun onCorsSignedIn(username: String)
    }

    /** URLs the server firmly rejected this process run (not Remnawave links). */
    private val rejectedUrls: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Persists a successful sign-in (session token, username, the link itself). */
    fun store(out: LoginOut, link: String) {
        Prefs.corsSubscriptionLink = link
        Prefs.corsSessionToken = out.token
        if (out.username.isNotEmpty()) Prefs.corsUsername = out.username
    }

    /**
     * Attempts link-auth with [url]. Returns the username on success, null
     * otherwise. A successful result is stored in [Prefs] (see [store]).
     */
    fun trySignIn(url: String): String? {
        val link = url.trim()
        if (link.isEmpty() || link in rejectedUrls) return null
        return try {
            val out = CorsClient().authLink(link)
            if (out.token.isBlank()) {
                rejectedUrls += link
                null
            } else {
                store(out, link)
                out.username.ifEmpty { "?" }
            }
        } catch (e: CorsException) {
            // Firm rejections are remembered so we don't re-scan the panel on
            // every connect for links that will never work. Transient errors
            // (network=0, panel 502/5xx) stay retryable.
            when (e.code) {
                401, 403, 404 -> rejectedUrls += link
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tries every stored xray subscription URL (in order) until one signs in.
     * Returns the username, or null when none works.
     *
     * NOTE: deliberately NOT gated on [Prefs.corsSignedIn] — that flag merely
     * reflects a stored username and stays true after the session token
     * expired, which used to silently block the implicit re-sign-in (the
     * "already signed in" checks must look at corsSessionToken instead).
     */
    fun trySignInFromXraySubscriptions(): String? {
        for (sub in Prefs.xraySubscriptions) {
            val username = trySignIn(sub.url)
            if (username != null) return username
        }
        return null
    }
}
