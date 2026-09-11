package bypass.whitelist.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import bypass.whitelist.R
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.ConnectionMode
import bypass.whitelist.util.Prefs
import bypass.whitelist.xray.XrayServer
import bypass.whitelist.xray.XraySubscription
import bypass.whitelist.xray.XraySubscriptionException
import bypass.whitelist.xray.XraySubscriptionManager
import bypass.whitelist.xray.isSupportedXrayShareLink
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.concurrent.thread

/**
 * Single "add" entry point that auto-detects what was pasted/scanned:
 *  - a call link (VK / Telemost / WB Stream / DION, see [CallPlatform.isCallLink])
 *    is saved as a manual [CallConfig] destination (mirrors the retired
 *    [AddDestinationSheet]);
 *  - an Xray share link (`vless://…`, `vmess://…`, `trojan://…`) is saved
 *    directly as one [XrayServer];
 *  - any other `http(s)://` link is treated as a subscription URL (fetched,
 *    base64-decoded, and expanded into many servers — see
 *    [XraySubscriptionManager]).
 */
class AddXraySubscriptionSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_add_xray_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val inputName = view.findViewById<EditText>(R.id.inputName)
        val inputLink = view.findViewById<EditText>(R.id.inputLink)
        val pasteChip = view.findViewById<LinearLayout>(R.id.pasteChip)
        val pasteChipLabel = view.findViewById<TextView>(R.id.pasteChipLabel)
        val errorText = view.findViewById<TextView>(R.id.sheetError)
        val buttonCancel = view.findViewById<Button>(R.id.buttonCancel)
        val buttonSave = view.findViewById<Button>(R.id.buttonSave)

        val prefillLink = arguments?.getString(ARG_PREFILL_LINK).orEmpty()
        if (prefillLink.isNotEmpty()) inputLink.setText(prefillLink)

        pasteChip.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            inputLink.setText(text)
            flashChip(pasteChip, pasteChipLabel)
        }

        buttonCancel.setOnClickListener { dismiss() }

        buttonSave.setOnClickListener {
            errorText.visibility = View.GONE
            val link = inputLink.text.toString().trim()
            if (link.isEmpty()) {
                inputLink.requestFocus()
                return@setOnClickListener
            }
            val name = inputName.text.toString().trim()

            if (CallPlatform.isCallLink(link)) {
                val config = CallConfig.newWith(
                    name = name.ifEmpty { CallConfig.suggestNameFor(link) },
                    url = link,
                )
                Prefs.addDestination(config)
                Prefs.connectionMode = ConnectionMode.INSTANCE
                notifyChanged()
                dismiss()
                return@setOnClickListener
            }

            if (link.isSupportedXrayShareLink()) {
                val server = XrayServer.parseShareLink(link)
                if (server == null) {
                    showError(errorText, getString(R.string.xray_sheet_error_bad_link))
                    return@setOnClickListener
                }
                val saved = if (name.isNotEmpty()) server.copy(remark = name) else server
                Prefs.addXrayServer(saved)
                notifyChanged()
                dismiss()
                return@setOnClickListener
            }

            if (!link.startsWith("http://", ignoreCase = true) && !link.startsWith("https://", ignoreCase = true)) {
                showError(errorText, getString(R.string.xray_sheet_error_unrecognized))
                return@setOnClickListener
            }

            // Reject a subscription URL that's already imported — otherwise the
            // same feed gets added twice (each add mints a fresh id) and its
            // servers are duplicated in the list.
            if (Prefs.xraySubscriptions.any { sameSubscriptionUrl(it.url, link) }) {
                showError(errorText, getString(R.string.xray_sheet_error_duplicate))
                return@setOnClickListener
            }

            setLoading(buttonSave, true)
            val subscription = XraySubscription.newWith(
                name = name.ifEmpty { XraySubscription.suggestNameFor(link) },
                url = link,
            )
            thread(name = "xray-subscription-fetch") {
                val result = try {
                    XraySubscriptionManager.fetchWithUserInfo(link, subscription.id)
                } catch (e: XraySubscriptionException) {
                    null
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    setLoading(buttonSave, false)
                    if (result == null || result.servers.isEmpty()) {
                        showError(errorText, getString(R.string.xray_sheet_error_subscription))
                        return@runOnUiThread
                    }
                    Prefs.addOrUpdateXraySubscription(
                        subscription.copy(
                            lastUpdatedMs = System.currentTimeMillis(),
                            lastServerCount = result.servers.size,
                            // Panel quota header (traffic/expiry), if the panel
                            // sent one — feeds the main-screen stats card.
                            usedBytes = result.userInfo?.usedBytes ?: 0L,
                            totalBytes = result.userInfo?.totalBytes ?: 0L,
                            expireAtSec = result.userInfo?.expireAtSec ?: 0L,
                        ),
                    )
                    Prefs.replaceSubscriptionServers(subscription.id, result.servers)
                    notifyChanged()
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(R.plurals.xray_toast_subscription_imported, result.servers.size, result.servers.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    // Already holding a session? Nothing to pick up (an
                    // expired one is silently refreshed on the next connect
                    // by CorsInstanceController.silentRelogin).
                    if (Prefs.corsSessionToken.isBlank()) {
                        // A Remnawave subscription link doubles as the
                        // Cors.Connect sign-in credential — try it silently
                        // right away so the whitelist-bypass flow works
                        // immediately (no separate sign-in step).
                        val act = activity
                        thread(name = "cors-sub-auth") {
                            val username = cc.cors.connect.cors.LinkAuth.trySignIn(link)
                            if (username != null && act != null) {
                                act.runOnUiThread {
                                    (act as? cc.cors.connect.cors.LinkAuth.Listener)?.onCorsSignedIn(username)
                                    Toast.makeText(
                                        act,
                                        act.getString(R.string.cors_toast_signed_in, username),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                    dismiss()
                }
            }
        }
    }

    /**
     * Compares two subscription URLs for "the same feed": case-insensitive,
     * ignoring a trailing slash and any surrounding whitespace. Deliberately
     * conservative — only spellings that unambiguously point at one feed count
     * as duplicates.
     */
    private fun sameSubscriptionUrl(a: String, b: String): Boolean {
        fun norm(s: String) = s.trim().trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private fun setLoading(button: Button, loading: Boolean) {
        button.isEnabled = !loading
        button.text = getString(if (loading) R.string.xray_sheet_saving else R.string.xray_sheet_save)
    }

    private fun showError(errorText: TextView, message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun notifyChanged() {
        (parentFragment as? XrayServersListener)?.onXrayServersChanged()
        (activity as? XrayServersListener)?.onXrayServersChanged()
    }

    private fun flashChip(chip: LinearLayout, label: TextView) {
        chip.setBackgroundResource(R.drawable.bg_paste_chip_flash)
        label.setTextColor(requireContext().getColor(R.color.accent_emerald))
        chip.postDelayed({
            if (isAdded) {
                chip.setBackgroundResource(R.drawable.bg_paste_chip)
                label.setTextColor(requireContext().getColor(R.color.ink_2))
            }
        }, 320)
    }

    companion object {
        const val TAG = "AddXraySubscriptionSheet"

        private const val ARG_PREFILL_LINK = "prefill_link"

        fun show(manager: FragmentManager, prefillLink: String = "") {
            val sheet = AddXraySubscriptionSheet()
            if (prefillLink.isNotEmpty()) {
                sheet.arguments = Bundle().apply { putString(ARG_PREFILL_LINK, prefillLink) }
            }
            sheet.show(manager, TAG)
        }
    }
}
