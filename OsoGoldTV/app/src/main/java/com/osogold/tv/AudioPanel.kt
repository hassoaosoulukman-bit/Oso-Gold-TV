package com.osogold.tv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player

/**
 * The luxury audio player of the Radio and Holy Quran sections. It controls the same background
 * player (Media3 service) the rest of the app uses, so the sound continues with the screen off.
 */
class AudioPanel(private val root: View, private val host: Host) {

    interface Host {
        fun controller(): Player?
        fun next()
        fun previous()
        fun toggleRecord()
        fun onMuteChanged(muted: Boolean)
        fun onClosed()
    }

    private val disc: View = root.findViewById(R.id.audioDisc)
    private val icon: ImageView = root.findViewById(R.id.audioIcon)
    private val title: TextView = root.findViewById(R.id.audioTitle)
    private val sub: TextView = root.findViewById(R.id.audioSub)
    private val recTimer: TextView = root.findViewById(R.id.audioRecTimer)
    private val seek: SeekBar = root.findViewById(R.id.audioSeek)
    private val pos: TextView = root.findViewById(R.id.audioPos)
    private val dur: TextView = root.findViewById(R.id.audioDur)
    private val btnMute: ImageView = root.findViewById(R.id.btnAudioMute)
    private val btnPrev: ImageView = root.findViewById(R.id.btnAudioPrev)
    private val btnPlay: ImageView = root.findViewById(R.id.btnAudioPlay)
    private val btnNext: ImageView = root.findViewById(R.id.btnAudioNext)
    private val btnRec: ImageView = root.findViewById(R.id.btnAudioRec)
    private val btnClose: ImageView = root.findViewById(R.id.btnAudioClose)

    private val handler = Handler(Looper.getMainLooper())
    private var spin: ObjectAnimator? = null
    private var dragging = false

    var muted = false
        private set

    val isVisible: Boolean
        get() = root.visibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500L)
        }
    }

    init {
        btnPlay.setOnClickListener {
            host.controller()?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
            refresh()
        }
        btnMute.setOnClickListener { setMuted(!muted) }
        btnPrev.setOnClickListener { host.previous() }
        btnNext.setOnClickListener { host.next() }
        btnRec.setOnClickListener { host.toggleRecord() }
        btnClose.setOnClickListener { hide() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) pos.text = format(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
                host.controller()?.seekTo((bar?.progress ?: 0).toLong())
            }
        })
    }

    /** [quran] = surah mode (shows the progress bar and the previous / next surah). */
    fun show(name: String, subtitle: String, quran: Boolean) {
        title.text = name
        sub.text = subtitle
        icon.setImageResource(if (quran) R.drawable.ic_quran else R.drawable.ic_radio)
        root.visibility = View.VISIBLE
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (spin == null) {
            spin = ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
                duration = 14_000L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        refresh()
    }

    fun hide() {
        handler.removeCallbacks(tick)
        spin?.cancel()
        spin = null
        root.visibility = View.GONE
        host.onClosed()
    }

    fun setTitle(name: String, subtitle: String) {
        title.text = name
        sub.text = subtitle
    }

    fun setMuted(value: Boolean) {
        muted = value
        btnMute.setImageResource(if (value) R.drawable.ic_mute else R.drawable.ic_volume)
        host.onMuteChanged(value)
    }

    fun resetMute() {
        muted = false
        btnMute.setImageResource(R.drawable.ic_volume)
    }

    /** Shows the running recording time (or hides it when [text] is null). */
    fun setRecording(text: String?) {
        if (text == null) {
            recTimer.visibility = View.INVISIBLE
            btnRec.setImageResource(R.drawable.ic_record)
        } else {
            recTimer.visibility = View.VISIBLE
            recTimer.text = "● $text"
            btnRec.setImageResource(R.drawable.ic_stop)
        }
    }

    private fun refresh() {
        val c = host.controller()
        if (c == null) {
            btnPlay.setImageResource(R.drawable.ic_play)
            return
        }
        val playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val s = spin
        if (s != null) {
            if (playing && s.isPaused) s.resume()
            if (!playing && s.isRunning) s.pause()
        }
        val duration = c.duration
        val known = duration > 0 && !c.isCurrentMediaItemLive
        seek.visibility = if (known) View.VISIBLE else View.INVISIBLE
        dur.text = if (known) format(duration) else "مباشر"
        if (known && !dragging) {
            seek.max = duration.toInt()
            seek.progress = c.currentPosition.toInt()
            pos.text = format(c.currentPosition)
        } else if (!known) {
            pos.text = ""
        }
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        spin?.cancel()
        spin = null
    }
}
