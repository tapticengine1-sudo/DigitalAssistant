package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private var amplitude = 0f
    private val bars = 5
    private val barSpacing = 12f

    fun setAmplitude(rms: Float) {
        amplitude = min(rms / 10f, 1f)
        invalidate()
    }

    fun reset() {
        amplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val maxHeight = height * 0.6f

        for (i in 0 until bars) {
            val offset = (i - bars / 2) * (paint.strokeWidth + barSpacing)
            val factor = 1f - kotlin.math.abs(i - bars / 2) * 0.2f
            val barHeight = maxHeight * amplitude * factor * (0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 200f + i))
            
            canvas.drawLine(
                centerX + offset,
                centerY - barHeight / 2,
                centerX + offset,
                centerY + barHeight / 2,
                paint
            )
        }

        if (amplitude > 0.05f) {
            postInvalidateDelayed(50)
        }
    }
}