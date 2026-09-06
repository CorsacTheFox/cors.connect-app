package bypass.whitelist.ui

import androidx.fragment.app.Fragment

interface MainActivityHost {
    fun pushSubPage(fragment: Fragment)
    fun popSubPage()

    /** Switch the bottom-nav to the "Servers" (Xray subscriptions) root tab. */
    fun openServersTab()
}
