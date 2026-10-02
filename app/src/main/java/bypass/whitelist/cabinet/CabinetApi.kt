package bypass.whitelist.cabinet

import bypass.whitelist.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal client for the Telegram bot's web cabinet API
 * (miniapp.cors-fox.cc, `/api/cabinet/...`). Covers sign-in (bot deep link,
 * email, OAuth), subscription status, renewal / tariff purchase and balance
 * top-up. All calls are blocking — run them off the main thread.
 *
 * Tokens live in [Prefs.cabinetAccessToken] / [Prefs.cabinetRefreshToken];
 * a 401 triggers one silent refresh before giving up.
 */
object CabinetApi {

    const val CABINET_URL = "https://miniapp.cors-fox.cc"
    private const val BASE = "$CABINET_URL/api/cabinet"
    private const val TIMEOUT_MS = 15_000

    /** Raw response: [code] plus parsed JSON (object or array) when present. */
    class Response(val code: Int, val body: Any?) {
        val ok get() = code in 200..299
        val obj get() = body as? JSONObject
        val arr get() = body as? JSONArray

        /** FastAPI error text: `detail` as string, or `detail.message`. */
        val errorMessage: String?
            get() {
                val d = obj?.opt("detail") ?: return null
                return when (d) {
                    is String -> d
                    is JSONObject -> d.optString("message").ifBlank { null }
                    else -> null
                }
            }
        val detailObj get() = obj?.optJSONObject("detail")
    }

    class ApiException(message: String, val response: Response? = null) : Exception(message)

    val signedIn: Boolean get() = Prefs.cabinetRefreshToken.isNotEmpty()

    fun signOut() {
        val refresh = Prefs.cabinetRefreshToken
        if (refresh.isNotEmpty()) {
            runCatching { call("POST", "/auth/logout", JSONObject().put("refresh_token", refresh), auth = true) }
        }
        Prefs.cabinetAccessToken = ""
        Prefs.cabinetRefreshToken = ""
    }

    // ---- sign-in -------------------------------------------------------

    data class DeepLink(val token: String, val botUsername: String, val expiresInSec: Int)

    fun requestDeepLink(): DeepLink {
        val o = expect(call("POST", "/auth/deeplink/request", JSONObject()))
        return DeepLink(o.getString("token"), o.getString("bot_username"), o.optInt("expires_in", 300))
    }

    enum class PollState { PENDING, DONE, EXPIRED }

    fun pollDeepLink(token: String): PollState {
        val r = call("POST", "/auth/deeplink/poll", JSONObject().put("token", token))
        return when {
            r.code == 202 -> PollState.PENDING
            r.ok -> { saveTokens(r.obj!!); PollState.DONE }
            r.code == 410 -> PollState.EXPIRED
            else -> throw ApiException(r.errorMessage ?: "HTTP ${r.code}", r)
        }
    }

    fun loginEmail(email: String, password: String) {
        val r = call("POST", "/auth/email/login", JSONObject().put("email", email).put("password", password))
        saveTokens(expect(r))
    }

    data class OAuthProvider(val name: String, val displayName: String)

    fun oauthProviders(): List<OAuthProvider> {
        val arr = expect(call("GET", "/auth/oauth/providers")).optJSONArray("providers") ?: return emptyList()
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            OAuthProvider(p.getString("name"), p.optString("display_name", p.getString("name")))
        }
    }

    /** Returns the provider's authorize URL; the redirect comes back to [CABINET_URL]/auth/oauth/callback. */
    fun oauthAuthorizeUrl(provider: String): String =
        expect(call("GET", "/auth/oauth/$provider/authorize")).getString("authorize_url")

    fun oauthCallback(provider: String, code: String, state: String, deviceId: String?) {
        val body = JSONObject().put("code", code).put("state", state)
        if (!deviceId.isNullOrBlank()) body.put("device_id", deviceId)
        saveTokens(expect(call("POST", "/auth/oauth/$provider/callback", body)))
    }

    // ---- account data --------------------------------------------------

    fun me(): JSONObject = expect(call("GET", "/auth/me", auth = true))

    /** `{has_subscription, subscription: {...}}` */
    fun subscription(): JSONObject = expect(call("GET", "/subscription", auth = true))

    fun balanceKopeks(): Long = expect(call("GET", "/balance", auth = true)).optLong("balance_kopeks")

    fun renewalOptions(): JSONArray = expectArr(call("GET", "/subscription/renewal-options", auth = true))

    fun purchaseOptions(): JSONObject = expect(call("GET", "/subscription/purchase-options", auth = true))

    fun paymentMethods(): JSONArray = expectArr(call("GET", "/balance/payment-methods", auth = true))

    /** Throws [ApiException] whose response has HTTP 402 + `detail.missing_amount` when the balance is short. */
    fun renew(periodDays: Int, subscriptionId: Int?) {
        val body = JSONObject().put("period_days", periodDays)
        if (subscriptionId != null) body.put("subscription_id", subscriptionId)
        expect(call("POST", "/subscription/renew", body, auth = true))
    }

    fun purchaseTariff(tariffId: Int, periodDays: Int) {
        val body = JSONObject().put("tariff_id", tariffId).put("period_days", periodDays)
        expect(call("POST", "/subscription/purchase-tariff", body, auth = true))
    }

    /** Creates a top-up payment and returns its payment page URL. */
    fun topUp(amountKopeks: Long, method: String, option: String?): String {
        val body = JSONObject().put("amount_kopeks", amountKopeks).put("payment_method", method)
        if (!option.isNullOrBlank()) body.put("payment_option", option)
        return expect(call("POST", "/balance/topup", body, auth = true)).getString("payment_url")
    }

    // ---- plumbing ------------------------------------------------------

    private fun saveTokens(o: JSONObject) {
        Prefs.cabinetAccessToken = o.getString("access_token")
        Prefs.cabinetRefreshToken = o.getString("refresh_token")
    }

    private fun expect(r: Response): JSONObject {
        if (!r.ok) throw ApiException(r.errorMessage ?: "HTTP ${r.code}", r)
        return r.obj ?: JSONObject()
    }

    private fun expectArr(r: Response): JSONArray {
        if (!r.ok) throw ApiException(r.errorMessage ?: "HTTP ${r.code}", r)
        return r.arr ?: JSONArray()
    }

    private fun refresh(): Boolean {
        val rt = Prefs.cabinetRefreshToken
        if (rt.isEmpty()) return false
        val r = raw("POST", "/auth/refresh", JSONObject().put("refresh_token", rt), null)
        if (!r.ok) {
            // Refresh token rejected — the session is gone.
            if (r.code == 401 || r.code == 403) {
                Prefs.cabinetAccessToken = ""
                Prefs.cabinetRefreshToken = ""
            }
            return false
        }
        saveTokens(r.obj!!)
        return true
    }

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false): Response {
        if (!auth) return raw(method, path, body, null)
        if (Prefs.cabinetAccessToken.isEmpty() && !refresh()) throw ApiException("not signed in")
        val r = raw(method, path, body, Prefs.cabinetAccessToken)
        if (r.code != 401) return r
        if (!refresh()) throw ApiException("not signed in", r)
        return raw(method, path, body, Prefs.cabinetAccessToken)
    }

    private fun raw(method: String, path: String, body: JSONObject?, token: String?): Response {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
            val parsed: Any? = when {
                text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                else -> null
            }
            return Response(code, parsed)
        } finally {
            conn.disconnect()
        }
    }
}
