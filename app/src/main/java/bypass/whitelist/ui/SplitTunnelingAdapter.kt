package bypass.whitelist.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import bypass.whitelist.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class SplitTunnelingAppItem(
    val packageName: String,
    val label: String,
    var isSelected: Boolean = false,
    val isUserApp: Boolean = false,
)

/**
 * Icons are NOT loaded up front — loading one drawable per installed app is
 * what made the screen take seconds to open. The list renders instantly with
 * blank icons and each icon is fetched lazily on a single background thread,
 * cached, and applied with one coalesced [notifyDataSetChanged] per drain so
 * hundreds of loads don't cause hundreds of relayouts.
 */
class SplitTunnelingAdapter(
    private val inflater: LayoutInflater,
    private val selectedPackages: MutableSet<String>,
) : BaseAdapter() {

    private val pm: PackageManager = inflater.context.packageManager
    private val iconCache = mutableMapOf<String, Drawable>()
    private val iconsPending = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var notifyQueued = false

    var items: List<SplitTunnelingAppItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)
        val view = convertView ?: inflater.inflate(R.layout.split_tunneling_app_list_item, parent, false)
        val iconView = view.findViewById<ImageView>(R.id.appIcon)
        val labelView = view.findViewById<TextView>(R.id.appLabel)
        val packageView = view.findViewById<TextView>(R.id.appPackage)
        val checkbox = view.findViewById<CheckBox>(R.id.appCheckbox)

        // Identifies the package this (possibly recycled) row currently shows.
        view.tag = item.packageName
        iconCache[item.packageName]?.let(iconView::setImageDrawable)
        requestIconIfNeeded(item.packageName, iconView)
        labelView.text = item.label
        val isDark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        labelView.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
        packageView.text = item.packageName

        val toggle = { checked: Boolean ->
            item.isSelected = checked
            if (checked) selectedPackages.add(item.packageName)
            else selectedPackages.remove(item.packageName)
        }

        view.setOnClickListener {
            val newState = !checkbox.isChecked
            checkbox.isChecked = newState
            toggle(newState)
        }
        checkbox.setOnCheckedChangeListener { _, isChecked -> toggle(isChecked) }
        checkbox.isChecked = item.isSelected

        return view
    }

    private fun requestIconIfNeeded(pkg: String, iconView: ImageView) {
        if (iconCache.containsKey(pkg)) return
        // Blank placeholder until the real icon arrives.
        iconView.setImageDrawable(null)
        if (!iconsPending.add(pkg)) return
        iconExecutor.execute {
            val icon = try {
                pm.getApplicationIcon(pkg)
            } catch (t: Throwable) {
                null
            }
            mainHandler.post {
                if (icon != null) iconCache[pkg] = icon
                iconsPending.remove(pkg)
                // Coalesce: one rebind pass covers every icon loaded so far.
                if (icon != null && !notifyQueued) {
                    notifyQueued = true
                    mainHandler.post {
                        notifyQueued = false
                        notifyDataSetChanged()
                    }
                }
            }
        }
    }
}
