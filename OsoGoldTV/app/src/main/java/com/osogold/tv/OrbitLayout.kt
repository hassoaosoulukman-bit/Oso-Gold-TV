package com.osogold.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Matrix
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
 * "Cosmic radar": glowing golden icons that travel evenly around the border of the big
 * rectangular player (the single child view). The player takes the whole layout minus the
 * margin used by the orbit; the orbit turns slowly by itself, can be dragged with a finger and a
 * tap on an icon selects it.
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
    private var iconR = 0f

    private val density = resources.displayMetrics.density
    private val painter = OrbPainter(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val trackPath = Path()
    private val measure = PathMeasure()
    private var trackLen = 1f
    private val point = FloatArray(2)
    private val playerRect = RectF()
    private val ringMatrix = Matrix()
    private var ringShader: SweepGradient? = null

    private var offset = 0f
    private var phase = 0f
    private var pulse = 0f
    private var lastNanos = 0L

    private var touching = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchAngle = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        color = 0x33FFD86B
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
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
        if (!touching) offset = (offset + dt * 16f * density) % trackLen
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

    private fun iconRadius(w: Int, h: Int): Float = min(w, h) * ICON_FRACTION

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val pad = (iconRadius(w, h) * PAD_FACTOR).toInt()
        val cw = MeasureSpec.makeMeasureSpec((w - 2 * pad).coerceAtLeast(1), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec((h - 2 * pad).coerceAtLeast(1), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val pad = (iconRadius(right - left, bottom - top) * PAD_FACTOR).toInt()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(pad, pad, pad + child.measuredWidth, pad + child.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        iconR = iconRadius(w, h)
        val pad = iconR * PAD_FACTOR
        playerRect.set(pad, pad, w - pad, h - pad)
        val centre = iconR * (PAD_FACTOR / 2f)
        val track = RectF(centre, centre, w - centre, h - centre)
        trackPath.reset()
        trackPath.addRoundRect(track, iconR, iconR, Path.Direction.CW)
        measure.setPath(trackPath, false)
        trackLen = measure.length.coerceAtLeast(1f)
        ringShader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(
                GoldStyle.GOLD_DEEP, GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP,
                GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP
            ),
            null
        )
        ringPaint.shader = ringShader
    }

    // ------------------------------------------------------------------ drawing

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // the orbit track (solid + dashed) and the glow around the player
        canvas.drawPath(trackPath, trackPaint)
        canvas.save()
        canvas.scale(1.025f, 1.025f, w / 2f, h / 2f)
        canvas.drawPath(trackPath, dashedPaint)
        canvas.restore()

        glowPaint.alpha = (60 + 80 * pulse).toInt()
        val corner = 8f * density
        canvas.drawRoundRect(playerRect, corner, corner, glowPaint)
        ringMatrix.setRotate(phase * 25f, w / 2f, h / 2f)
        ringShader?.setLocalMatrix(ringMatrix)
        canvas.drawRoundRect(playerRect, corner, corner, ringPaint)

        // orbit icons
        val count = items.size
        for (i in 0 until count) {
            val distance = (offset + i * trackLen / count) % trackLen
            measure.getPosTan(distance, point, null)
            positions[i * 2] = point[0]
            positions[i * 2 + 1] = point[1]
            painter.draw(canvas, point[0], point[1], iconR, icons[i], items[i].id == selectedId, pulse)
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
                    offset = ((offset + delta / 360f * trackLen) % trackLen + trackLen) % trackLen
                    lastTouchAngle = a
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                if (!dragging) handleTap(event.x, event.y)
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
            if (hypot(dx, dy) <= iconR * 1.4f) {
                performClick()
                onItemClick?.invoke(items[i])
                return
            }
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun angleOf(x: Float, y: Float, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    companion object {
        /** Radius of an orbit icon relative to the smaller side of this layout. */
        const val ICON_FRACTION = 0.062f

        /** Width of the orbit lane around the player, in icon radii. */
        const val PAD_FACTOR = 2.5f
    }
}
