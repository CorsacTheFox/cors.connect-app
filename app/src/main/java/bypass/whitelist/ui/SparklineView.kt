package bypass.whitelist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import bypass.whitelist.R

/**
 * Connected-view throughput chart: smooth RX (filled) and TX (line) curves
 * over the last [count] seconds on a linear, auto-scaling axis with faint
 * grid lines and a peak label. Fed once a second via [push]; [reset] clears.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val count = 60
    private val rx = FloatArray(count)
    private val tx = FloatArray(count)
    /** How many real samples are in the series (drawn right-aligned). */
    private var filled = 0

    /** Rolling ceiling (bytes/s) so the axis follows recent peaks smoothly. */
    private var ceiling = MIN_CEILING

    private val accent = ContextCompat.getColor(context, R.color.accent_emerald)
    private val ink3 = ContextCompat.getColor(context, R.color.ink_3)

    private val rxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val rxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.ink_2)
        alpha = 150
    }
    private val grid = Paint().apply {
        color = ContextCompat.getColor(context, R.color.hair)
        strokeWidth = dp(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink3
        textSize = dp(9.5f)
        typeface = Typeface.MONOSPACE
    }
    private val path = Path()

    fun push(rxBytesPerSec: Long, txBytesPerSec: Long) {
        System.arraycopy(rx, 1, rx, 0, count - 1)
        System.arraycopy(tx, 1, tx, 0, count - 1)
        // Light EMA so one-second bursts read as a wave, not a needle.
        val prevRx = if (filled > 0) rx[count - 2] else 0f
        val prevTx = if (filled > 0) tx[count - 2] else 0f
        rx[count - 1] = prevRx * 0.45f + rxBytesPerSec.coerceAtLeast(0) * 0.55f
        tx[count - 1] = prevTx * 0.45f + txBytesPerSec.coerceAtLeast(0) * 0.55f
        if (filled < count) filled++
        val peak = maxOf(rx.max(), tx.max())
        // Rise immediately, decay slowly — no jumpy rescaling.
        ceiling = maxOf(peak * 1.15f, ceiling * 0.97f, MIN_CEILING)
        invalidate()
    }

    fun reset() {
        rx.fill(0f)
        tx.fill(0f)
        filled = 0
        ceiling = MIN_CEILING
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rxFill.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            (accent and 0x00FFFFFF) or (0x80 shl 24),
            accent and 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = label.textSize + dp(6f)
        val bottom = h - grid.strokeWidth
        val plotH = bottom - top

        // Grid: baseline + quarters.
        for (i in 0..3) {
            val y = top + plotH * i / 4f
            canvas.drawLine(0f, y, w, y, grid)
        }
        canvas.drawLine(0f, bottom, w, bottom, grid)
        canvas.drawText(formatRate(ceiling), 0f, label.textSize, label)

        if (filled < 2) return
        val step = w / (count - 1)
        val first = count - filled
        fun y(v: Float) = bottom - plotH * (v / ceiling).coerceIn(0f, 1f)

        buildCurve(rx, first, step, ::y)
        canvas.drawPath(path, rxLine)
        path.lineTo(w, bottom)
        path.lineTo(first * step, bottom)
        path.close()
        canvas.drawPath(path, rxFill)

        buildCurve(tx, first, step, ::y)
        canvas.drawPath(path, txLine)
    }

    /** Smooth curve through the samples (midpoint quadratic segments). */
    private fun buildCurve(s: FloatArray, first: Int, step: Float, y: (Float) -> Float) {
        path.reset()
        var px = first * step
        var py = y(s[first])
        path.moveTo(px, py)
        for (i in first + 1 until count) {
            val x = i * step
            val yy = y(s[i])
            path.quadTo(px, py, (px + x) / 2f, (py + yy) / 2f)
            px = x
            py = yy
        }
        path.lineTo(px, py)
    }

    private fun formatRate(bytesPerSec: Float): String {
        val bits = bytesPerSec * 8
        return when {
            bits >= 1e6f -> String.format("%.1f Mbps", bits / 1e6f)
            else -> String.format("%.0f Kbps", bits / 1e3f)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        /** Axis never goes below ~100 Kbps, so idle noise stays near the floor. */
        const val MIN_CEILING = 12_500f
    }
}
