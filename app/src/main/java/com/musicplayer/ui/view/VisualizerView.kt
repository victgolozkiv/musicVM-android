package com.musicplayer.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { SPECTRUM, CIRCULAR, PARTICLES }
    
    private var bytes: ByteArray? = null
    private var points: FloatArray? = null
    private val paint = Paint().apply {
        strokeWidth = 8f
        isAntiAlias = true
        color = -0x5ea106 // 0xFFA15EFA -> -0x5ea106
        strokeCap = Paint.Cap.ROUND
    }
    
    var currentMode = Mode.SPECTRUM
        private set
    
    private val particles = Array(50) { Particle() }
    private val random = Random()

    fun updateVisualizer(bytes: ByteArray) {
        this.bytes = bytes
        invalidate()
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun cycleMode() {
        val next = (currentMode.ordinal + 1) % Mode.values().size
        currentMode = Mode.values()[next]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bytes ?: return

        when (currentMode) {
            Mode.SPECTRUM -> drawSpectrum(canvas, data)
            Mode.CIRCULAR -> drawCircular(canvas, data)
            Mode.PARTICLES -> drawParticles(canvas, data)
        }
    }

    private fun drawSpectrum(canvas: Canvas, data: ByteArray) {
        if (points == null || points!!.size < data.size * 4) {
            points = FloatArray(data.size * 4)
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val numBars = data.size / 2
        
        points?.let { pts ->
            for (i in 0 until numBars) {
                val x = width * i / numBars
                pts[i * 4] = x
                pts[i * 4 + 1] = height
                pts[i * 4 + 2] = x
                val b = data[i]
                val amplitude = (b.toFloat() + 128) / 256
                pts[i * 4 + 3] = height - (amplitude * height * 0.8f)
            }
            paint.strokeWidth = width / numBars * 0.8f
            canvas.drawLines(pts, paint)
        }
    }

    private fun drawCircular(canvas: Canvas, data: ByteArray) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) * 0.5f
        val numBars = 60
        
        paint.strokeWidth = 6f
        for (i in 0 until numBars) {
            val byteIndex = i * data.size / numBars
            val b = data[byteIndex]
            val amplitude = (b.toFloat() + 128) / 256
            val barLength = radius * 0.4f * amplitude
            
            val angle = Math.toRadians(i * (360.0 / numBars))
            val startX = (centerX + radius * cos(angle)).toFloat()
            val startY = (centerY + radius * sin(angle)).toFloat()
            val stopX = (centerX + (radius + barLength) * cos(angle)).toFloat()
            val stopY = (centerY + (radius + barLength) * sin(angle)).toFloat()
            
            canvas.drawLine(startX, startY, stopX, stopY, paint)
        }
    }

    private fun drawParticles(canvas: Canvas, data: ByteArray) {
        var sum = 0f
        for (b in data) sum += abs(b.toFloat())
        val avg = sum / data.size / 128f
        
        for (p in particles) {
            if (p.x == 0f) p.reset(width.toFloat(), height.toFloat())
            
            p.update(avg)
            paint.alpha = (p.alpha * 255).toInt()
            canvas.drawCircle(p.x, p.y, p.size * (1 + avg), paint)
            
            if (p.alpha <= 0) p.reset(width.toFloat(), height.toFloat())
        }
        paint.alpha = 255
    }

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var alpha = 0f
        private val r = Random()

        fun reset(w: Float, h: Float) {
            x = w / 2
            y = h / 2
            val angle = r.nextFloat() * 2 * Math.PI.toFloat()
            val speed = r.nextFloat() * 5 + 2
            vx = cos(angle.toDouble()).toFloat() * speed
            vy = sin(angle.toDouble()).toFloat() * speed
            size = r.nextFloat() * 10 + 5
            alpha = 1.0f
        }

        fun update(energy: Float) {
            x += vx * (1 + energy * 2)
            y += vy * (1 + energy * 2)
            alpha -= 0.02f
        }
    }
}
