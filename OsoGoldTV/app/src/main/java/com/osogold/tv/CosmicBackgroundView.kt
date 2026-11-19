package com.osogold.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

/** Black / gold cosmic background: deep radial gradient with slowly twinkling golden stars. */
class CosmicBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Star(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float)

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GoldStyle.GOLD_LIGHT }
    private val stars = ArrayList<Star>()
    private var time = 0f
    private var lastNanos = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.1f)
            lastNanos = now
            time += dt
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundPaint.shader = RadialGradient(
            w / 2f, h / 2f, maxOf(w, h) * 0.75f,
            intArrayOf(0xFF2A200A.toInt(), 0xFF0B0803.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        stars.clear()
        val random = Random(7)
        val count = ((w * h) / (60f * 60f * density * density) * 9f).toInt().coerceIn(40, 160)
        repeat(count) {
            stars.add(
                Star(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    size = (0.6f + random.nextFloat() * 1.6f) * density,
                    phase = random.nextFloat() * 6.28f,
                    speed = 0.6f + random.nextFloat() * 1.6f
                )
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        for (star in stars) {
            val twinkle = 0.25f + 0.75f * abs(sin(time * star.speed + star.phase))
            starPaint.alpha = (twinkle * 210f).toInt()
            canvas.drawCircle(star.x, star.y, star.size, starPaint)
        }
    }
}
