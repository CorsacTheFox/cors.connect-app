package bypass.whitelist.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.util.Prefs

/**
 * "Advanced" sub-page: technical settings a regular user rarely touches —
 * video pipeline (VP8), autofill, Xray subscriptions, the Cors service,
 * behavior toggles and the danger zone. Reached from the root settings page.
 */
class AdvancedSettingsScreenFragment : Fragment(R.layout.fragment_advanced_settings) {

    private lateinit var ui: SettingsUi

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ui = SettingsUi(this)
        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            (activity as? MainActivityHost)?.popSubPage()
        }
        val root = view.findViewById<LinearLayout>(R.id.settingsContent)
        root.removeAllViews()
        root.addView(buildMediaSection())
        root.addView(buildAutomationSection())
        root.addView(buildXraySection())
        root.addView(buildCorsSection())
        root.addView(buildBehaviorSection())
        root.addView(buildDangerSection())
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun host(): SettingsScreenFragment.Host? = activity as? SettingsScreenFragment.Host

    private fun buildMediaSection(): View {
        val section = ui.newSection(R.string.settings_section_media)
        val card = ui.sectionCard(section)

        val vp8Sub = buildString {
            append(getString(R.string.settings_row_vp8_sub, Prefs.vp8Fps, Prefs.vp8Batch))
            if (Prefs.dualTrack) append(" / ").append(getString(R.string.settings_row_vp8_flag_dual))
            if (Prefs.reliable) append(" / ").append(getString(R.string.settings_row_vp8_flag_kcp))
        }
        ui.addRow(card, R.drawable.ic_setting_vp8, getString(R.string.settings_row_vp8), vp8Sub, null) {
            Vp8ActionSheet.show(parentFragmentManager, Prefs.vp8Fps, Prefs.vp8Batch, Prefs.dualTrack, Prefs.reliable) { fps, batch, dual, reliable ->
                Prefs.vp8Fps = fps
                Prefs.vp8Batch = batch
                Prefs.dualTrack = dual
                Prefs.reliable = reliable
                rebuild()
            }
        }
        return section
    }

    private fun buildAutomationSection(): View {
        val section = ui.newSection(R.string.settings_section_automation)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_setting_autofill, getString(R.string.settings_row_autofill), if (Prefs.autofillEnabled) Prefs.autofillName else getString(R.string.settings_row_autofill_off), null) {
            AutofillActionSheet.show(parentFragmentManager) { rebuild() }
        }
        return section
    }

    private fun buildXraySection(): View {
        val section = ui.newSection(R.string.settings_section_xray)
        val card = ui.sectionCard(section)

        val subscriptionCount = Prefs.xraySubscriptions.size
        val serverCount = Prefs.xraySavedServers.size
        val serversSub = resources.getQuantityString(R.plurals.settings_row_xray_servers_sub, serverCount, serverCount, subscriptionCount)
        ui.addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.settings_row_xray_servers), serversSub, null) {
            (activity as? MainActivityHost)?.pushSubPage(XraySubscriptionsScreenFragment())
        }
        return section
    }

    private fun buildCorsSection(): View {
        val section = ui.newSection(R.string.cors_section_service)
        val card = ui.sectionCard(section)

        val baseUrl = Prefs.corsBaseUrl
        ui.addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.cors_base_url), baseUrl, null) {
            InputActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.cors_base_url),
                subtitle = getString(R.string.cors_base_url_sub),
                fieldLabel = getString(R.string.cors_base_url_label),
                initialValue = baseUrl,
            ) { value ->
                val normalized = value.trim().trimEnd('/')
                if (normalized.isNotEmpty() && normalized != baseUrl) {
                    Prefs.corsBaseUrl = normalized
                    host()?.onCorsBaseUrlChanged()
                    rebuild()
                }
            }
        }

        // No "Account" row: sign-in is implicit. Adding an xray subscription
        // that is also a Remnawave subscription link signs the user in
        // automatically (see cc.cors.connect.cors.LinkAuth); there is no
        // separate account concept in the UI.

        ui.addRow(card, R.drawable.ic_setting_trash, getString(R.string.cors_forget_instance), getString(R.string.cors_forget_instance_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.cors_forget_instance),
                subtitle = getString(R.string.cors_forget_instance_sub),
                confirmLabel = getString(R.string.confirm_forget),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onCorsForgetInstance() }
        }
        return section
    }

    private fun buildBehaviorSection(): View {
        val section = ui.newSection(R.string.settings_section_behavior)
        val card = ui.sectionCard(section)

        ui.addSwitchRow(card, R.drawable.ic_setting_headless, getString(R.string.settings_row_headless), getString(R.string.settings_row_headless_sub), Prefs.headless) { checked ->
            Prefs.headless = checked
        }
        ui.addSwitchRow(card, R.drawable.ic_setting_reconnect, getString(R.string.settings_row_reconnect), getString(R.string.settings_row_reconnect_sub), Prefs.connectOnStart) { checked ->
            Prefs.connectOnStart = checked
        }
        ui.addSwitchRow(card, R.drawable.ic_setting_debug, getString(R.string.settings_row_debug), getString(R.string.settings_row_debug_sub), Prefs.debug) { checked ->
            Prefs.debug = checked
        }
        return section
    }

    private fun buildDangerSection(): View {
        val section = ui.newSection(R.string.settings_section_danger)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_setting_reset, getString(R.string.settings_reset_all), getString(R.string.settings_reset_all_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_reset_all),
                subtitle = getString(R.string.settings_reset_all_sub),
                confirmLabel = getString(R.string.confirm_reset),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onResetAllSettings() }
        }
        ui.addRow(card, R.drawable.ic_setting_trash, getString(R.string.settings_forget_all_destinations), getString(R.string.settings_forget_all_destinations_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_forget_all_destinations),
                subtitle = getString(R.string.settings_forget_all_destinations_sub),
                confirmLabel = getString(R.string.confirm_forget),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onForgetAllDestinations() }
        }
        ui.addRow(card, R.drawable.ic_setting_trash, getString(R.string.settings_forget_all_xray_servers), getString(R.string.settings_forget_all_xray_servers_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_forget_all_xray_servers),
                subtitle = getString(R.string.settings_forget_all_xray_servers_sub),
                confirmLabel = getString(R.string.confirm_forget),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onForgetAllXrayServers() }
        }
        return section
    }

    private fun rebuild() {
        if (!isAdded) return
        val rootView = view ?: return
        onViewCreated(rootView, null)
    }
}
