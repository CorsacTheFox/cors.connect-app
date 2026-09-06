package bypass.whitelist.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.tunnel.XrayVpnService
import bypass.whitelist.util.Prefs
import java.lang.ref.WeakReference

/**
 * Per-app split tunneling editor. The mode/package set in [Prefs] is shared
 * globally — it applies to both connection types (instance/call via
 * [TunnelVpnService] and standard Xray via [XrayVpnService]) so there's a
 * single split tunneling configuration for the whole app, not one per
 * connection type.
 */
class SplitTunnelingScreenFragment : Fragment() {

    /**
     * Process-wide cache of the installed-app scan (package / label /
     * user-app flag — no icons, no selection state). The PackageManager full
     * scan plus per-app label loading is what made this screen slow to open;
     * with the cache only the first open pays that cost. Icons are loaded
     * lazily by [SplitTunnelingAdapter] and never block the list.
     */
    private data class CachedApp(val packageName: String, val label: String, val isUserApp: Boolean)

    companion object {
        @Volatile private var cachedApps: List<CachedApp>? = null
    }

    private var mode: SplitTunnelingMode = SplitTunnelingMode.NONE
    private var packages: MutableSet<String> = mutableSetOf()

    /** True once [onViewCreated] finished loading state — see [onDestroyView]. */
    private var viewInitialized: Boolean = false

    private lateinit var summary: TextView
    private lateinit var appsHeader: View
    private lateinit var appsArea: View
    private lateinit var appsListContainer: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var systemAppsCheckbox: CheckBox
    private lateinit var appsList: ListView

    private lateinit var modeOffRow: View
    private lateinit var modeBypassRow: View
    private lateinit var modeOnlyRow: View
    private lateinit var modeOffCheck: View
    private lateinit var modeBypassCheck: View
    private lateinit var modeOnlyCheck: View

    private var allApps: List<SplitTunnelingAppItem> = emptyList()
    private var includeSystemApps: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_split_tunneling, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mode = Prefs.splitTunnelingMode
        packages = Prefs.splitTunnelingPackages.toMutableSet()

        view.findViewById<View>(R.id.modeCard).clipToOutline = true
        view.findViewById<TextView>(R.id.screenTitle).setText(R.string.split_tunneling_title)
        summary = view.findViewById(R.id.splitSummary)
        appsHeader = view.findViewById(R.id.appsHeader)
        appsArea = view.findViewById(R.id.appsArea)
        appsListContainer = view.findViewById(R.id.appsListContainer)
        loadingBar = view.findViewById(R.id.loadingBar)
        searchInput = view.findViewById(R.id.searchInput)
        systemAppsCheckbox = view.findViewById(R.id.systemAppsCheckbox)
        appsList = view.findViewById(R.id.appsListView)
        modeOffRow = view.findViewById(R.id.modeOff)
        modeBypassRow = view.findViewById(R.id.modeBypass)
        modeOnlyRow = view.findViewById(R.id.modeOnly)
        modeOffCheck = view.findViewById(R.id.modeOffCheck)
        modeBypassCheck = view.findViewById(R.id.modeBypassCheck)
        modeOnlyCheck = view.findViewById(R.id.modeOnlyCheck)

        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener { popSelf() }

        modeOffRow.setOnClickListener { applyMode(SplitTunnelingMode.NONE) }
        modeBypassRow.setOnClickListener { applyMode(SplitTunnelingMode.BYPASS) }
        modeOnlyRow.setOnClickListener { applyMode(SplitTunnelingMode.ONLY) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                popSelf()
            }
        })

        applyMode(mode, persist = false)
        refreshAppsList(initial = true)
        viewInitialized = true
    }

    override fun onDestroyView() {
        // Persist on every exit path (back button, bottom tab tap, tab swipe) —
        // not just popSelf(), so selections are never lost. Only when
        // onViewCreated actually completed: the fields hold the NONE/empty
        // defaults until then, and writing them unconditionally could wipe a
        // saved selection if the view was destroyed right after a rapid
        // re-open (the "split routing resets to empty" bug).
        if (viewInitialized) {
            Prefs.splitTunnelingMode = mode
            Prefs.splitTunnelingPackages = packages
        }
        viewInitialized = false
        super.onDestroyView()
    }

    private fun popSelf() {
        val running = TunnelVpnService.instance?.isRunning == true || XrayVpnService.instance?.isRunning == true
        if (running) {
            Toast.makeText(requireContext(), R.string.split_tunneling_mode_changed, Toast.LENGTH_SHORT).show()
        }
        (activity as? MainActivityHost)?.popSubPage()
    }

    private fun applyMode(newMode: SplitTunnelingMode, persist: Boolean = true) {
        mode = newMode
        if (persist) {
            Prefs.splitTunnelingMode = newMode
        }
        modeOffCheck.visibility = if (newMode == SplitTunnelingMode.NONE) View.VISIBLE else View.INVISIBLE
        modeBypassCheck.visibility = if (newMode == SplitTunnelingMode.BYPASS) View.VISIBLE else View.INVISIBLE
        modeOnlyCheck.visibility = if (newMode == SplitTunnelingMode.ONLY) View.VISIBLE else View.INVISIBLE
        val showApps = newMode != SplitTunnelingMode.NONE
        appsHeader.visibility = if (showApps) View.VISIBLE else View.GONE
        appsArea.visibility = if (showApps) View.VISIBLE else View.GONE
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = if (mode == SplitTunnelingMode.NONE) {
            getString(R.string.split_tunneling_summary_off)
        } else {
            resources.getQuantityString(R.plurals.split_tunneling_summary_count, packages.size, getString(mode.labelRes), packages.size)
        }
    }

    private fun refreshAppsList(initial: Boolean) {
        if (!initial && allApps.isNotEmpty()) {
            applyFilters()
            return
        }
        // Cache hit: skip the PackageManager scan entirely — the list renders
        // immediately with the (possibly still lazy-loading) icons.
        cachedApps?.let { cache ->
            allApps = buildItems(cache)
            showAppsList()
            return
        }
        loadingBar.visibility = View.VISIBLE
        appsListContainer.visibility = View.GONE
        val ctx = context ?: return
        val pm = ctx.packageManager
        val ownPackage = ctx.packageName
        val act = activity ?: return
        val weakSelf = WeakReference(this)
        Thread {
            // No GET_META_DATA (nothing here needs meta data) and no per-app
            // icon loading — both dominated the old load time. Labels still
            // have to be resolved per app for sorting/filtering.
            val loaded = pm.getInstalledApplications(0)
                .filter { it.packageName != ownPackage }
                .mapNotNull { info ->
                    val pkg = info.packageName
                    if (pkg.isBlank()) return@mapNotNull null
                    val label = info.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: pkg
                    CachedApp(pkg, label, (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            cachedApps = loaded
            if (weakSelf.get() == null) return@Thread
            act.runOnUiThread {
                val self = weakSelf.get() ?: return@runOnUiThread
                if (!self.isAdded) return@runOnUiThread
                self.allApps = self.buildItems(loaded)
                self.showAppsList()
            }
        }.start()
    }

    private fun buildItems(cache: List<CachedApp>): List<SplitTunnelingAppItem> {
        val selectedSnapshot = packages.toSet()
        return cache.map {
            SplitTunnelingAppItem(
                it.packageName, it.label,
                selectedSnapshot.contains(it.packageName),
                it.isUserApp,
            )
        }.sortedWith(
            compareByDescending<SplitTunnelingAppItem> { it.isSelected }.thenBy { it.label.lowercase() }
        )
    }

    private fun showAppsList() {
        loadingBar.visibility = View.GONE
        appsListContainer.visibility = View.VISIBLE
        val weakSelf = WeakReference(this)
        appsList.adapter = SplitTunnelingAdapter(layoutInflater, packages)
        applyFilters()
        systemAppsCheckbox.isChecked = includeSystemApps
        systemAppsCheckbox.setOnCheckedChangeListener { _, checked ->
            val s = weakSelf.get() ?: return@setOnCheckedChangeListener
            s.includeSystemApps = checked
            s.applyFilters()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                weakSelf.get()?.applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {
                weakSelf.get()?.updateSummary()
            }
        })
    }

    private fun applyFilters() {
        val adapter = appsList.adapter as? SplitTunnelingAdapter ?: return
        val query = searchInput.text.toString()
        val base = allApps.filter { includeSystemApps || it.isUserApp }
        val filtered = if (query.isBlank()) base else base.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        adapter.items = filtered
        updateSummary()
    }

}
