package com.osogold.tv

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer (Media3). Because the player lives in a MediaSessionService it keeps playing
 * Quran / radio / streams in the background and with the screen locked (media notification).
 *
 * Supported formats (HLS m3u8 + TS, DASH mpd, MP4, MKV, MP3, AAC ...) are detected automatically.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // The User-Agent comes from Settings and is read for every new connection.
        val dataSourceFactory = NetConfig.dataSourceFactory(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // ---- anti-freeze & smart buffer -------------------------------------------------------
        // 20 seconds are buffered in advance (up to 40 s); the buffer is capped at 120 MB so even
        // 4K / 8K streams cannot exhaust the memory of the phone.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 40_000, 2_500, 5_000)
            .setTargetBufferBytes(120 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(10_000, true)
            .build()

        // ---- 8K / 4K / HEVC / AV1 with hardware acceleration -----------------------------------
        // MediaCodec hardware decoders are always preferred; software decoding is only a fallback.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // ---- ABR: automatic quality switching by network speed ---------------------------------
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setForceHighestSupportedBitrate(false)
                .build()
        }

        // ---- auto reconnect: every failed download is retried 12 times (with a growing delay) ----
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(12))

        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Pauses the playback (used when a trailer page is opened). */
    fun pausePlayback() {
        mediaSession?.player?.pause()
    }

    /** Stops the playback (used by the sleep timer). */
    fun stopPlayback() {
        mediaSession?.player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // The app was swiped away: keep playing only if something is really playing.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }
}
