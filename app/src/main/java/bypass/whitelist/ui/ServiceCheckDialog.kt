package bypass.whitelist.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import bypass.whitelist.R

/**
 * Centered modal that shows the "service availability" probe outcome. The
 * main screen button opens it in a running state; [publish] streams the
 * results in as each sequential probe finishes — checked rows render their
 * RTT right away while the pending services stay as pulsing "…" placeholders
 * (the dialog survives the async callback via [current] — the check is short
 * and tied to this screen's lifecycle).
 */
class ServiceCheckDialog : DialogFragment(R.layout.dialog_service_check) {

    private var running = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        dialog?.window?.apply {
            // Transparent window + a very light scrim so the menu behind stays
            // visible; only the results frame itself is opaque.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.15f)
        }
        // Entrance: the card scales and fades up from 92%.
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()

        view.findViewById<View>(R.id.serviceDialogClose).setOnClickListener { dismiss() }
        // The probe is a real TCP round-trip to each host:443 through the active
        // tunnel (SOCKS5 CONNECT), in both Whitelist Bypass and Xray modes.
        view.findViewById<View>(R.id.serviceDialogNote).visibility = View.GONE
        if (running) {
            view.findViewById<TextView>(R.id.serviceDialogSummary).startAnimation(
                AlphaAnimation(0.4f, 1.0f).apply {
                    duration = 450
                    repeatMode = AlphaAnimation.REVERSE
                    repeatCount = AlphaAnimation.INFINITE
                },
            )
        } else {
            render(cache, pendingCount = 0, finished = true)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    fun showResults(results: List<MainFragment.ServiceStatus>) {
        running = false
        cache = results
        val view = view ?: return
        view.findViewById<TextView>(R.id.serviceDialogSummary).clearAnimation()
        render(results, pendingCount = 0, finished = true)
    }

    /** Streams a partial result set while the sequential check is running. */
    fun showProgress(results: List<MainFragment.ServiceStatus>) {
        if (!running) return
        cache = results
        val view = view ?: return
        val total = MainFragment.SERVICE_TARGETS.size
        view.findViewById<TextView>(R.id.serviceDialogProgressLabel).text =
            getString(R.string.services_progress, results.size, total)
        render(results, pendingCount = total - results.size, finished = false)
    }

    /** Rows are code-built, same as the old inline list (see MainFragmentView). */
    private fun render(
        results: List<MainFragment.ServiceStatus>,
        pendingCount: Int,
        finished: Boolean,
    ) {
        val view = view ?: return
        val context = requireContext()
        val summary = view.findViewById<TextView>(R.id.serviceDialogSummary)
        val list = view.findViewById<LinearLayout>(R.id.serviceDialogList)
        val runningRow = view.findViewById<View>(R.id.serviceDialogRunning)

        runningRow.visibility = if (finished) View.GONE else View.VISIBLE

        val okCount = results.count { it.ok }
        summary.text = if (finished) {
            context.getString(R.string.services_summary, okCount, results.size)
        } else {
            context.getString(R.string.services_running)
        }
        if (finished) {
            val summaryColor = when {
                results.isEmpty() -> R.color.ink
                okCount == results.size -> R.color.ok_green
                okCount == 0 -> R.color.error_red
                else -> R.color.warn_amber
            }
            summary.setTextColor(context.getColor(summaryColor))
        }

        // Keep already-shown rows; only animate the ones that are new this
        // render so results visibly "land" as the sequential probe streams in.
        val previousCount = list.childCount
        list.removeAllViews()
        val rows = ArrayList<View>()
        results.forEach { result -> rows.add(buildResultRow(result)) }
        // Not-yet-checked services keep their slot with a pulsing "…" so the
        // list doesn't jump around as results stream in.
        MainFragment.SERVICE_TARGETS.drop(results.size).forEach { rows.add(buildPendingRow(it.first)) }

        rows.forEachIndexed { index, row ->
            list.addView(row)
            if (index >= previousCount - 1) {
                row.alpha = 0f
                row.translationY = 14f
                row.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(((index - (previousCount - 1)).coerceAtLeast(0)) * 35L)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun buildResultRow(result: MainFragment.ServiceStatus): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 6.dp() }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setBackgroundResource(R.drawable.bg_service_row)
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(7.dp(), 7.dp()).apply { marginEnd = 10.dp() }
            setBackgroundResource(if (result.ok) R.drawable.bg_status_dot_ok else R.drawable.bg_status_dot_warn)
        }
        val name = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = result.name
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.ink))
        }
        val status = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            text = if (result.ok) {
                context.getString(R.string.services_ms, result.rttMs)
            } else {
                context.getString(R.string.services_fail)
            }
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(context.getColor(if (result.ok) R.color.ink_2 else R.color.error_red))
        }
        row.addView(dot)
        row.addView(name)
        row.addView(status)
        return row
    }

    private fun buildPendingRow(name: String): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 6.dp() }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setBackgroundResource(R.drawable.bg_service_row)
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(7.dp(), 7.dp()).apply { marginEnd = 10.dp() }
            setBackgroundResource(R.drawable.bg_status_dot_idle)
            startAnimation(
                AlphaAnimation(0.25f, 1.0f).apply {
                    duration = 450
                    repeatMode = AlphaAnimation.REVERSE
                    repeatCount = AlphaAnimation.INFINITE
                },
            )
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.ink_3))
        }
        val ellipsis = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            text = "…"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.ink_3))
        }
        row.addView(dot)
        row.addView(label)
        row.addView(ellipsis)
        return row
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (current === this) current = null
    }

    override fun onDestroy() {
        // Fallback for dismissals that bypass onDismiss (process death etc.).
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: ServiceCheckDialog? = null
        private var cache: List<MainFragment.ServiceStatus> = emptyList()

        fun open(manager: FragmentManager) {
            current?.dismissAllowingStateLoss()
            cache = emptyList()
            ServiceCheckDialog().apply { running = true }
                .also { current = it }
                .show(manager, "ServiceCheckDialog")
        }

        /**
         * Delivers results to the live dialog: a partial list keeps the
         * running spinner, a full list (one row per target) finishes it.
         * No-op when the dialog was closed.
         */
        fun publish(results: List<MainFragment.ServiceStatus>) {
            val dialog = current ?: return
            if (results.size >= MainFragment.SERVICE_TARGETS.size) {
                dialog.showResults(results)
            } else {
                dialog.showProgress(results)
            }
        }
    }
}
