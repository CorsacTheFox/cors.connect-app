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
class SubscriptionScreenFragment : Fragment(R.layout.fragment_subscription_screen) {

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
        val history: List<JSONObject>,
        val devices: JSONObject?,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.subRefresh).apply {
            setColorSchemeColors(requireContext().getColor(R.color.accent_emerald))
            setProgressBackgroundColorSchemeColor(requireContext().getColor(R.color.panel_2))
            setOnRefreshListener { reload() }
        }
        view.findViewById<View>(R.id.subLoginTelegram).setOnClickListener { startTelegramLogin() }
        view.findViewById<View>(R.id.subOpenBot).setOnClickListener { openUrl(BOT_URL) }
        view.findViewById<View>(R.id.subOpenBotSignedIn).setOnClickListener { openUrl(BOT_URL) }
        view.findViewById<View>(R.id.subOpenCabinet).setOnClickListener { openUrl(CabinetApi.CABINET_URL) }
        view.findViewById<View>(R.id.subLinks).setOnClickListener {
            (activity as? MainActivityHost)?.pushSubPage(XraySubscriptionsScreenFragment())
        }
        view.findViewById<View>(R.id.subTopUp).setOnClickListener { askTopUp() }
        view.findViewById<View>(R.id.subLogout).setOnClickListener {
            ConfirmActionSheet.show(parentFragmentManager, getString(R.string.sub_logout_q), getString(R.string.sub_logout_sub),
                getString(R.string.sub_logout), getString(android.R.string.cancel), destructive = true) {
                thread(isDaemon = true) {
                    CabinetApi.signOut()
                    main.post { state = null; reload() }
                }
            }
        }
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
            view?.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.subRefresh)?.isRefreshing = false
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
                val history = runCatching {
                    val a = CabinetApi.deposits()
                    (0 until a.length()).map { a.getJSONObject(it) }
                }.getOrDefault(emptyList())
                val devices = if (subscription != null) runCatching { CabinetApi.devices() }.getOrNull() else null
                State(CabinetApi.me(), subscription, CabinetApi.balanceKopeks(), renewals, tariffs, history, devices)
            }
            main.post {
                loading = false
                view?.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.subRefresh)?.isRefreshing = false
                result.onSuccess { state = it }
                result.onFailure { error = it.message }
                render()
            }
        }
    }

    // ---- UI ------------------------------------------------------------

    private fun render() {
        val v = view ?: return
        val signedIn = CabinetApi.signedIn
        val s = state
        v.findViewById<View>(R.id.subSignedOut).visibility = if (signedIn) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.subSignedIn).visibility = if (signedIn && s != null) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.subAccountRow).visibility = if (signedIn) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.subLinksCount).text = Prefs.xraySubscriptions.size.toString()

        val header = v.findViewById<TextView>(R.id.subHeader)
        val statusLine = v.findViewById<TextView>(R.id.subStatusLine)
        val title = v.findViewById<TextView>(R.id.subTitle)
        val detail = v.findViewById<TextView>(R.id.subDetail)
        detail.visibility = View.GONE
        statusLine.setTextColor(requireContext().getColor(R.color.accent_emerald))

        if (!signedIn) {
            header.text = getString(R.string.nav_servers)
            statusLine.text = getString(R.string.sub_signed_out_status)
            title.text = getString(R.string.sub_signed_out_title)
            detail.text = getString(R.string.sub_signed_out_detail)
            detail.visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.subLoginTelegramLabel).text =
                getString(if (pollToken != null) R.string.sub_login_waiting else R.string.sub_login_telegram)
            renderOtherLogins(v.findViewById(R.id.subLoginOther))
            return
        }
        if (s == null) {
            header.text = ""
            statusLine.text = if (loading) getString(R.string.sub_loading) else (error ?: getString(R.string.sub_error_generic))
            title.text = if (loading) "…" else getString(R.string.sub_retry)
            return
        }
        header.text = s.user.optString("username").let { if (it.isNotBlank()) "@$it" else "" }
            .ifBlank { s.user.optString("email") }.ifBlank { s.user.optString("first_name") }
        renderStatus(v, s)
        renderBuy(v, s)
        renderHistory(v.findViewById(R.id.subHistory), s.history)
        val hasDevices = s.subscription != null
        v.findViewById<View>(R.id.subDevicesLabel).visibility = if (hasDevices) View.VISIBLE else View.GONE
        v.findViewById<LinearLayout>(R.id.subDevicesList).apply {
            visibility = if (hasDevices) View.VISIBLE else View.GONE
            renderDevices(this, s.devices)
        }
    }

    private fun renderDevices(box: LinearLayout, data: JSONObject?) {
        box.removeAllViews()
        val ctx = requireContext()
        val arr = data?.optJSONArray("devices")
        if (arr == null || arr.length() == 0) {
            box.addView(TextView(ctx).apply {
                text = getString(R.string.sub_devices_empty)
                setTextColor(ctx.getColor(R.color.ink_3))
                textSize = 12f
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val outFmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val hwid = d.optString("hwid")
            val name = d.optString("local_name").takeIf { it.isNotBlank() && it != "null" }
                ?: d.optString("device_model").ifBlank { hwid.take(8) }
            val isThis = hwid == Prefs.deviceHwid
            if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.hair)) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                setOnClickListener { deviceActions(hwid, name) }
            }
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(ctx).apply {
                text = name
                setTextColor(ctx.getColor(R.color.ink))
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            left.addView(TextView(ctx).apply {
                val seen = runCatching { outFmt.format(inFmt.parse(d.getString("created_at").take(19))!!) }.getOrDefault("")
                text = listOf(d.optString("platform"), seen).filter { it.isNotBlank() }.joinToString(" · ")
                typeface = Fonts.mono(context)
                setTextColor(ctx.getColor(R.color.ink_3))
                textSize = 10f
            })
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(ctx).apply {
                text = if (isThis) getString(R.string.sub_device_this) else "⋯"
                typeface = Fonts.mono(context)
                setTextColor(ctx.getColor(if (isThis) R.color.accent_emerald else R.color.ink_3))
                textSize = if (isThis) 10f else 14f
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(row)
        }
    }

    private fun deviceActions(hwid: String, name: String) {
        val opts = listOf(
            ChoiceActionSheet.Option("rename", getString(R.string.sub_device_rename)),
            ChoiceActionSheet.Option("delete", getString(R.string.sub_device_delete), getString(R.string.sub_device_delete_sub)),
        )
        ChoiceActionSheet.show(parentFragmentManager, name, null, opts, null) { o ->
            when (o.id) {
                "rename" -> InputActionSheet.show(parentFragmentManager, getString(R.string.sub_device_rename), null,
                    getString(R.string.sub_device_name), name) { newName ->
                    runIo({ CabinetApi.renameDevice(hwid, newName.trim()) }) { reload() }
                }
                "delete" -> ConfirmActionSheet.show(parentFragmentManager, getString(R.string.sub_device_delete_q, name),
                    getString(R.string.sub_device_delete_sub), getString(R.string.sub_device_delete),
                    getString(android.R.string.cancel), destructive = true) {
                    runIo({ CabinetApi.deleteDevice(hwid) }) { toast(getString(R.string.sub_done)); reload() }
                }
            }
        }
    }

    private fun renderOtherLogins(box: LinearLayout) {
        box.removeAllViews()
        val items = listOf(getString(R.string.sub_login_email_short) to { showEmailLogin() }) +
            oauthProviders.map { p -> p.displayName to { startOAuth(p.name) } }
        items.forEachIndexed { i, (label, action) ->
            box.addView(chip(label, i < items.lastIndex) { action() })
        }
    }

    private fun renderStatus(v: View, s: State) {
        val statusLine = v.findViewById<TextView>(R.id.subStatusLine)
        val title = v.findViewById<TextView>(R.id.subTitle)
        val sub = s.subscription
        if (sub == null) {
            statusLine.text = getString(R.string.sub_none)
            statusLine.setTextColor(requireContext().getColor(R.color.ink_3))
            title.text = getString(R.string.sub_choose_tariff)
            v.findViewById<TextView>(R.id.subTraffic).text = "—"
            v.findViewById<TextView>(R.id.subDevices).text = "—"
        } else {
            val end = runCatching {
                val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(sub.getString("end_date").take(19))
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(d!!)
            }.getOrDefault("—")
            val active = sub.optBoolean("is_active")
            val kind = when {
                active && sub.optBoolean("is_trial") -> getString(R.string.sub_status_trial)
                active -> getString(R.string.sub_status_active)
                else -> getString(R.string.sub_status_expired)
            }
            statusLine.text = "$kind · ${getString(R.string.sub_until, end)}"
            if (!active) statusLine.setTextColor(requireContext().getColor(R.color.error_red))
            val days = sub.optInt("days_left")
            title.text = if (active) resources.getQuantityString(R.plurals.sub_days_left, days, days)
            else getString(R.string.sub_renew_title)
            val limit = sub.optInt("traffic_limit_gb")
            val used = sub.optDouble("traffic_used_gb", 0.0)
            v.findViewById<TextView>(R.id.subTraffic).text =
                if (limit > 0) String.format(Locale.getDefault(), "%.1f/%d GB", used, limit)
                else String.format(Locale.getDefault(), "%.1f GB", used)
            v.findViewById<android.widget.ProgressBar>(R.id.subTrafficBar).apply {
                visibility = if (limit > 0) View.VISIBLE else View.GONE
                progress = sub.optDouble("traffic_used_percent", 0.0).toInt()
            }
            val count = s.devices?.optInt("total", -1) ?: -1
            v.findViewById<TextView>(R.id.subDevices).text =
                if (count >= 0) "$count/${sub.optInt("device_limit")}" else sub.optInt("device_limit").toString()
        }
        v.findViewById<TextView>(R.id.subBalance).text = rub(s.balanceKopeks)
    }

    private class Offer(val title: String, val price: String, val badge: String?, val highlighted: Boolean, val onClick: () -> Unit)

    private fun renderBuy(v: View, s: State) {
        val label = v.findViewById<TextView>(R.id.subBuyLabel)
        val grid = v.findViewById<LinearLayout>(R.id.subBuyGrid)
        grid.removeAllViews()
        val offers = mutableListOf<Offer>()
        if (s.renewals.isNotEmpty()) {
            label.setText(R.string.sub_section_renew)
            val subId = s.subscription?.optInt("id")
            s.renewals.forEach { o ->
                val days = o.getInt("period_days")
                val price = rub(o.optLong("price_kopeks"))
                val disc = o.optInt("discount_percent")
                offers += Offer(periodLabel(days), price, if (disc > 0) "−$disc%" else null, o.optBoolean("is_highlighted")) {
                    confirmPay(getString(R.string.sub_confirm_renew, periodLabel(days), price), o.optLong("price_kopeks"), s) {
                        CabinetApi.renew(days, subId)
                    }
                }
            }
        } else {
            label.setText(R.string.sub_section_buy)
            s.tariffs.filter { !it.optBoolean("is_daily") }.forEach { t ->
                val periods = t.optJSONArray("periods") ?: return@forEach
                for (i in 0 until periods.length()) {
                    val p = periods.getJSONObject(i)
                    val period = p.optString("label").ifBlank { periodLabel(p.getInt("days")) }
                    val price = p.optString("price_label").ifBlank { rub(p.optLong("price_kopeks")) }
                    offers += Offer(period, price, t.optString("name").takeIf { s.tariffs.size > 1 }, p.optBoolean("is_highlighted")) {
                        confirmPay(getString(R.string.sub_confirm_buy, t.optString("name"), period, price), p.optLong("price_kopeks"), s) {
                            CabinetApi.purchaseTariff(t.getInt("id"), p.getInt("days"))
                        }
                    }
                }
            }
        }
        if (offers.isEmpty()) {
            grid.addView(chip(getString(R.string.sub_buy_in_bot), false) { openUrl(BOT_URL) })
            return
        }
        offers.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, o -> row.addView(offerCard(o, gapEnd = i == 0)) }
            if (pair.size == 1) row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(4) })
            grid.addView(row)
        }
    }

    private fun offerCard(o: Offer, gapEnd: Boolean): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (o.highlighted) R.drawable.bg_destination_card_active else R.drawable.bg_stats_card_soft)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            foreground = ctx.getDrawable(android.R.drawable.list_selector_background)?.also { it.alpha = 0 }
            setOnClickListener { o.onClick() }
        }
        card.addView(TextView(ctx).apply {
            text = o.title
            setTextColor(ctx.getColor(R.color.ink))
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val priceRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        priceRow.addView(TextView(ctx).apply {
            text = o.price
            typeface = Fonts.mono(context)
            setTextColor(ctx.getColor(R.color.accent_emerald))
            textSize = 15f
        })
        if (o.badge != null) priceRow.addView(TextView(ctx).apply {
            text = o.badge
            typeface = Fonts.mono(context)
            setTextColor(ctx.getColor(R.color.ok_green))
            textSize = 10f
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(priceRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                bottomMargin = dp(8)
                if (gapEnd) marginEnd = dp(4) else marginStart = dp(4)
            }
        }
    }

    private fun renderHistory(box: LinearLayout, items: List<JSONObject>) {
        box.removeAllViews()
        val ctx = requireContext()
        if (items.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = getString(R.string.sub_history_empty)
                setTextColor(ctx.getColor(R.color.ink_3))
                textSize = 12f
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val outFmt = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
        items.forEachIndexed { i, t ->
            if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.hair)) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(ctx).apply {
                text = t.optString("description").ifBlank { t.optString("payment_method").ifBlank { getString(R.string.sub_topup) } }
                setTextColor(ctx.getColor(R.color.ink))
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            left.addView(TextView(ctx).apply {
                val date = runCatching { outFmt.format(inFmt.parse(t.getString("created_at").take(19))!!) }.getOrDefault("")
                text = if (t.optBoolean("is_completed", true)) date else "$date · ${getString(R.string.sub_history_pending)}"
                typeface = Fonts.mono(context)
                setTextColor(ctx.getColor(R.color.ink_3))
                textSize = 10f
            })
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(ctx).apply {
                text = "+" + rub(t.optLong("amount_kopeks"))
                typeface = Fonts.mono(context)
                setTextColor(ctx.getColor(if (t.optBoolean("is_completed", true)) R.color.ok_green else R.color.ink_3))
                textSize = 13f
                setPadding(dp(8), 0, 0, 0)
            })
            box.addView(row)
        }
    }

    private fun chip(label: String, gapEnd: Boolean, onClick: () -> Unit): TextView =
        TextView(requireContext(), null, 0, R.style.QuickChip).apply {
            text = label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundResource(R.drawable.bg_quick_chip)
            gravity = android.view.Gravity.CENTER
            setTextColor(requireContext().getColor(R.color.ink_2))
            textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { if (gapEnd) marginEnd = dp(8) }
        }

    private fun askTopUp() {
        InputActionSheet.show(parentFragmentManager, getString(R.string.sub_topup), null,
            getString(R.string.sub_topup_amount), "300") { value ->
            val rubles = value.trim().replace(',', '.').toDoubleOrNull()
            if (rubles == null || rubles < 10) toast(getString(R.string.sub_topup_min))
            else startTopUp((rubles * 100).toLong())
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- flows ---------------------------------------------------------

    private fun startTelegramLogin() {
        if (pollToken != null) return
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

    private companion object {
        const val BOT_URL = "https://t.me/corsxray2bot"
    }
}
