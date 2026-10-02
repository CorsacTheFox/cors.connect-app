package bypass.whitelist.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import bypass.whitelist.R

/** App typefaces for views built in code (XML uses @font/… directly). */
object Fonts {
    private var mono: Typeface? = null

    /** JetBrains Mono — labels, numbers, stats. */
    fun mono(context: Context): Typeface =
        mono ?: (ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE).also { mono = it }
}
