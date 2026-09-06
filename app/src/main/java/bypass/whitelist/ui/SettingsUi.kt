package bypass.whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.util.Callback
import bypass.whitelist.util.ParamCallback
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Shared builders for settings screens: section cards, tappable rows and
 * switch rows inflated from the same item layouts, so the root settings page
 * and the advanced sub-page look and behave identically.
 */
class SettingsUi(private val fragment: Fragment) {

    private val layoutInflater: LayoutInflater get() = fragment.layoutInflater

    fun newSection(labelRes: Int): View {
        val parent = fragment.view as ViewGroup?
        val v = layoutInflater.inflate(R.layout.item_settings_section, parent, false)
        v.findViewById<TextView>(R.id.sectionLabel).setText(labelRes)
        v.findViewById<View>(R.id.sectionCard).clipToOutline = true
        return v
    }

    fun sectionCard(section: View): LinearLayout = section.findViewById(R.id.sectionCard)

    fun addRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        trail: String?,
        danger: Boolean = false,
        onClick: Callback,
    ) {
        val row = layoutInflater.inflate(R.layout.item_settings_row, card, false)
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconRes)
        if (danger) {
            row.findViewById<View>(R.id.rowIconBox).setBackgroundResource(R.drawable.bg_settings_row_icon_danger)
            row.findViewById<ImageView>(R.id.rowIcon).setColorFilter(fragment.requireContext().getColor(R.color.error_red))
            row.findViewById<TextView>(R.id.rowTitle).setTextColor(fragment.requireContext().getColor(R.color.error_red))
        }
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSub).apply {
            if (sub.isNullOrBlank()) { visibility = View.GONE } else { text = sub; visibility = View.VISIBLE }
        }
        row.findViewById<TextView>(R.id.rowTrail).apply {
            if (trail.isNullOrBlank()) { visibility = View.GONE } else { text = trail; visibility = View.VISIBLE }
        }
        row.findViewById<ImageView>(R.id.rowChev).visibility = View.VISIBLE
        row.setOnClickListener { onClick() }
        if (card.isNotEmpty()) addDividerTo(card)
        card.addView(row)
    }

    fun addSwitchRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        initial: Boolean,
        onToggled: ParamCallback<Boolean>,
    ) {
        val row = layoutInflater.inflate(R.layout.item_settings_row, card, false)
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSub).apply {
            if (sub.isNullOrBlank()) { visibility = View.GONE } else { text = sub; visibility = View.VISIBLE }
        }
        val sw = row.findViewById<MaterialSwitch>(R.id.rowSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = initial
        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onToggled(sw.isChecked)
        }
        if (card.isNotEmpty()) addDividerTo(card)
        card.addView(row)
    }

    private fun addDividerTo(card: LinearLayout) {
        val divider = View(fragment.requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (14 * fragment.resources.displayMetrics.density).toInt()
                marginEnd = (14 * fragment.resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(fragment.requireContext().getColor(R.color.hair))
        }
        card.addView(divider)
    }
}
