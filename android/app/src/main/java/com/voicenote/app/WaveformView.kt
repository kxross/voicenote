package com.voicenote.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin

/**
 * Simple waveform bar display.
 * Feed it amplitude values via pushAmplitude() while recording.
 * Animates idle sine wave when not recording.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 40
    private val amplitudes = FloatArray(barCount) { 0f }
    private var isRecording = false
    private var idlePhase = 0f
    private val idleAnimRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) {
                idlePhase += 0.08f
                invalidate()
                postDelayed(this, 16)
            }
        }
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.FILL
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idle_ring)
        style = Paint.Style.FILL
    }

    fun startRecording() {
        isRecording = true
        removeCallbacks(idleAnimRunnable)
        invalidate()
    }

    fun stopRecording() {
        isRecording = false
        amplitudes.fill(0f)
        post(idleAnimRunnable)
    }

    fun pushAmplitude(amp: Float) {
        // Shift left
        for (i in 0 until barCount - 1) {
            amplitudes[i] = amplitudes[i + 1]
        }
        amplitudes[barCount - 1] = amp.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(idleAnimRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleAnimRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / (barCount * 1.6f)
        val gap = barWidth * 0.6f
        val totalBarWidth = barWidth + gap
        val startX = (w - (barCount * totalBarWidth - gap)) / 2f
        val centerY = h / 2f
        val maxBarH = h * 0.45f

        for (i in 0 until barCount) {
            val x = startX + i * totalBarWidth
            val amp = if (isRecording) {
                amplitudes[i]
            } else {
                // gentle idle sine wave
                val phase = idlePhase + i * 0.4f
                (sin(phase.toDouble()).toFloat() * 0.15f + 0.08f).coerceAtLeast(0.05f)
            }
            val barH = (maxBarH * amp).coerceAtLeast(3f)
            val paint = if (isRecording) barPaint else idlePaint
            canvas.drawRoundRect(
                x, centerY - barH,
                x + barWidth, centerY + barH,
                barWidth / 2, barWidth / 2,
                paint
            )
        }
    }
}
