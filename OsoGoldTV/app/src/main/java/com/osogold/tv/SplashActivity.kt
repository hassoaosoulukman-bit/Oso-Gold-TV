package com.osogold.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Royal animated splash screen (2 seconds), then:
 *   not logged in yet  ->  password screen  ->  welcome video  ->  main screen
 *   already logged in  ->  welcome video  ->  main screen
 */
class SplashActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val proceedRunnable = Runnable { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.splashLogo)
        logo.alpha = 0f
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1000L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        handler.postDelayed(proceedRunnable, SPLASH_MILLIS)
    }

    private fun proceed() {
        val prefs = Prefs(this)
        val target = when {
            !prefs.loggedIn -> PasswordActivity::class.java
            Prefs.WELCOME_EVERY_LAUNCH -> WelcomeVideoActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(proceedRunnable)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MILLIS = 2000L
    }
}
