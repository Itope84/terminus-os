package com.terminus.rec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A thin ring that fills with how much of the context window is used. A short tick marks the
 *  auto-compact ceiling when one is set. */
class ContextRingView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val stroke = resources.displayMetrics.density * 3f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = ctx.getColor(R.color.t_line)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val ceilingMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = maxOf(2f, stroke * 0.8f)
        strokeCap = Paint.Cap.ROUND; color = ctx.getColor(R.color.t_clay)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.t_muted); textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 9f
    }
    private val box = RectF()
    private var fraction = 0f
    private var ceilingFraction: Float? = null
    private var tokens = ""

    /** [used] is a 0..1 fraction of the window. [usedTokens] is the absolute
     *  count shown inside the ring (33k, 1.2m) — never a bare percent. */
    fun set(used: Float, ceilingAt: Float?, usedTokens: Long = 0) {
        fraction = used.coerceIn(0f, 1f)
        ceilingFraction = ceilingAt?.takeIf { it.isFinite() && it in 0f..1f }
        tokens = fmtTokens(usedTokens)
        arc.color = ctx().getColor(colorFor(fraction))
        invalidate()
    }

    private fun ctx() = context

    override fun onDraw(c: Canvas) {
        val pad = stroke
        box.set(pad, pad, width - pad, height - pad)
        c.drawArc(box, 0f, 360f, false, track)
        // Never an empty-looking ring: even a sliver shows a short arc and its count.
        if (fraction > 0f) c.drawArc(box, -90f, maxOf(360f * fraction, 12f), false, arc)
        ceilingFraction?.let { ceiling ->
            val angle = Math.toRadians((-90f + ceiling * 360f).toDouble())
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - stroke
            val inner = radius - stroke
            val outer = radius + stroke * 0.5f
            c.drawLine(
                cx + kotlin.math.cos(angle).toFloat() * inner,
                cy + kotlin.math.sin(angle).toFloat() * inner,
                cx + kotlin.math.cos(angle).toFloat() * outer,
                cy + kotlin.math.sin(angle).toFloat() * outer,
                ceilingMark,
            )
        }
        if (tokens.isNotEmpty()) c.drawText(tokens, width / 2f, height / 2f - (label.ascent() + label.descent()) / 2f, label)
    }

    companion object {
        /** Calm until 70%, warm to 90%, then the error colour. */
        fun colorFor(fraction: Float): Int = when {
            fraction >= 0.9f -> R.color.t_err
            fraction >= 0.7f -> R.color.t_clay
            else -> R.color.t_muted
        }

        /** Compact absolute count for the 32dp ring: 845, 33k, 100k, 1m, 1.2m, 3m. Empty at zero. */
        fun fmtTokens(n: Long): String = when {
            n <= 0 -> ""
            n < 1000 -> n.toString()
            n < 1_000_000 -> "${Math.round(n / 1000.0)}k".replace(".0k", "k")
            else -> {
                val m = n / 1_000_000.0
                if (m >= 100) "${Math.round(m)}m"
                else "${(Math.round(m * 10) / 10.0)}m".replace(".0m", "m")
            }
        }
    }
}
