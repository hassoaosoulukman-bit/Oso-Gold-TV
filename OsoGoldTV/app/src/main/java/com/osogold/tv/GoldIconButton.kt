package com.osogold.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Circular golden glowing icon button with a small label underneath.
 * Used on the main screen and in the settings screen (one single icon style everywhere).
 */
class GoldIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val painter = OrbPainter(context)
    private val density = resources.displayMetrics.density
    private var icon: Drawable? = null

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Highlights the button (used by the favourites button while favourites are shown). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GoldStyle.GOLD_LIGHT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.GoldIconButton)
            val iconRes = a.getResourceId(R.styleable.GoldIconButton_gbIcon, 0)
            if (iconRes != 0) {
                icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
            }
            label = a.getString(R.styleable.GoldIconButton_gbLabel) ?: ""
            a.recycle()
        }
        isClickable = true
        isFocusable = true
        contentDescription = label
    }

    fun setIcon(resId: Int) {
        icon = ContextCompat.getDrawable(context, resId)?.mutate()
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val labelHeight = if (label.isEmpty()) 0f else 16f * density
        labelPaint.textSize = (if (w < 90f * density) 9.5f else 12f) * density

        val orbArea = h - labelHeight
        val r = min(w / 2f, orbArea / 2f) * 0.62f
        val cx = w / 2f
        val cy = orbArea / 2f

        painter.draw(canvas, cx, cy, r, icon, active, 0.5f, isPressed)

        if (label.isNotEmpty()) {
            val baseline = h - 4f * density
            canvas.drawText(label, cx, baseline, labelPaint)
        }
    }
}
