package com.example.konkani

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * Live mic meter: five amber bars that follow real recording amplitude, so the speaker can SEE
 * the mic responding. Feed it MediaRecorder.getMaxAmplitude via [start]; call [stop] when done.
 */
class RecordingMeter @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD59B32.toInt() }
    private val levels = FloatArray(5)
    private var provider: (() -> Int)? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val amp = try { provider?.invoke() ?: 0 } catch (e: Exception) { 0 }
            val norm = (amp / 32767f).coerceIn(0f, 1f)
            for (i in 0 until levels.size - 1) levels[i] = levels[i + 1]
            levels[levels.size - 1] = norm
            invalidate()
            ui.postDelayed(this, 90)
        }
    }

    fun start(amplitudeProvider: () -> Int) {
        provider = amplitudeProvider
        levels.fill(0f)
        visibility = VISIBLE
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    fun stop() {
        provider = null
        ui.removeCallbacks(tick)
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val n = levels.size
        val gap = width / (n * 2f)
        for (i in 0 until n) {
            val h = (0.15f + 0.85f * levels[i]) * height
            val left = gap / 2 + i * 2 * gap
            canvas.drawRoundRect(left, (height - h) / 2, left + gap, (height + h) / 2, gap / 2, gap / 2, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
