package com.voicenote.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Big tap-to-record button: tap once to start, tap again to stop.
 * States: IDLE → RECORDING → UPLOADING → SUCCESS / ERROR
 */
class RecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, RECORDING, UPLOADING, SUCCESS, ERROR }

    var state: State = State.IDLE
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    var onPressStart: (() -> Unit)? = null
    var onPressEnd: (() -> Unit)? = null

    // Paints
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val bounds = RectF()

    // Animation
    private var pulseRadius = 0f
    private var pulseAlpha = 0f
    private var scaleAnim = 1f
    private var pulseAnimator: ValueAnimator? = null

    // Colors
    private val colorIdle get() = ContextCompat.getColor(context, R.color.idle_ring)
    private val colorRecording get() = ContextCompat.getColor(context, R.color.recording_ring)
    private val colorUploading get() = ContextCompat.getColor(context, R.color.uploading_ring)
    private val colorSuccess get() = ContextCompat.getColor(context, R.color.success_ring)

    init {
        updateColors()
        isClickable = true
    }

    private fun updateColors() {
        val color = when (state) {
            State.IDLE -> colorIdle
            State.RECORDING -> colorRecording
            State.UPLOADING -> colorUploading
            State.SUCCESS -> colorSuccess
            State.ERROR -> colorRecording
        }
        outerRingPaint.color = color
        innerCirclePaint.color = color
        pulseRingPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.78f

        // Pulse ring (recording state)
        if (state == State.RECORDING && pulseAlpha > 0) {
            pulseRingPaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(cx, cy, radius + pulseRadius, pulseRingPaint)
        }

        // Outer static ring
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius, outerRingPaint)

        // Inner filled circle (scales on press)
        val innerRadius = radius * 0.72f * scaleAnim
        canvas.drawCircle(cx, cy, innerRadius, innerCirclePaint)

        // Frosty iceberg mark in idle state
        if (state == State.IDLE) {
            drawFrostyIcon(canvas, cx, cy, innerRadius * 0.58f)
        }

        // Square icon when recording (tap-to-stop visual cue)
        if (state == State.RECORDING) {
            val sq = innerRadius * 0.32f
            val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFF0D0D0D.toInt()
            }
            canvas.drawRoundRect(
                cx - sq, cy - sq, cx + sq, cy + sq,
                sq * 0.25f, sq * 0.25f,
                squarePaint
            )
        }
    }

    // Facets of the Frosty iceberg (matches @drawable/ic_iceberg), on its
    // 108-unit design grid: six vertex pairs per triangle, then color + alpha.
    private val frostyFacets = listOf(
        Triple(floatArrayOf(34f, 62f, 54f, 84f, 56f, 62f), 0xFF5E9FBE.toInt(), 140),
        Triple(floatArrayOf(56f, 62f, 54f, 84f, 74f, 62f), 0xFF4A8CAD.toInt(), 140),
        Triple(floatArrayOf(50f, 26f, 30f, 62f, 46f, 50f), 0xFFDCEFF7.toInt(), 255),
        Triple(floatArrayOf(50f, 26f, 46f, 50f, 60f, 44f), 0xFFF4FBFF.toInt(), 255),
        Triple(floatArrayOf(50f, 26f, 60f, 44f, 68f, 34f), 0xFFCBE6F2.toInt(), 255),
        Triple(floatArrayOf(68f, 34f, 60f, 44f, 78f, 62f), 0xFFB8DCEC.toInt(), 255),
        Triple(floatArrayOf(46f, 50f, 30f, 62f, 56f, 62f), 0xFFC4E2F0.toInt(), 255),
        Triple(floatArrayOf(46f, 50f, 56f, 62f, 60f, 44f), 0xFFE8F6FC.toInt(), 255),
        Triple(floatArrayOf(60f, 44f, 56f, 62f, 78f, 62f), 0xFFA9D3E8.toInt(), 255),
    )
    private val frostyPath = android.graphics.Path()
    private val frostyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private fun drawFrostyIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // Map the 108-grid (berg centered on ~(54,55), half-extent 30) so
        // `size` is the drawn half-extent.
        fun px(gx: Float) = cx + (gx - 54f) / 30f * size
        fun py(gy: Float) = cy + (gy - 55f) / 30f * size

        for ((pts, color, alpha) in frostyFacets) {
            frostyPath.reset()
            frostyPath.moveTo(px(pts[0]), py(pts[1]))
            frostyPath.lineTo(px(pts[2]), py(pts[3]))
            frostyPath.lineTo(px(pts[4]), py(pts[5]))
            frostyPath.close()
            frostyPaint.color = color
            frostyPaint.alpha = alpha
            canvas.drawPath(frostyPath, frostyPaint)
        }
        // Waterline
        frostyPaint.color = 0xFF7FC4DE.toInt()
        frostyPaint.alpha = 230
        canvas.drawRect(px(24f), py(61.4f), px(84f), py(62.6f), frostyPaint)
    }

    /**
     * Tap to start, tap again to stop.
     *
     * The whole gesture is resolved on ACTION_UP and routed through
     * [performClick] so accessibility services (TalkBack) can activate the
     * button too — the old down/up split never called it, so click listeners
     * were silently dead.
     *
     * ACTION_CANCEL is deliberately a no-op: under press-and-hold a cancel
     * meant "finger left, finish up", but under tap semantics a cancel is a
     * stolen gesture (a scroll, a system overlay) and must NOT send the
     * recording.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animatePressDown()
                true
            }
            MotionEvent.ACTION_UP -> {
                animatePressUp()
                // Only count as a tap if the finger is still on the button.
                if (event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height) {
                    performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePressUp()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (state == State.RECORDING) {
            onPressEnd?.invoke()
        } else if (state == State.IDLE || state == State.SUCCESS || state == State.ERROR) {
            // The pulse starts only once the caller confirms recording really
            // began (see [markRecordingStarted]); a blocked start — missing
            // endpoint, denied permission — must not latch the button into a
            // fake recording look.
            onPressStart?.invoke()
        }
        return true
    }

    /** Called by the host once the recorder is actually running, so the
     *  button's look always reflects real state rather than assuming the
     *  start succeeded. */
    fun markRecordingStarted() {
        state = State.RECORDING
        startPulse()
    }

    /** Called when recording ends (or was never really running). */
    fun markRecordingStopped() {
        stopPulse()
    }

    private fun animatePressDown() {
        ObjectAnimator.ofFloat(this, "scaleAnimValue", 1f, 0.93f).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun animatePressUp() {
        ObjectAnimator.ofFloat(this, "scaleAnimValue", scaleAnim, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    // Property used by ObjectAnimator
    @Suppress("unused")
    fun setScaleAnimValue(v: Float) {
        scaleAnim = v
        invalidate()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val t = it.animatedFraction
                pulseRadius = t * 28f
                pulseAlpha = 1f - t
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRadius = 0f
        pulseAlpha = 0f
        invalidate()
    }
}
