package com.osogold.tv

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Sleep timer: after the chosen time the playback is stopped, the playback service is stopped
 * and every open screen of the app is closed.
 */
object SleepTimer {

    const val ACTION_CLOSE = "com.osogold.tv.ACTION_SLEEP_CLOSE"

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var endElapsed = 0L

    val isActive: Boolean
        get() = runnable != null

    /** Minutes that are left (rounded up), 0 when no timer is running. */
    fun remainingMinutes(): Int {
        if (runnable == null) return 0
        val left = endElapsed - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    fun start(context: Context, minutes: Int) {
        cancel()
        val app = context.applicationContext
        val task = Runnable {
            runnable = null
            endElapsed = 0L
            PlaybackService.instance?.stopPlayback()
            app.stopService(Intent(app, PlaybackService::class.java))
            app.sendBroadcast(Intent(ACTION_CLOSE).setPackage(app.packageName))
        }
        runnable = task
        val delay = minutes * 60_000L
        endElapsed = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(task, delay)
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        endElapsed = 0L
    }
}
