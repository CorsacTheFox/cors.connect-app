package bypass.whitelist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import bypass.whitelist.R
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Animated neon-violet backdrop: a deep vertical gradient base with a single
 * continuously roaming violet wash — its radius is larger than the viewport and
 * its centre drifts on a Lissajous path, so it never reads as a circle, just a
 * smeared field of light that keeps moving. A second, even softer wash rides
 * out of phase for asymmetry.
 *
 * The dot lattice only lights up inside short-lived ripples that start where the
 * user last touched: a bright core at the tap point that fades outward and
 * quickly dies.
 *
 * Everything is driven by one repeating [ValueAnimator]. Placed behind the pager
 * in activity_main so every tab sits on it; sub pages are translucent and let it
 * show through. [rippleAt] is fed touch points by MainActivity.dispatchTouchEvent
 * (a view behind the pager never receives the touches itself).
 */
class GradientBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val basePaint = Paint()
    private val washPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Pre-rendered full-screen dot lattice (ALPHA_8 mask), drawn every frame at
    // a barely-there opacity. Same grid spacing as the touch ripples.
    private var dotLayer: Bitmap? = null
    private val staticDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val backdropTop = ContextCompat.getColor(context, R.color.backdrop_top)
    private val backdropBottom = ContextCompat.getColor(context, R.color.backdrop_bottom)

    // Very dark violet — a diffuse, many-stop falloff so the wash has no hard
    // edge anywhere, and dim enough that it only barely lifts the black.
    private val washColor1 = Color.argb(0x66, 0x36, 0x20, 0x64)
    private val washColor2 = Color.argb(0x42, 0x20, 0x16, 0x48)
    private val dotColor = Color.argb(0xFF, 0xD6, 0xBE, 0xFF)

    init {
        staticDotPaint.color = alphaColor(dotColor, 0.06f)
    }

    /** Seconds since attach — a monotonic, non-wrapping clock for the drift. */
    private var phase = 0f
    private var startAt = 0L
    private var drift: ValueAnimator? = null

    private val dotStepDp = 5.5f
    private val dotRadiusDp = 0.9f
    private val rippleMaxDp = 41f
    private val rippleBandDp = 30f
    private val rippleLifeMs = 950L
    private val maxRipples = 6

    private data class Ripple(val x: Float, val y: Float, val start: Long)

    private val ripples = ArrayList<Ripple>()

    /** Called from the activity for every ACTION_DOWN; [x]/[y] are window px. */
    fun rippleAt(x: Float, y: Float) {
        if (x < 0f || y < 0f || x > width || y > height) return
        synchronized(ripples) {
            if (ripples.size >= maxRipples) ripples.removeAt(0)
            ripples.add(Ripple(x, y, System.currentTimeMillis()))
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, fh,
            backdropTop, backdropBottom, Shader.TileMode.CLAMP,
        )
        // Washes are much wider than the screen and use a smooth multi-stop
        // ramp, so what shows on screen is only the soft middle of the falloff —
        // a smear, never a disc.
        val stops = floatArrayOf(0f, 0.35f, 0.65f, 1f)
        washPaint1.shader = RadialGradient(
            0f, 0f, fw * 1.35f,
            intArrayOf(
                washColor1,
                alphaColor(washColor1, 0.55f),
                alphaColor(washColor1, 0.15f),
                Color.TRANSPARENT,
            ),
            stops, Shader.TileMode.CLAMP,
        )
        washPaint2.shader = RadialGradient(
            0f, 0f, fw * 1.6f,
            intArrayOf(
                washColor2,
                alphaColor(washColor2, 0.5f),
                alphaColor(washColor2, 0.12f),
                Color.TRANSPARENT,
            ),
            stops, Shader.TileMode.CLAMP,
        )
        buildDotLayer(w, h)
    }

    /** Renders the static dot grid once into an ALPHA_8 mask bitmap. */
    private fun buildDotLayer(w: Int, h: Int) {
        dotLayer?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val density = resources.displayMetrics.density
        val step = dotStepDp * density
        val dotR = dotRadiusDp * density
        var gx = step / 2f
        while (gx < w) {
            var gy = step / 2f
            while (gy < h) {
                c.drawCircle(gx, gy, dotR, p)
                gy += step
            }
            gx += step
        }
        dotLayer = bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, basePaint)

        // `phase` is elapsed seconds and never wraps, so every sine term below
        // stays continuous forever — no periodic jump/tear in the drift.
        val t = phase * 0.62f

        // One roaming wash across the whole viewport + a slower counter-phase
        // one. Centres wander well past the edges so the pattern shifts
        // constantly without ever settling into a shape.
        drawWash(
            canvas,
            w * (0.5f + 0.42f * sin(t)),
            h * (0.5f + 0.34f * sin(t * 0.73f + 1.3f)),
            washPaint1,
        )
        drawWash(
            canvas,
            w * (0.5f + 0.38f * sin(t * 0.55f + Math.PI.toFloat())),
            h * (0.5f + 0.30f * sin(t * 0.87f + 2.6f)),
            washPaint2,
        )

        dotLayer?.let { canvas.drawBitmap(it, 0f, 0f, staticDotPaint) }
        drawRipples(canvas)
    }

    private fun drawWash(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, width * 1.4f, paint)
        canvas.restore()
    }

    /**
     * A wave of dots that spreads out from the touch point and then fades. Dots
     * light only inside a ring that rides the expanding wave front (brightest at
     * the front, soft tail inward); the front decelerates as it grows and the
     * whole ring fades to nothing over the back half of [rippleLifeMs] (< 1 s).
     * No fill or glow under the dots.
     */
    private fun drawRipples(canvas: Canvas) {
        val snapshot: List<Ripple>
        val now = System.currentTimeMillis()
        synchronized(ripples) {
            ripples.removeAll { now - it.start > rippleLifeMs }
            snapshot = ArrayList(ripples)
        }
        if (snapshot.isEmpty()) return

        val density = resources.displayMetrics.density
        val step = dotStepDp * density
        val dotR = dotRadiusDp * density
        val maxR = rippleMaxDp * density
        val band = rippleBandDp * density
        val w = width.toFloat()
        val h = height.toFloat()

        for (r in snapshot) {
            val age = ((now - r.start).toFloat() / rippleLifeMs).coerceIn(0f, 1f)
            // Expanding, decelerating wave front.
            val frontR = maxR * (1f - (1f - age) * (1f - age))
            if (frontR <= 1f) continue
            // Full strength while it spreads, then fades over the back half.
            val ageFade = 1f - ((age - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (ageFade <= 0f) continue

            val minX = (r.x - frontR).coerceAtLeast(0f)
            val maxX = (r.x + frontR).coerceAtMost(w)
            val minY = (r.y - frontR).coerceAtLeast(0f)
            val maxY = (r.y + frontR).coerceAtMost(h)
            var gx = (minX / step).toInt() * step + step / 2f
            while (gx <= maxX) {
                var gy = (minY / step).toInt() * step + step / 2f
                while (gy <= maxY) {
                    val dist = hypot(gx - r.x, gy - r.y)
                    // Distance behind the front, normalised to the band width.
                    val p = (dist - (frontR - band)) / band
                    if (p in 0f..1f) {
                        val b = ageFade * p * p * 0.22f
                        if (b > 0.015f) {
                            dotPaint.color = alphaColor(dotColor, b)
                            canvas.drawCircle(gx, gy, dotR, dotPaint)
                        }
                    }
                    gy += step
                }
                gx += step
            }
        }
    }

    private fun alphaColor(color: Int, alphaFactor: Float): Int {
        val alpha = (Color.alpha(color) * alphaFactor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAt = android.os.SystemClock.elapsedRealtime()
        // The animator is just a vsync-paced ticker; the actual drift phase is
        // read from a monotonic clock each frame so it can't jump on loop.
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10_000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = (android.os.SystemClock.elapsedRealtime() - startAt) / 1000f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        drift?.cancel()
        drift = null
        dotLayer?.recycle()
        dotLayer = null
        super.onDetachedFromWindow()
    }
}
