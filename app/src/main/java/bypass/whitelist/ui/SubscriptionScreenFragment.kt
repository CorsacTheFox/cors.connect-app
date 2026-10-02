package bypass.whitelist.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.cabinet.CabinetApi
import bypass.whitelist.util.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Subscription" tab. Signed out: sign-in choices (every method the bot's
 * cabinet has enabled — bot deep link, email, OAuth) plus the plain
 * subscription-link management that works without any account. Signed in:
 * status, renewal / tariff purchase, balance top-up via the cabinet API.
 */
class SubscriptionScreenFragment : Fragment(R.layout.fragment_settings_screen) {

    private lateinit var ui: SettingsUi
    private val main = Handler(Looper.getMainLooper())

    /** Last loaded account snapshot; null while loading / signed out. */
    private var state: State? = null
    private var loading = false
    private var error: String? = null
    private var oauthProviders: List<CabinetApi.OAuthProvider> = emptyList()

    private var pollToken: String? = null
    private var pollDeadline = 0L

    private class State(
        val user: JSONObject,
        val subscription: JSONObject?,
        val balanceKopeks: Long,
        val renewals: List<JSONObject>,
        val tariffs: List<JSONObject>,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ui = SettingsUi(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the bot / a payment page: re-read everything.
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        main.removeCallbacksAndMessages(null)
    }

    /** Called by MainActivity when the OAuth browser redirect lands back in the app. */
    fun completeOAuth(provider: String, code: String, oauthState: String, deviceId: String?) {
        runIo({ CabinetApi.oauthCallback(provider, code, oauthState, deviceId) }) { reload() }
    }

    // ---- data ----------------------------------------------------------

    fun reload() {
        if (!isAdded) return
        if (!CabinetApi.signedIn) {
            state = null
            if (oauthProviders.isEmpty()) {
                thread(isDaemon = true) {
                    val p = runCatching { CabinetApi.oauthProviders() }.getOrDefault(emptyList())
                    main.post { oauthProviders = p; render() }
                }
            }
            render()
            return
        }
        loading = true
        error = null
        render()
        thread(isDaemon = true) {
            val result = runCatching {
                val sub = CabinetApi.subscription()
                val subscription = if (sub.optBoolean("has_subscription")) sub.optJSONObject("subscription") else null
                val renewals = if (subscription != null) {
                    runCatching { CabinetApi.renewalOptions().let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
                        .getOrDefault(emptyList())
                } else emptyList()
                val tariffs = if (renewals.isEmpty()) {
                    runCatching {
                        val arr = CabinetApi.purchaseOptions().optJSONArray("tariffs")
                        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
                    }.getOrDefault(emptyList())
                } else emptyList()
                State(CabinetApi.me(), subscription, CabinetApi.balanceKopeks(), renewals, tariffs)
            }
            main.post {
                loading = false
                result.onSuccess { state = it }
                result.onFailure { error = it.message }
                render()
            }
        }
    }

    // ---- UI ------------------------------------------------------------

    private fun render() {
        val root = view?.findViewById<LinearLayout>(R.id.settingsContent) ?: return
        root.removeAllViews()
        val s = state
        when {
            !CabinetApi.signedIn -> root.addView(buildSignInSection())
            s == null -> root.addView(buildLoadingSection())
            else -> {
                root.addView(buildStatusSection(s))
                root.addView(buildBuySection(s))
                root.addView(buildBalanceSection(s))
            }
        }
        root.addView(buildLinksSection())
        if (CabinetApi.signedIn) root.addView(buildAccountSection())
    }

    private fun buildSignInSection(): View {
        val section = ui.newSection(R.string.sub_section_manage)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_telegram, getString(R.string.sub_login_telegram),
            if (pollToken != null) getString(R.string.sub_login_waiting) else getString(R.string.sub_login_telegram_sub), null) {
            startTelegramLogin()
        }
        ui.addRow(card, R.drawable.ic_setting_autofill, getString(R.string.sub_login_email), null, null) {
            showEmailLogin()
        }
        oauthProviders.forEach { p ->
            ui.addRow(card, R.drawable.ic_setting_advanced, getString(R.string.sub_login_oauth, p.displayName), null, null) {
                startOAuth(p.name)
            }
        }
        ui.addRow(card, R.drawable.ic_telegram, getString(R.string.sub_open_bot), getString(R.string.sub_open_bot_sub), null) {
            openUrl("https://t.me/corsxray2bot")
        }
        return section
    }

    private fun buildLoadingSection(): View {
        val section = ui.newSection(R.string.sub_section_status)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_setting_reconnect,
            if (loading) getString(R.string.sub_loading) else (error ?: getString(R.string.sub_error_generic)),
            if (loading) null else getString(R.string.sub_retry), null) { reload() }
        return section
    }

    private fun buildStatusSection(s: State): View {
        val section = ui.newSection(R.string.sub_section_status)
        val card = ui.sectionCard(section)
        val sub = s.subscription
        if (sub == null) {
            ui.addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.sub_none), null, null) {}
            return section
        }
        val end = runCatching {
            val iso = sub.getString("end_date").take(19)
            val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(d!!)
        }.getOrDefault("—")
        val title = when {
            sub.optBoolean("is_active") && sub.optBoolean("is_trial") -> getString(R.string.sub_status_trial)
            sub.optBoolean("is_active") -> getString(R.string.sub_status_active)
            else -> getString(R.string.sub_status_expired)
        }
        ui.addRow(card, R.drawable.ic_setting_tunnel, title, getString(R.string.sub_until, end),
            sub.optString("time_left_display").ifBlank { getString(R.string.sub_days, sub.optInt("days_left")) }) {}
        val limit = sub.optInt("traffic_limit_gb")
        val used = sub.optDouble("traffic_used_gb", 0.0)
        ui.addRow(card, R.drawable.ic_setting_dns, getString(R.string.sub_traffic),
            null, if (limit > 0) String.format(Locale.US, "%.1f / %d GB", used, limit) else String.format(Locale.US, "%.1f GB / ∞", used)) {}
        ui.addRow(card, R.drawable.ic_setting_split, getString(R.string.sub_devices), null, sub.optInt("device_limit").toString()) {}
        val url = sub.optString("subscription_url")
        if (url.isNotBlank() && Prefs.xraySubscriptions.none { it.url.trim() == url.trim() }) {
            ui.addRow(card, R.drawable.ic_plus, getString(R.string.sub_add_servers), getString(R.string.sub_add_servers_sub), null) {
                AddXraySubscriptionSheet.show(parentFragmentManager, url)
            }
        }
        return section
    }

    private fun buildBuySection(s: State): View {
        val section = ui.newSection(if (s.renewals.isNotEmpty()) R.string.sub_section_renew else R.string.sub_section_buy)
        val card = ui.sectionCard(section)
        if (s.renewals.isNotEmpty()) {
            val subId = s.subscription?.optInt("id")
            s.renewals.forEach { o ->
                val days = o.getInt("period_days")
                val price = rub(o.optLong("price_kopeks"))
                val sub = if (o.optInt("discount_percent") > 0) "−${o.optInt("discount_percent")}%" else null
                ui.addRow(card, R.drawable.ic_setting_reconnect, getString(R.string.sub_period, periodLabel(days)), sub, price) {
                    confirmPay(getString(R.string.sub_confirm_renew, periodLabel(days), price), o.optLong("price_kopeks"), s) {
                        CabinetApi.renew(days, subId)
                    }
                }
            }
        } else if (s.tariffs.isNotEmpty()) {
            s.tariffs.filter { !it.optBoolean("is_daily") }.forEach { t ->
                val periods = t.optJSONArray("periods") ?: return@forEach
                for (i in 0 until periods.length()) {
                    val p = periods.getJSONObject(i)
                    val label = p.optString("label").ifBlank { periodLabel(p.getInt("days")) }
                    val price = p.optString("price_label").ifBlank { rub(p.optLong("price_kopeks")) }
                    ui.addRow(card, R.drawable.ic_plus, "${t.optString("name")} · $label", null, price) {
                        confirmPay(getString(R.string.sub_confirm_buy, t.optString("name"), label, price), p.optLong("price_kopeks"), s) {
                            CabinetApi.purchaseTariff(t.getInt("id"), p.getInt("days"))
                        }
                    }
                }
            }
        } else {
            ui.addRow(card, R.drawable.ic_telegram, getString(R.string.sub_buy_in_bot), null, null) {
                openUrl("https://t.me/corsxray2bot")
            }
        }
        return section
    }

    private fun buildBalanceSection(s: State): View {
        val section = ui.newSection(R.string.sub_section_balance)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_plus, getString(R.string.sub_topup), getString(R.string.sub_balance_sub), rub(s.balanceKopeks)) {
            InputActionSheet.show(parentFragmentManager, getString(R.string.sub_topup), null,
                getString(R.string.sub_topup_amount), "300") { value ->
                val rubles = value.trim().replace(',', '.').toDoubleOrNull()
                if (rubles == null || rubles < 10) toast(getString(R.string.sub_topup_min))
                else startTopUp((rubles * 100).toLong())
            }
        }
        return section
    }

    private fun buildLinksSection(): View {
        val section = ui.newSection(R.string.sub_section_links)
        val card = ui.sectionCard(section)
        val count = Prefs.xraySubscriptions.size
        ui.addRow(card, R.drawable.ic_setting_proxy, getString(R.string.sub_links_manage), getString(R.string.sub_links_manage_sub),
            count.toString()) {
            (activity as? MainActivityHost)?.pushSubPage(XraySubscriptionsScreenFragment())
        }
        return section
    }

    private fun buildAccountSection(): View {
        val section = ui.newSection(R.string.sub_section_account)
        val card = ui.sectionCard(section)
        val u = state?.user
        val who = u?.let { it.optString("username").ifBlank { it.optString("email") }.ifBlank { it.optString("first_name") } }
        ui.addRow(card, R.drawable.ic_telegram, getString(R.string.sub_open_cabinet), who?.takeIf { it.isNotBlank() }, null) {
            openUrl(CabinetApi.CABINET_URL)
        }
        ui.addRow(card, R.drawable.ic_setting_reset, getString(R.string.sub_logout), null, null, danger = true) {
            thread(isDaemon = true) {
                CabinetApi.signOut()
                main.post { state = null; reload() }
            }
        }
        return section
    }

    // ---- flows ---------------------------------------------------------

    private fun startTelegramLogin() {
        runIo({ CabinetApi.requestDeepLink() }) { link ->
            pollToken = link.token
            pollDeadline = System.currentTimeMillis() + link.expiresInSec * 1000L
            render()
            val tg = "tg://resolve?domain=${link.botUsername}&start=webauth_${link.token}"
            if (!openUrl(tg, silent = true)) openUrl("https://t.me/${link.botUsername}?start=webauth_${link.token}")
            schedulePoll()
        }
    }

    private fun schedulePoll() {
        main.postDelayed({
            val token = pollToken ?: return@postDelayed
            if (System.currentTimeMillis() > pollDeadline) {
                pollToken = null; render(); return@postDelayed
            }
            thread(isDaemon = true) {
                val r = runCatching { CabinetApi.pollDeepLink(token) }
                main.post {
                    if (!isAdded || pollToken != token) return@post
                    when (r.getOrNull()) {
                        CabinetApi.PollState.DONE -> { pollToken = null; reload() }
                        CabinetApi.PollState.EXPIRED -> { pollToken = null; render() }
                        else -> schedulePoll()
                    }
                }
            }
        }, 2_000)
    }

    private fun showEmailLogin() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val email = EditText(ctx).apply {
            hint = getString(R.string.sub_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pass = EditText(ctx).apply {
            hint = getString(R.string.sub_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(email); box.addView(pass)
        val forgot = TextView(ctx).apply {
            text = getString(R.string.sub_email_register_forgot)
            setTextColor(ctx.getColor(R.color.accent_emerald))
            setPadding(0, pad / 2, 0, 0)
            setOnClickListener { openUrl(CabinetApi.CABINET_URL) }
        }
        box.addView(forgot)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sub_login_email)
            .setView(box)
            .setPositiveButton(R.string.sub_login_button) { _, _ ->
                val e = email.text.toString().trim()
                val p = pass.text.toString()
                if (e.isNotEmpty() && p.isNotEmpty()) runIo({ CabinetApi.loginEmail(e, p) }) { reload() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startOAuth(provider: String) {
        runIo({ CabinetApi.oauthAuthorizeUrl(provider) }) { url ->
            Prefs.cabinetOAuthProvider = provider
            openUrl(url)
        }
    }

    /**
     * Confirms, then runs [action]. If the balance is short the cabinet saves
     * the cart and answers 402 — we then top up the missing amount and the bot
     * completes the purchase automatically once the payment lands.
     */
    private fun confirmPay(text: String, priceKopeks: Long, s: State, action: () -> Unit) {
        val short = priceKopeks - s.balanceKopeks
        val sub = if (short > 0) getString(R.string.sub_confirm_short, rub(short)) else getString(R.string.sub_confirm_from_balance)
        ConfirmActionSheet.show(parentFragmentManager, text, sub, getString(R.string.sub_pay), getString(android.R.string.cancel)) {
            thread(isDaemon = true) {
                val r = runCatching { action() }
                main.post {
                    if (!isAdded) return@post
                    val e = r.exceptionOrNull() as? CabinetApi.ApiException
                    when {
                        r.isSuccess -> { toast(getString(R.string.sub_done)); reload() }
                        e?.response?.code == 402 -> {
                            val missing = e.response.detailObj?.optLong("missing_amount") ?: short
                            startTopUp(maxOf(missing, 1000L))
                        }
                        else -> toast(r.exceptionOrNull()?.message ?: getString(R.string.sub_error_generic))
                    }
                }
            }
        }
    }

    private fun startTopUp(amountKopeks: Long) {
        runIo({ CabinetApi.paymentMethods() }) { arr ->
            val methods = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optBoolean("is_available", true) }
            if (methods.isEmpty()) { toast(getString(R.string.sub_no_methods)); return@runIo }
            val options = methods.map { ChoiceActionSheet.Option(it.getString("id"), it.optString("name"), it.optString("description").ifBlank { null }) }
            ChoiceActionSheet.show(parentFragmentManager, getString(R.string.sub_pay_method), getString(R.string.sub_pay_amount, rub(amountKopeks)), options, null) { picked ->
                val m = methods.first { it.getString("id") == picked.id }
                val amount = amountKopeks.coerceAtLeast(m.optLong("min_amount_kopeks", 1000L))
                val sub = m.optJSONArray("options")
                if (sub != null && sub.length() > 0) {
                    val subOpts = (0 until sub.length()).map { sub.getJSONObject(it) }
                        .map { ChoiceActionSheet.Option(it.getString("id"), it.optString("name"), it.optString("description").ifBlank { null }) }
                    ChoiceActionSheet.show(parentFragmentManager, picked.title, null, subOpts, null) { o -> pay(amount, picked.id, o.id) }
                } else {
                    pay(amount, picked.id, null)
                }
            }
        }
    }

    private fun pay(amountKopeks: Long, method: String, option: String?) {
        runIo({ CabinetApi.topUp(amountKopeks, method, option) }) { url -> openUrl(url) }
    }

    // ---- helpers -------------------------------------------------------

    private fun <T> runIo(work: () -> T, done: (T) -> Unit) {
        thread(isDaemon = true) {
            val r = runCatching(work)
            main.post {
                if (!isAdded) return@post
                r.onSuccess(done)
                r.onFailure { toast(it.message ?: getString(R.string.sub_error_generic)) }
            }
        }
    }

    private fun openUrl(url: String, silent: Boolean = false): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        if (!silent) toast(getString(R.string.sub_error_generic))
        false
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun rub(kopeks: Long): String =
        if (kopeks % 100 == 0L) "${kopeks / 100} ₽" else String.format(Locale.US, "%.2f ₽", kopeks / 100.0)

    private fun periodLabel(days: Int): String = when {
        days % 365 == 0 -> resources.getQuantityString(R.plurals.sub_years, days / 365, days / 365)
        days % 30 == 0 -> resources.getQuantityString(R.plurals.sub_months, days / 30, days / 30)
        else -> resources.getQuantityString(R.plurals.sub_days_plural, days, days)
    }
}
