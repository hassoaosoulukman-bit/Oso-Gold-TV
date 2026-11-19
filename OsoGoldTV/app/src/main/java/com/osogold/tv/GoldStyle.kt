package com.osogold.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/** Colours of the black / royal gold design. */
object GoldStyle {
    const val GOLD = 0xFFD4AF37.toInt()
    const val GOLD_LIGHT = 0xFFFFD86B.toInt()
    const val GOLD_DEEP = 0xFF9A7B1E.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val PANEL = 0xFF0C0A05.toInt()
}

/**
 * Draws the one and only icon style of the app: a dark disc with a glowing golden ring and a
 * golden line icon in the middle. The orbit icons, the buttons on the main screen and the
 * icons of the settings screen all use this painter, so they look exactly the same.
 */
class OrbPainter(context: Context) {

    private val density = context.resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xAAFFD86B.toInt(), 0x44D4AF37, 0x00000000),
            floatArrayOf(0.55f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFF3A2B0A.toInt(), 0xFF140E04.toInt(), 0xFF080603.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        shader = SweepGradient(
            0f, 0f,
            intArrayOf(
                GoldStyle.GOLD_DEEP, GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP,
                GoldStyle.GOLD_LIGHT, GoldStyle.GOLD_DEEP
            ),
            null
        )
    }

    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66FFD86B
    }

    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0xFFFFE9A6.toInt()
    }

    /**
     * @param cx,cy  centre of the orb in the coordinates of [canvas]
     * @param r      radius of the disc (the glow reaches about 1.6 * r)
     * @param icon   line icon (tinted here)
     * @param active true for the selected / pressed item (brighter glow + extra ring)
     * @param pulse  0..1, makes the glow breathe
     * @param pressed true while a finger is on the item (slightly smaller)
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        icon: Drawable?,
        active: Boolean,
        pulse: Float,
        pressed: Boolean = false
    ) {
        val radius = if (pressed) r * 0.93f else r
        canvas.save()
        canvas.translate(cx, cy)

        // glow
        val base = if (active) 255f else 185f
        glowPaint.alpha = (base * (0.72f + 0.28f * pulse)).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.scale(radius * 1.6f, radius * 1.6f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()

        // dark disc
        canvas.save()
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, fillPaint)
        canvas.restore()

        // rings
        canvas.drawCircle(0f, 0f, radius, ringPaint)
        canvas.drawCircle(0f, 0f, radius - 5f * density, innerRingPaint)
        if (active) {
            canvas.drawCircle(0f, 0f, radius + 4f * density, activeRingPaint)
        }

        // icon
        if (icon != null) {
            val half = (radius * 0.52f).toInt()
            icon.setBounds(-half, -half, half, half)
            icon.setTint(if (active) GoldStyle.GOLD_LIGHT else GoldStyle.GOLD)
            icon.draw(canvas)
        }
        canvas.restore()
    }
}
