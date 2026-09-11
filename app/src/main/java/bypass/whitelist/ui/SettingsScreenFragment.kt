package bypass.whitelist.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import bypass.whitelist.BuildConfig
import bypass.whitelist.R
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.util.AppUpdater
import bypass.whitelist.util.BatteryOptimizer
import bypass.whitelist.util.Prefs

/**
 * Root settings page — only the things a user actually tweaks: connection,
 * appearance and app housekeeping (updates, battery). Everything technical
 * lives behind the "Advanced" row ([AdvancedSettingsScreenFragment]).
 */
class SettingsScreenFragment : Fragment(R.layout.fragment_settings_screen) {

    interface Host {
        fun onTunnelModeChanged(mode: TunnelMode)
        fun onForgetAllDestinations()
        fun onForgetAllXrayServers()
        fun onResetAllSettings()
        fun onCorsForgetInstance()
        fun onCorsBaseUrlChanged()
    }

    private lateinit var ui: SettingsUi

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ui = SettingsUi(this)
        val root = view.findViewById<LinearLayout>(R.id.settingsContent)
        root.removeAllViews()
        root.addView(buildConnectionSection())
        root.addView(buildAppearanceSection())
        root.addView(buildAppSection())
        root.addView(buildAdvancedSection())
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    fun refresh() {
        rebuild()
    }

    private fun host(): Host? = activity as? Host

    private fun buildConnectionSection(): View {
        val section = ui.newSection(R.string.settings_section_connection)
        val card = ui.sectionCard(section)

        ui.addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.settings_row_tunnel_mode), null, getString(Prefs.tunnelMode.labelRes)) {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_tunnel_mode),
                options = TunnelMode.entries.map { ChoiceActionSheet.Option(it.name, getString(it.labelRes)) },
                selectedId = Prefs.tunnelMode.name,
            ) { picked ->
                val newMode = TunnelMode.valueOf(picked.id)
                if (newMode != Prefs.tunnelMode) {
                    Prefs.tunnelMode = newMode
                    host()?.onTunnelModeChanged(newMode)
                    rebuild()
                }
            }
        }

        val splitSummary = if (Prefs.splitTunnelingMode == SplitTunnelingMode.NONE) {
            getString(Prefs.splitTunnelingMode.labelRes)
        } else {
            resources.getQuantityString(R.plurals.split_tunneling_summary_count, Prefs.splitTunnelingPackages.size, getString(Prefs.splitTunnelingMode.labelRes), Prefs.splitTunnelingPackages.size)
        }
        ui.addRow(card, R.drawable.ic_setting_split, getString(R.string.settings_row_split), splitSummary, null) {
            (activity as? MainActivityHost)?.pushSubPage(SplitTunnelingScreenFragment())
        }

        ui.addRow(card, R.drawable.ic_setting_proxy, getString(R.string.settings_row_proxy), getString(R.string.settings_row_proxy_sub, Prefs.socksPort), null) {
            ProxyActionSheet.show(parentFragmentManager) { rebuild() }
        }

        ui.addRow(card, R.drawable.ic_setting_dns, getString(R.string.settings_row_dns), getString(Prefs.dnsMode.labelRes), null) {
            DnsActionSheet.show(parentFragmentManager) { rebuild() }
        }

        ui.addRow(card, R.drawable.ic_setting_proxy, getString(R.string.settings_row_link_method), null, getString(Prefs.corsLinkMethod.labelRes)) {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_link_method),
                options = cc.cors.connect.cors.CorsLinkMethod.entries.map {
                    ChoiceActionSheet.Option(it.name, getString(it.labelRes))
                },
                selectedId = Prefs.corsLinkMethod.name,
            ) { picked ->
                val newMethod = cc.cors.connect.cors.CorsLinkMethod.valueOf(picked.id)
                if (newMethod != Prefs.corsLinkMethod) {
                    Prefs.corsLinkMethod = newMethod
                    rebuild()
                }
            }
        }

        return section
    }

    private fun buildAppearanceSection(): View {
        val section = ui.newSection(R.string.settings_section_appearance)
        val card = ui.sectionCard(section)
        // Theme picker removed: the app is dark-only now.
        ui.addRow(card, R.drawable.ic_setting_language, getString(R.string.settings_row_language), getString(R.string.settings_row_language_sub), currentLanguageLabel()) {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_language),
                subtitle = getString(R.string.settings_row_language_sub),
                options = listOf(
                    ChoiceActionSheet.Option(LANG_SYSTEM, getString(R.string.language_system)),
                    ChoiceActionSheet.Option(LANG_EN, getString(R.string.language_english)),
                    ChoiceActionSheet.Option(LANG_RU, getString(R.string.language_russian)),
                ),
                selectedId = currentLanguageId(),
            ) { picked ->
                applyLanguage(picked.id)
            }
        }
        return section
    }

    /** App housekeeping: version/updates and the battery-optimization exemption. */
    private fun buildAppSection(): View {
        val section = ui.newSection(R.string.settings_section_app)
        val card = ui.sectionCard(section)

        ui.addRow(card, R.drawable.ic_setting_update, getString(R.string.settings_row_update), getString(R.string.settings_row_update_sub, BuildConfig.VERSION_NAME), null) {
            UpdateActionSheet.show(parentFragmentManager)
        }

        val batterySub = if (BatteryOptimizer.isIgnored(requireContext())) {
            getString(R.string.settings_row_battery_ok)
        } else {
            getString(R.string.settings_row_battery_sub)
        }
        ui.addRow(card, R.drawable.ic_setting_battery, getString(R.string.settings_row_battery), batterySub, null) {
            BatteryOptimizer.requestIgnore(requireContext())
        }

        return section
    }

    private fun buildAdvancedSection(): View {
        val section = ui.newSection(R.string.settings_section_advanced)
        val card = ui.sectionCard(section)
        ui.addRow(card, R.drawable.ic_setting_advanced, getString(R.string.settings_row_advanced), getString(R.string.settings_row_advanced_sub), null) {
            (activity as? MainActivityHost)?.pushSubPage(AdvancedSettingsScreenFragment())
        }
        return section
    }

    private fun currentLanguageId(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return LANG_SYSTEM
        return when (locales[0]?.language) {
            "ru" -> LANG_RU
            "en" -> LANG_EN
            else -> LANG_SYSTEM
        }
    }

    private fun currentLanguageLabel(): String = getString(
        when (currentLanguageId()) {
            LANG_RU -> R.string.language_russian
            LANG_EN -> R.string.language_english
            else -> R.string.language_system
        }
    )

    /** Applies the per-app locale; "system" clears the override so the app follows the OS language. */
    private fun applyLanguage(id: String) {
        val locales = when (id) {
            LANG_RU -> LocaleListCompat.forLanguageTags("ru")
            LANG_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun rebuild() {
        if (!isAdded) return
        val rootView = view ?: return
        onViewCreated(rootView, null)
    }

    private companion object {
        const val LANG_SYSTEM = "system"
        const val LANG_EN = "en"
        const val LANG_RU = "ru"
    }
}
