package bypass.whitelist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import bypass.whitelist.R
import kotlin.math.ln

/**
 * Thin throughput bar-graph from the Nocturne redesign (screen 1b). Fed the
 * real per-second tunnel byte rate via [push]; each call shifts the series
 * left and appends one bar. [reset] clears it back to flat.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val count = 30
    private val series = FloatArray(count)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = resources.displayMetrics.density * 3f

    /** Rolling ceiling so the graph auto-scales to recent peak activity. */
    private var ceiling = 1f

    fun push(bytesPerSec: Long) {
        val v = ln(1.0 + bytesPerSec.coerceAtLeast(0)).toFloat()
        ceiling = maxOf(ceiling * 0.92f, v, 1f)
        System.arraycopy(series, 1, series, 0, count - 1)
        series[count - 1] = (v / ceiling).coerceIn(0f, 1f)
        invalidate()
    }

    fun reset() {
        series.fill(0f)
        ceiling = 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            ContextCompat.getColor(context, R.color.accent_emerald),
            ContextCompat.getColor(context, R.color.accent_deep),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = (w - gap * (count - 1)) / count
        val min = density(1f)
        var x = 0f
        for (i in 0 until count) {
            val bh = (h * series[i]).coerceAtLeast(min)
            canvas.drawRoundRect(x, h - bh, x + barW, h, min, min, barPaint)
            x += barW + gap
        }
    }

    private fun density(dp: Float) = dp * resources.displayMetrics.density
}
