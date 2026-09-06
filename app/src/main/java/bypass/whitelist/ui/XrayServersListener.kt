package bypass.whitelist.ui

/** Notifies hosts (Activity/Fragment) that the saved Xray server/subscription list changed. */
interface XrayServersListener {
    fun onXrayServersChanged()
}
