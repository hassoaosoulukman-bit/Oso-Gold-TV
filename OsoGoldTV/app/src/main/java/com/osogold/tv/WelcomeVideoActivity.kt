package com.osogold.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Full screen welcome video (any format supported by Media3) with a "Skip" button.
 *
 * Sound: ONLY the sound that is built into the video itself is played, at normal volume and
 * without interruption. The app never plays any separate music or song. Before the video
 * starts, anything that is still playing in the background (a stream left over from the last
 * session) is stopped, and the video player takes the audio focus so nothing else can mix in.
 *
 * Video source, in this order:
 *   1. the video chosen / recorded in Settings
 *   2. a file  res/raw/welcome.*  if you put one into the project
 *   3. otherwise a silent animated royal intro that lasts 30 seconds
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

        // No other sound may play while the welcome video runs.
        PlaybackService.instance?.stopPlayback()
        stopService(Intent(this, PlaybackService::class.java))

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
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exo = ExoPlayer.Builder(this)
            // true = the video owns the audio focus, so no other sound can interrupt or mix in
            .setAudioAttributes(attributes, true)
            .build()
        exo.volume = 1f // the original sound of the video, at its natural level
        exo.repeatMode = Player.REPEAT_MODE_OFF
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
        player?.stop()
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
