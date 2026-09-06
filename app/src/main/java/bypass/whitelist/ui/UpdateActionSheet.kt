package bypass.whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import bypass.whitelist.BuildConfig
import bypass.whitelist.R
import bypass.whitelist.util.AppUpdater
import bypass.whitelist.util.ApkInstaller
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * "Updates" sheet: shows the installed version, queries GitHub Releases on
 * open, and offers either an APK download (delegates to [ApkInstaller], which
 * survives this sheet being dismissed) or the releases page in a browser.
 */
class UpdateActionSheet : BottomSheetDialogFragment() {

    private var release: AppUpdater.Release? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_action_update, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val changelog = view.findViewById<TextView>(R.id.updateChangelog)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val download = view.findViewById<MaterialButton>(R.id.buttonDownload)
        val close = view.findViewById<MaterialButton>(R.id.buttonClose)

        status.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        close.setOnClickListener { dismiss() }
        download.setOnClickListener {
            val rel = release ?: return@setOnClickListener
            val context = requireContext()
            if (rel.apkUrl != null) {
                ApkInstaller.download(
                    context = context,
                    url = rel.apkUrl,
                    title = getString(R.string.update_notification_title, rel.version),
                )
                Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                AppUpdater.openReleasesPage(context)
            }
        }

        progress.visibility = View.VISIBLE
        status.text = getString(R.string.update_checking)
        AppUpdater.checkLatest { rel ->
            if (!isAdded) return@checkLatest
            progress.visibility = View.GONE
            if (rel == null) {
                status.text = getString(R.string.update_check_failed)
                download.isEnabled = true
                download.setText(R.string.update_open_page)
                return@checkLatest
            }
            release = rel
            if (AppUpdater.isNewer(rel.version)) {
                status.text = getString(R.string.update_available, rel.version)
                if (rel.changelog.isNotBlank()) {
                    changelog.visibility = View.VISIBLE
                    changelog.text = rel.changelog
                }
            } else {
                status.text = getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            }
            // The button always downloads the latest release's APK (even when
            // already up to date — a reinstall of the current build is fine);
            // the browser page stays the fallback for releases without an APK.
            download.isEnabled = true
            download.setText(if (rel.apkUrl != null) R.string.update_download else R.string.update_open_page)
        }
    }

    companion object {
        fun show(manager: FragmentManager) {
            UpdateActionSheet().show(manager, "UpdateActionSheet")
        }
    }
}
