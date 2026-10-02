package bypass.whitelist.ui

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.DialogFragment
import bypass.whitelist.R

/**
 * Base for the app's action popups: a card centred on screen (settings-card
 * surface, 12dp corners, hairline border) over a dimmed backdrop, instead of
 * a bottom sheet. Subclasses just inflate their content in onCreateView.
 */
open class CenteredSheet : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AppCompatDialog(requireContext(), R.style.ThemeOverlay_WhitelistBypass_CenteredDialog)

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val dm = resources.displayMetrics
        val margin = (16 * dm.density).toInt()
        val card = GradientDrawable().apply {
            setColor(requireContext().getColor(R.color.panel_bg))
            setStroke((1 * dm.density).toInt(), requireContext().getColor(R.color.hair_ink))
            cornerRadius = 12 * dm.density
        }
        window.setBackgroundDrawable(InsetDrawable(card, margin, 0, margin, 0))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)
    }
}
