package com.osogold.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touch control of the player:
 *  - swipe up / down on the RIGHT half  -> volume
 *  - swipe up / down on the LEFT half   -> screen brightness
 *  - simple tap                         -> play / pause
 */
class PlayerGestures(
    private val activity: Activity,
    private val indicator: TextView,
    private val onTap: () -> Unit
) : View.OnTouchListener {

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { indicator.visibility = View.GONE }

    private var rightSide = true
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var startVolume = 0
    private var startBrightness = 0.5f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                rightSide = event.x > view.width / 2f
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                startBrightness = currentBrightness()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = downY - event.y
                if (!moved && abs(dy) > slop && abs(dy) > abs(event.x - downX)) {
                    moved = true
                }
                if (moved) {
                    val fraction = dy / view.height.coerceAtLeast(1)
                    if (rightSide) changeVolume(fraction) else changeBrightness(fraction)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && hypot(event.x - downX, event.y - downY) <= slop) {
                    view.performClick()
                    onTap()
                }
                scheduleHide()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                scheduleHide()
                return true
            }
        }
        return false
    }

    private fun changeVolume(fraction: Float) {
        val target = (startVolume + fraction * maxVolume * 1.3f).roundToInt().coerceIn(0, maxVolume)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val percent = (target * 100f / maxVolume).roundToInt()
        show("🔊  $percent%")
    }

    private fun changeBrightness(fraction: Float) {
        val target = (startBrightness + fraction * 1.3f).coerceIn(0.02f, 1f)
        val params = activity.window.attributes
        params.screenBrightness = target
        activity.window.attributes = params
        show("☀  ${(target * 100f).roundToInt()}%")
    }

    private fun currentBrightness(): Float {
        val window = activity.window.attributes.screenBrightness
        if (window >= 0f) return window
        return try {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun show(text: String) {
        handler.removeCallbacks(hideRunnable)
        indicator.text = text
        indicator.visibility = View.VISIBLE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 700L)
    }
}
