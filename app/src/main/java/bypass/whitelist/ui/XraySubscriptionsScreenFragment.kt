package bypass.whitelist.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isNotEmpty
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.util.Prefs
import bypass.whitelist.xray.XrayServer
import bypass.whitelist.xray.XraySubscription
import bypass.whitelist.xray.XraySubscriptionException
import bypass.whitelist.xray.XraySubscriptionManager
import kotlin.concurrent.thread

/**
 * Manage screen for imported Xray subscriptions and manually-added servers.
 * Selecting the active server for the Main screen still happens on the Main
 * screen itself — this screen is for import/refresh/remove bookkeeping.
 */
class XraySubscriptionsScreenFragment : Fragment(), XrayServersListener {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_xray_subscriptions, container, false)

    /** Root-tab mode (bottom-nav "Серверы"): no back affordance, no pop. */
    private val isRootTab: Boolean get() = arguments?.getBoolean(ARG_ROOT) == true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        if (isRootTab) {
            backButton.visibility = View.GONE
        } else {
            backButton.setOnClickListener { popSelf() }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    popSelf()
                }
            })
        }
        view.findViewById<ImageButton>(R.id.addButton).setOnClickListener {
            AddXraySubscriptionSheet.show(childFragmentManager)
        }

        rebuild()
    }

    override fun onXrayServersChanged() {
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // The list may have changed while this tab was off-screen (a
        // subscription added from the Main screen, say).
        rebuild()
    }

    private fun popSelf() {
        (activity as? MainActivityHost)?.popSubPage()
    }

    /** Subscription-group ids the user has collapsed. Empty = all expanded. */
    private val collapsedGroups = mutableSetOf<String>()

    private fun rebuild() {
        val content = view?.findViewById<LinearLayout>(R.id.xraySubsContent) ?: return
        content.removeAllViews()

        val subscriptions = Prefs.xraySubscriptions
        val allServers = Prefs.xraySavedServers
        val manualServers = allServers.filter { it.subscriptionId == null }

        if (subscriptions.isEmpty() && allServers.isEmpty()) {
            content.addView(buildEmptyState())
            return
        }

        // One hierarchical list: each subscription is an expandable group with
        // its servers nested underneath; manually-added servers form their own
        // group. Groups start expanded.
        subscriptions.forEach { sub ->
            val servers = allServers.filter { it.subscriptionId == sub.id }
            content.addView(newGroup().also { section ->
                val card = section.findViewById<LinearLayout>(R.id.sectionCard)
                val expanded = !collapsedGroups.contains(sub.id)
                addGroupHeader(
                    card = card,
                    title = sub.name,
                    subtitle = subscriptionSubtitle(sub, servers.size),
                    expanded = expanded,
                    onToggle = {
                        if (expanded) collapsedGroups.add(sub.id) else collapsedGroups.remove(sub.id)
                        rebuild()
                    },
                    onMenu = { showSubscriptionMenu(sub) },
                )
                if (expanded) {
                    if (servers.isEmpty()) {
                        addGroupPlaceholder(card, getString(R.string.xray_subs_row_sub_never))
                    } else {
                        servers.forEach { server -> addServerRow(card, server, nested = true) }
                    }
                }
            })
        }

        if (manualServers.isNotEmpty()) {
            val key = MANUAL_GROUP
            content.addView(newGroup().also { section ->
                val card = section.findViewById<LinearLayout>(R.id.sectionCard)
                val expanded = !collapsedGroups.contains(key)
                addGroupHeader(
                    card = card,
                    title = getString(R.string.xray_subs_section_manual),
                    subtitle = resources.getQuantityString(
                        R.plurals.xray_toast_subscription_imported, manualServers.size, manualServers.size,
                    ),
                    expanded = expanded,
                    onToggle = {
                        if (expanded) collapsedGroups.add(key) else collapsedGroups.remove(key)
                        rebuild()
                    },
                    onMenu = null,
                )
                if (expanded) manualServers.forEach { server -> addServerRow(card, server, nested = true) }
            })
        }
    }

    private fun subscriptionSubtitle(subscription: XraySubscription, serverCount: Int): String =
        if (subscription.lastUpdatedMs > 0L) {
            getString(
                R.string.xray_subs_row_sub,
                serverCount,
                DateUtils.getRelativeTimeSpanString(subscription.lastUpdatedMs),
            )
        } else {
            getString(R.string.xray_subs_row_sub_never)
        }

    private fun addGroupHeader(
        card: LinearLayout,
        title: String,
        subtitle: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        onMenu: (() -> Unit)?,
    ) {
        val row = layoutInflater.inflate(R.layout.item_settings_row, card, false)
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_setting_tunnel)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSub).apply { text = subtitle; visibility = View.VISIBLE }
        row.findViewById<ImageView>(R.id.rowChev).apply {
            visibility = View.VISIBLE
            rotation = if (expanded) 90f else 0f
        }
        row.setOnClickListener { onToggle() }
        if (onMenu != null) row.setOnLongClickListener { onMenu(); true }
        if (card.isNotEmpty()) addDivider(card)
        card.addView(row)
    }

    private fun addGroupPlaceholder(card: LinearLayout, text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextColor(requireContext().getColor(R.color.ink_3))
            textSize = 12f
            val p = (13 * resources.displayMetrics.density).toInt()
            setPadding(p + (30 * resources.displayMetrics.density).toInt(), p, p, p)
        }
        if (card.isNotEmpty()) addDivider(card)
        card.addView(tv)
    }

    private fun buildEmptyState(): View {
        val text = TextView(requireContext()).apply {
            text = getString(R.string.empty_servers_sub)
            setTextColor(requireContext().getColor(R.color.ink_3))
            textSize = 13f
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        return text
    }

    /** A card with no standalone section label — the group header carries the name. */
    private fun newGroup(): View {
        val parent = view as ViewGroup?
        val v = layoutInflater.inflate(R.layout.item_settings_section, parent, false)
        v.findViewById<TextView>(R.id.sectionLabel).visibility = View.GONE
        v.findViewById<View>(R.id.sectionCard).clipToOutline = true
        return v
    }

    private fun addServerRow(card: LinearLayout, server: XrayServer, nested: Boolean = false) {
        val row = layoutInflater.inflate(R.layout.item_settings_row, card, false)
        val active = server.id == Prefs.xrayActiveServerId
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(if (active) R.drawable.ic_check else R.drawable.ic_setting_tunnel)
            imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(if (active) R.color.accent_emerald else R.color.ink_2)
            )
        }
        row.findViewById<TextView>(R.id.rowTitle).apply {
            text = server.remark
            setTextColor(requireContext().getColor(if (active) R.color.accent_emerald else R.color.ink))
        }
        row.findViewById<TextView>(R.id.rowSub).apply { text = server.summary; visibility = View.VISIBLE }
        row.findViewById<ImageView>(R.id.rowChev).visibility = View.GONE
        if (nested) {
            row.setPadding(
                row.paddingLeft + (16 * resources.displayMetrics.density).toInt(),
                row.paddingTop, row.paddingRight, row.paddingBottom,
            )
        }
        row.setOnClickListener {
            Prefs.xrayActiveServerId = server.id
            Prefs.connectionMode = bypass.whitelist.tunnel.ConnectionMode.XRAY
            notifyChanged()
        }
        row.setOnLongClickListener { showServerMenu(server); true }
        if (card.isNotEmpty()) addDivider(card)
        card.addView(row)
    }

    private fun addDivider(card: LinearLayout) {
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (14 * resources.displayMetrics.density).toInt()
                marginEnd = (14 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(requireContext().getColor(R.color.hair))
        }
        card.addView(divider)
    }

    private fun showSubscriptionMenu(subscription: XraySubscription) {
        MenuActionSheet.show(
            manager = childFragmentManager,
            title = subscription.name,
            subtitle = subscription.url,
            items = listOf(
                MenuActionSheet.MenuItem("refresh", getString(R.string.xray_subs_menu_refresh), R.drawable.ic_setting_reconnect),
                MenuActionSheet.MenuItem("rename", getString(R.string.xray_server_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("remove", getString(R.string.xray_subs_menu_remove), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "refresh" -> refreshSubscription(subscription)
                "rename" -> promptRenameSubscription(subscription)
                "remove" -> confirmRemoveSubscription(subscription)
            }
        }
    }

    private fun refreshSubscription(subscription: XraySubscription) {
        Toast.makeText(requireContext(), R.string.xray_subs_toast_refreshing, Toast.LENGTH_SHORT).show()
        thread(name = "xray-subscription-refresh") {
            val result = try {
                XraySubscriptionManager.fetch(subscription.url, subscription.id)
            } catch (e: XraySubscriptionException) {
                null
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (result.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), R.string.xray_sheet_error_subscription, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Prefs.addOrUpdateXraySubscription(
                    subscription.copy(lastUpdatedMs = System.currentTimeMillis(), lastServerCount = result.size),
                )
                Prefs.replaceSubscriptionServers(subscription.id, result)
                notifyChanged()
                Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(R.plurals.xray_toast_subscription_imported, result.size, result.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun promptRenameSubscription(subscription: XraySubscription) {
        InputActionSheet.show(
            manager = childFragmentManager,
            title = getString(R.string.xray_server_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = subscription.name,
        ) { newName ->
            if (newName != subscription.name) {
                Prefs.addOrUpdateXraySubscription(subscription.copy(name = newName))
                notifyChanged()
            }
        }
    }

    private fun confirmRemoveSubscription(subscription: XraySubscription) {
        ConfirmActionSheet.show(
            manager = childFragmentManager,
            title = getString(R.string.xray_server_delete_title),
            subtitle = getString(R.string.xray_server_delete_confirm, subscription.name),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeXraySubscription(subscription.id)
            notifyChanged()
        }
    }

    private fun showServerMenu(server: XrayServer) {
        MenuActionSheet.show(
            manager = childFragmentManager,
            title = server.remark,
            subtitle = server.summary,
            items = listOf(
                MenuActionSheet.MenuItem("active", getString(R.string.xray_server_menu_set_active), R.drawable.ic_check),
                MenuActionSheet.MenuItem("rename", getString(R.string.xray_server_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("delete", getString(R.string.xray_server_menu_delete), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "active" -> {
                    Prefs.xrayActiveServerId = server.id
                    notifyChanged()
                }
                "rename" -> promptRenameServer(server)
                "delete" -> confirmRemoveServer(server)
            }
        }
    }

    private fun promptRenameServer(server: XrayServer) {
        InputActionSheet.show(
            manager = childFragmentManager,
            title = getString(R.string.xray_server_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = server.remark,
        ) { newName ->
            if (newName != server.remark) {
                Prefs.renameXrayServer(server.id, newName)
                notifyChanged()
            }
        }
    }

    private fun confirmRemoveServer(server: XrayServer) {
        ConfirmActionSheet.show(
            manager = childFragmentManager,
            title = getString(R.string.xray_server_delete_title),
            subtitle = getString(R.string.xray_server_delete_confirm, server.remark),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeXrayServer(server.id)
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        rebuild()
        (activity as? XrayServersListener)?.onXrayServersChanged()
    }

    companion object {
        private const val ARG_ROOT = "root_tab"
        private const val MANUAL_GROUP = "__manual__"

        fun newRoot(): XraySubscriptionsScreenFragment = XraySubscriptionsScreenFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_ROOT, true) }
        }
    }
}
