package com.osogold.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Full screen welcome video (any format supported by Media3) with a "Skip" button.
 *
 * Video source, in this order:
 *   1. the video chosen / recorded in Settings
 *   2. a file  res/raw/welcome.*  if you put one into the project
 *   3. otherwise an animated royal intro that also lasts 30 seconds
 */
class WelcomeVideoActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var fallbackRoot: View
    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { goMain() }
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        playerView = findViewById(R.id.welcomePlayer)
        fallbackRoot = findViewById(R.id.fallbackRoot)
        findViewById<TextView>(R.id.btnSkip).setOnClickListener { goMain() }

        val prefs = Prefs(this)
        val file = prefs.welcomeVideoFile()
        if (file != null) {
            startVideo(Uri.fromFile(file))
        } else {
            val rawId = resources.getIdentifier("welcome", "raw", packageName)
            if (rawId != 0) {
                startVideo(Uri.parse("android.resource://$packageName/$rawId"))
            } else {
                startFallback()
            }
        }
    }

    private fun startVideo(uri: Uri) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) goMain()
            }

            override fun onPlayerError(error: PlaybackException) {
                // the chosen file cannot be played -> show the royal intro instead
                exo.release()
                player = null
                playerView.player = null
                startFallback()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun startFallback() {
        playerView.visibility = View.GONE
        fallbackRoot.visibility = View.VISIBLE
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, FALLBACK_MILLIS)
    }

    private fun goMain() {
        if (finished) return
        finished = true
        handler.removeCallbacks(finishRunnable)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        /** Length of the built-in royal intro (the requirement: at least 30 seconds). */
        private const val FALLBACK_MILLIS = 30_000L
    }
}
