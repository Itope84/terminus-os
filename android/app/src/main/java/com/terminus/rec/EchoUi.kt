package com.terminus.rec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Scrolling level bars: push() adds the newest bar on the right. */
class WaveView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val bars = FloatArray(40) { 0.04f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.t_ok) }
    private val r = RectF()

    fun push(level: Float) {
        System.arraycopy(bars, 1, bars, 0, bars.size - 1)
        bars[bars.size - 1] = level
        invalidate()
    }

    fun reset() {
        bars.fill(0.04f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / bars.size
        val bw = slot * 0.55f
        for (i in bars.indices) {
            val bh = max(4f, bars[i] * h)
            val cx = slot * i + slot / 2
            r.set(cx - bw / 2, (h - bh) / 2, cx + bw / 2, (h + bh) / 2)
            canvas.drawRoundRect(r, bw / 2, bw / 2, paint)
        }
    }
}
