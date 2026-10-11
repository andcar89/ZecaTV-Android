package com.ze.iptvplayer.cast

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType

/**
 * Turns a playlist entry into what a Chromecast can actually play. The Chromecast fetches the
 * stream itself, and the stock receiver handles HLS (.m3u8) and MP4 well but NOT a raw MPEG-TS
 * live stream — which is what most IPTV panels serve for "…/user/pass/12345(.ts)". Those panels
 * almost always serve the same channel as HLS at the same address with ".m3u8", so Xtream-style
 * live URLs are rewritten that way.
 */
object CastMedia {

    private val xtreamLive = Regex("^(https?://[^/]+/(?:live/)?[^/]+/[^/]+/\\d+)(?:\\.ts)?(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun castUrl(channel: Channel): String {
        if (channel.contentType != ContentType.LIVE) return channel.streamUrl
        val m = xtreamLive.find(channel.streamUrl) ?: return channel.streamUrl
        return m.groupValues[1] + ".m3u8" + m.groupValues[2]
    }

    private fun mimeFor(url: String, live: Boolean, original: Boolean = false): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            live && original -> MimeTypes.VIDEO_MP2T // raw panel URL without extension = MPEG-TS
            live -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.VIDEO_MP4
        }
    }

    /** [original] = the playlist's own URL untouched (fallback when the HLS rewrite is refused). */
    fun mediaItem(channel: Channel, original: Boolean = false): MediaItem {
        val url = if (original) channel.streamUrl else castUrl(channel)
        android.util.Log.i("ZecaCast", "Casting ${channel.name}: $url (original=$original)")
        val metadata = MediaMetadata.Builder().setTitle(channel.name).apply {
            channel.logoUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
        }.build()
        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeFor(url, channel.contentType == ContentType.LIVE, original))
            .setMediaMetadata(metadata)
            .build()
    }
}
