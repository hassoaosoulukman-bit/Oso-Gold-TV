package com.osogold.tv

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Secondary player: LibVLC (built in). Media3 / ExoPlayer is the default player of the app; the
 * user can switch to LibVLC in Settings or with the engine button of the player at any time.
 *
 * LibVLC plays inside the activity (it is not part of the background service), so the guaranteed
 * background / locked screen playback belongs to the Media3 engine.
 */
class VlcEngine(context: Context) {

    private val appContext = context.applicationContext
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var attachedLayout: VLCVideoLayout? = null
    private var currentUrl: String? = null
    private var currentAgent: String = "IPTVSmartersPro"

    /** LibVLC options for the subtitle look (size / colour / position). Applied when the player is created. */
    var subtitleOptions: List<String> = emptyList()

    /** Called (main thread) when the stream cannot be played. */
    var onError: (() -> Unit)? = null

    /** Called (main thread) as soon as the picture starts. */
    var onPlaying: (() -> Unit)? = null

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    private fun ensure(): MediaPlayer {
        player?.let { return it }
        val options = arrayListOf("--no-drop-late-frames", "--no-skip-frames")
        options.addAll(subtitleOptions)
        val lib = LibVLC(appContext, options)
        val mp = MediaPlayer(lib)
        mp.setVolume(100)
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> onError?.invoke()
                MediaPlayer.Event.Playing -> onPlaying?.invoke()
                else -> Unit
            }
        }
        libVlc = lib
        player = mp
        return mp
    }

    /** Shows the video inside [layout] (the circle of the main screen or the full screen stage). */
    fun attach(layout: VLCVideoLayout?) {
        val mp = ensure()
        if (attachedLayout === layout) return
        if (attachedLayout != null) mp.detachViews()
        attachedLayout = layout
        if (layout != null) mp.attachViews(layout, null, false, false)
    }

    fun play(url: String, userAgent: String) {
        val mp = ensure()
        val lib = libVlc ?: return
        currentUrl = url
        currentAgent = userAgent
        val media = Media(lib, Uri.parse(url))
        media.addOption(":http-user-agent=$userAgent")
        // anti-freeze: a 5 s network buffer, automatic re-connection, hardware decoding (4K / 8K / HEVC / AV1)
        media.addOption(":network-caching=5000")
        media.addOption(":live-caching=5000")
        media.addOption(":http-reconnect")
        media.addOption(":avcodec-hw=any")
        media.setHWDecoderEnabled(true, false)
        mp.setMedia(media)
        media.release()
        mp.play()
    }

    /** Subtitle tracks of the running stream as (id, name). id -1 = off. */
    fun subtitleTracks(): List<Pair<Int, String>> {
        val tracks = player?.spuTracks ?: return emptyList()
        return tracks.map { it.id to it.name }
    }

    fun selectSubtitleTrack(id: Int) {
        player?.setSpuTrack(id)
    }

    /** Loads an external subtitle file (srt / ass / vtt ...) and selects it. */
    fun addSubtitle(uri: Uri) {
        player?.addSlave(Media.Slave.Type.Subtitle, uri, true)
    }

    /** New subtitle look: LibVLC reads these options only when it is created, so it is rebuilt. */
    fun applySubtitleOptions(options: List<String>) {
        subtitleOptions = options
        val layout = attachedLayout
        val url = currentUrl
        val agent = currentAgent
        if (player == null) return
        try {
            player?.stop()
            if (attachedLayout != null) player?.detachViews()
            player?.release()
            libVlc?.release()
        } catch (_: Exception) {
        }
        player = null
        libVlc = null
        attachedLayout = null
        if (layout != null) attach(layout)
        if (url != null) play(url, agent)
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun togglePlayPause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    fun stop() {
        currentUrl = null
        player?.stop()
    }

    /** Video shape: 0 fill screen, 1 cinema, 2 original, 3 stretch (same numbers as the stage). */
    fun applyAspect(mode: Int) {
        val mp = player ?: return
        mp.setVideoScale(
            when (mode) {
                0, 1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                else -> MediaPlayer.ScaleType.SURFACE_FILL
            }
        )
    }

    /** Video tracks of the running stream as (id, name). */
    fun videoTracks(): List<Pair<Int, String>> {
        val tracks = player?.videoTracks ?: return emptyList()
        return tracks.filter { it.id >= 0 }.map { it.id to it.name }
    }

    fun selectVideoTrack(id: Int) {
        player?.setVideoTrack(id)
    }

    /** Back to automatic quality: the stream is simply started again with the default choice. */
    fun resetQuality(userAgent: String) {
        val url = currentUrl ?: return
        play(url, userAgent)
    }

    fun release() {
        try {
            player?.stop()
            if (attachedLayout != null) player?.detachViews()
            player?.release()
            libVlc?.release()
        } catch (_: Exception) {
        }
        attachedLayout = null
        player = null
        libVlc = null
        currentUrl = null
    }
}
