package com.voicenote.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * A dependency-free zoomable/pannable image view: pinch to zoom, drag to pan
 * when zoomed, double-tap to toggle zoom. Fits the image on load and clamps
 * panning to the image bounds.
 *
 * Plays nice inside a ViewPager2: while at the fit scale it lets the pager
 * intercept horizontal drags (so pages swipe); once zoomed it keeps the touch
 * to pan.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val m = Matrix()
    private val values = FloatArray(9)
    private val last = PointF()
    private var minScale = 1f
    private val maxScale = 5f
    private var viewW = 0f
    private var viewH = 0f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageURI(uri: Uri?) {
        super.setImageURI(uri)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        viewW = w.toFloat()
        viewH = h.toFloat()
        fitToView()
    }

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun fitToView() {
        val d = drawable ?: return
        if (viewW == 0f || viewH == 0f) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = minOf(viewW / dw, viewH / dh)
        minScale = scale
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate((viewW - dw * scale) / 2f, (viewH - dh * scale) / 2f)
        imageMatrix = m
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        m.getValues(values)
        val tx = values[Matrix.MTRANS_X]
        val ty = values[Matrix.MTRANS_Y]
        val s = values[Matrix.MSCALE_X]
        val cw = d.intrinsicWidth * s
        val ch = d.intrinsicHeight * s
        val nx = clampTrans(tx, viewW, cw)
        val ny = clampTrans(ty, viewH, ch)
        m.postTranslate(nx - tx, ny - ty)
    }

    /** Keep content within the view: center it if smaller, pin to edges if larger. */
    private fun clampTrans(trans: Float, viewSize: Float, contentSize: Float): Float =
        if (contentSize <= viewSize) (viewSize - contentSize) / 2f
        else trans.coerceIn(viewSize - contentSize, 0f)

    private fun zoomed(): Boolean = currentScale() > minScale * 1.01f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(zoomed())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && zoomed()) {
                    m.postTranslate(event.x - last.x, event.y - last.y)
                    fixTranslation()
                    imageMatrix = m
                    last.set(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (!zoomed()) {
                    // at fit scale — let the pager swipe pages
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val proposed = currentScale() * factor
            if (proposed < minScale) factor = minScale / currentScale()
            if (proposed > maxScale) factor = maxScale / currentScale()
            m.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = m
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoomed()) minScale else minScale * 2.5f
            val factor = target / currentScale()
            m.postScale(factor, factor, e.x, e.y)
            fixTranslation()
            imageMatrix = m
            return true
        }
    }
}
