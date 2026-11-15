package com.voicenote.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * Three dots that pulse in sequence — the "assistant is working" affordance.
 *
 * Self-contained (no dependency, no drawable). The animator starts on attach
 * and **stops on detach**, which matters because these live inside recycled
 * RecyclerView rows: a stray animator on a scrolled-off row would keep the
 * view invalidating forever.
 */
class TypingDotsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        style = Paint.Style.FILL
    }

    private val dotCount = 3
    private val dotRadius = 3.5f * resources.displayMetrics.density
    private val dotGap = 6f * resources.displayMetrics.density
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (dotCount * dotRadius * 2 + (dotCount - 1) * dotGap).toInt()
        val h = (dotRadius * 4).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        var cx = dotRadius
        for (i in 0 until dotCount) {
            // Each dot lags the previous by a third of the cycle.
            val t = (phase - i * 0.22f + 1f) % 1f
            // Ease up then down: 0 -> 1 -> 0 across the cycle.
            val wave = if (t < 0.5f) t * 2f else (1f - t) * 2f
            paint.alpha = (70 + 185 * wave).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, dotRadius, paint)
            cx += dotRadius * 2 + dotGap
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
