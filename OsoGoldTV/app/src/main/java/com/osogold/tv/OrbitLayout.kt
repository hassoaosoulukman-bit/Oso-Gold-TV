package com.osogold.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * "Cosmic radar": a rotating orbit of glowing golden icons around the centre.
 *
 * The (single) child view is the circular video player; it is centred and sized by this layout.
 * The orbit turns slowly by itself, can be dragged with a finger, and a tap on an icon selects
 * the section.
 */
class OrbitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    data class Item(val id: String, val label: String, val iconRes: Int)

    var onItemClick: ((Item) -> Unit)? = null

    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            icons = value.map { item ->
                ContextCompat.getDrawable(context, item.iconRes)!!.mutate()
            }
            positions = FloatArray(value.size * 2)
            invalidate()
        }

    private var icons: List<Drawable> = emptyList()
    private var positions = FloatArray(0)
    private var lastIconRadius = 0f

    private val density = resources.displayMetrics.density
    private val painter = OrbPainter(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var baseAngle = 0f
    private var sweepAngle = 0f
    private var phase = 0f
    private var pulse = 0f
    private var lastNanos = 0L

    private var touching = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchAngle = 0f

    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x88D4AF37.toInt()
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x55D4AF37
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 8f * density), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x22D4AF37
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xCCFFD86B.toInt(), 0x55D4AF37, 0x00000000),
            floatArrayOf(0.66f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val playerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GoldStyle.GOLD_LIGHT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 9.5f * density
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        lastNanos = now
        phase += dt
        pulse = 0.5f + 0.5f * sin(phase * 2f)
        sweepAngle = (sweepAngle + dt * 70f) % 360f
        if (!touching) baseAngle = (baseAngle + dt * 8f) % 360f
        invalidate()
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

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val diameter = (min(w, h) * PLAYER_FRACTION).toInt()
        val spec = MeasureSpec.makeMeasureSpec(diameter, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(spec, spec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val l = (w - child.measuredWidth) / 2
            val t = (h - child.measuredHeight) / 2
            child.layout(l, t, l + child.measuredWidth, t + child.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(0x00D4AF37, 0x00D4AF37, 0x55FFD86B),
            floatArrayOf(0f, 0.8f, 1f)
        )
        playerRingPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                GoldStyle.GOLD_DEEP, GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP,
                GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP
            ),
            null
        )
        nebulaPaint.shader = RadialGradient(
            cx, cy, min(w, h) * 0.5f,
            intArrayOf(0x00000000, 0x22D4AF37, 0x00000000),
            floatArrayOf(0.55f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ drawing

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val m = min(w, h)
        val orbitR = m * 0.40f
        val iconR = m * 0.072f
        val playerR = m * PLAYER_FRACTION / 2f
        lastIconRadius = iconR

        // nebula
        canvas.drawCircle(cx, cy, m * 0.5f, nebulaPaint)

        // radar rings + cross hair
        canvas.drawCircle(cx, cy, orbitR, orbitPaint)
        canvas.drawCircle(cx, cy, orbitR * 0.70f, dashedPaint)
        canvas.drawCircle(cx, cy, orbitR * 1.12f, dashedPaint)
        canvas.drawLine(cx - orbitR * 1.12f, cy, cx + orbitR * 1.12f, cy, crossPaint)
        canvas.drawLine(cx, cy - orbitR * 1.12f, cx, cy + orbitR * 1.12f, crossPaint)

        // radar sweep
        canvas.save()
        canvas.rotate(sweepAngle, cx, cy)
        canvas.drawCircle(cx, cy, orbitR + iconR * 0.4f, sweepPaint)
        canvas.restore()

        // glow + ring around the player
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(playerR * 1.32f, playerR * 1.32f)
        playerGlowPaint.alpha = (170 + 85 * pulse).toInt()
        canvas.drawCircle(0f, 0f, 1f, playerGlowPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(phase * 25f, cx, cy)
        canvas.drawCircle(cx, cy, playerR + 2f * density, playerRingPaint)
        canvas.restore()

        // orbit icons
        val count = items.size
        for (i in 0 until count) {
            val angle = Math.toRadians((baseAngle + i * 360.0 / count - 90.0))
            val x = cx + orbitR * cos(angle).toFloat()
            val y = cy + orbitR * sin(angle).toFloat()
            positions[i * 2] = x
            positions[i * 2 + 1] = y

            val item = items[i]
            painter.draw(canvas, x, y, iconR, icons[i], item.id == selectedId, pulse)

            // label drawn towards the centre so it never leaves the view
            val labelRadius = orbitR - iconR - 9f * density
            val lx = cx + labelRadius * cos(angle).toFloat()
            val ly = cy + labelRadius * sin(angle).toFloat() + labelPaint.textSize / 3f
            canvas.drawText(item.label, lx, ly, labelPaint)
        }

        // the player itself
        super.dispatchDraw(canvas)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragging = false
                downX = event.x
                downY = event.y
                lastTouchAngle = angleOf(event.x, event.y, cx, cy)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    val a = angleOf(event.x, event.y, cx, cy)
                    var delta = a - lastTouchAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    baseAngle += delta
                    lastTouchAngle = a
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                if (!dragging) {
                    handleTap(event.x, event.y)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        for (i in items.indices) {
            val dx = x - positions[i * 2]
            val dy = y - positions[i * 2 + 1]
            if (hypot(dx, dy) <= lastIconRadius * 1.4f) {
                performClick()
                onItemClick?.invoke(items[i])
                return
            }
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun angleOf(x: Float, y: Float, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    companion object {
        /** Diameter of the circular player relative to the smaller side of this layout. */
        const val PLAYER_FRACTION = 0.44f
    }
}
