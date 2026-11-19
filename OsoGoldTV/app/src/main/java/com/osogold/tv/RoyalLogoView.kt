package com.osogold.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated royal "Oso Gold TV" logo: glowing golden rings that rotate in opposite directions,
 * a crown in the middle and the golden name underneath.
 */
class RoyalLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var showText = true
    private val crown = ContextCompat.getDrawable(context, R.drawable.ic_crown)!!.mutate()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xAAFFD86B.toInt(), 0x44D4AF37, 0x00000000),
            floatArrayOf(0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFF3A2B0A.toInt(), 0xFF120D04.toInt(), 0xFF050403.toInt()),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = GoldStyle.GOLD
    }
    private val dashPaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = GoldStyle.GOLD_LIGHT
        pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
    }
    private val dashPaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0xAAD4AF37.toInt()
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 9f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = GoldStyle.GOLD_LIGHT
    }

    private var angle = 0f
    private var pulse = 0f
    private var lastNanos = 0L
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.1f)
            lastNanos = now
            phase += dt
            angle = (angle + dt * 28f) % 360f
            pulse = 0.5f + 0.5f * sin(phase * 2.2f)
            invalidate()
        }
    }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.RoyalLogoView)
            showText = a.getBoolean(R.styleable.RoyalLogoView_rlShowText, true)
            a.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val textWidth = w * 0.8f
        textPaint.shader = LinearGradient(
            (w - textWidth) / 2f, 0f, (w + textWidth) / 2f, 0f,
            intArrayOf(GoldStyle.GOLD_DEEP, GoldStyle.GOLD_LIGHT, 0xFFFFF2C2.toInt(), GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP),
            null,
            Shader.TileMode.CLAMP
        )
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
        val w = width.toFloat()
        val h = height.toFloat()
        val textSpace = if (showText) h * 0.2f else 0f
        val areaH = h - textSpace
        val r = min(w / 2f, areaH / 2f) * 0.78f
        val cx = w / 2f
        val cy = areaH / 2f

        // glow
        glowPaint.alpha = (150 + 105 * pulse).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r * 1.38f, r * 1.38f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()

        // disc
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, discPaint)
        canvas.restore()

        // rings
        canvas.drawCircle(cx, cy, r, ringPaint)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.88f, dashPaintA)
        canvas.restore()

        canvas.save()
        canvas.rotate(-angle * 1.6f, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.74f, dashPaintB)
        canvas.restore()

        // crown
        val half = (r * 0.46f).toInt()
        crown.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        crown.setTint(GoldStyle.GOLD_LIGHT)
        crown.draw(canvas)

        // name
        if (showText) {
            textPaint.textSize = textSpace * 0.62f
            val baseline = areaH + textSpace * 0.7f
            canvas.drawText("Oso Gold TV", cx, baseline, textPaint)
        }
    }
}
