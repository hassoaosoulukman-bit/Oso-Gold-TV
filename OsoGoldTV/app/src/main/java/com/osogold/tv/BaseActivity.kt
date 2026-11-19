package com.osogold.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base class of every screen:
 *  - forces landscape
 *  - permanent immersive full screen (IMMERSIVE_STICKY): no status bar and no navigation buttons
 *    for the whole time the app is running. When a swipe from the screen edge shows the bars for
 *    a moment, they are hidden again automatically.
 *  - dialogs are shown without ever bringing the system bars back ([showImmersive])
 *  - closes itself when the sleep timer ends
 */
abstract class BaseActivity : AppCompatActivity() {

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishAndRemoveTask()
        }
    }
    private var receiverRegistered = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val rehideRunnable = Runnable { enterImmersive() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }
        enterImmersive()

        // If the system bars appear for any reason (edge swipe, keyboard, dialog) hide them again.
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                uiHandler.removeCallbacks(rehideRunnable)
                uiHandler.postDelayed(rehideRunnable, 1200L)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                closeReceiver,
                IntentFilter(SleepTimer.ACTION_CLOSE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(closeReceiver)
            receiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(rehideRunnable)
        super.onDestroy()
    }

    /** IMMERSIVE_STICKY: hides status bar + navigation bar; a swipe only shows them transiently. */
    @Suppress("DEPRECATION")
    fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.systemUiVisibility = IMMERSIVE_FLAGS
    }

    /**
     * Shows a dialog in immersive mode. The window is first shown without focus so that the
     * system bars never flash, then the immersive flags are applied and the focus is given back.
     */
    @Suppress("DEPRECATION")
    fun showImmersive(dialog: AlertDialog) {
        val dialogWindow = dialog.window
        dialogWindow?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        dialog.setOnDismissListener { enterImmersive() }
        dialog.show()
        dialogWindow?.let { w ->
            w.decorView.systemUiVisibility = IMMERSIVE_FLAGS
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        @Suppress("DEPRECATION")
        const val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}
