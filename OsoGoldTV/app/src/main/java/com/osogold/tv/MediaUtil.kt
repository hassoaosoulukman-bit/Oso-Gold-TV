package com.osogold.tv

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/** Builds the Media3 item of a channel. HLS and DASH are detected from the address. */
fun buildMediaItem(channel: Channel, url: String): MediaItem {
    val lower = url.lowercase()
    val mime = when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
    return MediaItem.Builder()
        .setUri(url)
        .setMimeType(mime)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(channel.name)
                .setArtist(channel.group)
                .build()
        )
        .build()
}
