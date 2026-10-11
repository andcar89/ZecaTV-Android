package com.ze.iptvplayer.player

import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.MediaStatus
import android.util.Log
import android.content.Context
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.framework.CastContext
import com.ze.iptvplayer.cast.CastMedia
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.network.LenientTls

/**
 * Owns the app's players: one local ExoPlayer shared between the Live tab's mini-preview and the
 * fullscreen player, and — when a Chromecast session is running — a CastPlayer that takes over.
 *
 * Local: [playChannel] is a no-op if the shared player is already playing that exact channel, so
 * previewing channel X and then opening it fullscreen hands the SAME live connection over instead
 * of restarting it. [attach]/[detach] bind a screen's PlayerView without touching playback; only
 * [release] actually tears the local player down.
 *
 * Cast: when a session becomes available the current channel moves to the Chromecast (the phone
 * stops its own stream so the provider doesn't see two connections); when it ends, playback comes
 * back to the phone, paused. Screens ask [active] for the player to drive and listen with
 * [addCastListener] to rebind when it switches.
 */
object PlayerHolder {
    private const val TAG = "ZecaCast"

    private var player: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var appContext: Context? = null
    private var castFailed = false
    private val castListeners = mutableSetOf<() -> Unit>()

    var currentChannel: Channel? = null
        private set

    /** True while a Chromecast session is the active output. */
    @Volatile
    var casting: Boolean = false
        private set

    /** Set when the Chromecast reports it could not play the current stream; null otherwise. */
    @Volatile
    var castError: String? = null
        private set
    private var triedOriginalUrl = false
    /** A live channel was opened while casting: when the session ends, start it on the phone. */
    private var resumeOnLocal = false

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return
            when (status.playerState) {
                MediaStatus.PLAYER_STATE_IDLE ->
                    if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) onCastLoadFailed()
                MediaStatus.PLAYER_STATE_PLAYING,
                MediaStatus.PLAYER_STATE_BUFFERING,
                MediaStatus.PLAYER_STATE_PAUSED ->
                    if (castError != null) { castError = null; notifyCastChanged() }
            }
        }
    }

    /** True while the fullscreen PlayerActivity is on screen (set by that activity). The main
     *  screen checks this when it stops, so it only pauses playback when the app is really going
     *  to the background — not when it merely hands the player over to the fullscreen screen. */
    @Volatile
    var fullscreenOpen: Boolean = false

    private fun getOrCreate(context: Context): ExoPlayer {
        player?.let { return it }

        // LenientTls tolerates the broken cert chains common on IPTV panels, and the tighter
        // buffer durations trade a little rebuffering risk for a faster start.
        val dataSourceFactory = OkHttpDataSource.Factory(LenientTls.newClientBuilder().build())
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) IptvPlayer/1.0")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1000,
                2000
            )
            .build()

        val newPlayer = ExoPlayer.Builder(context.applicationContext, Mp2RenderersFactory(context.applicationContext))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        newPlayer.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onTracksChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        Log.i("ZecaAudio", "${currentChannel?.name}: audio ${f.sampleMimeType} codecs=${f.codecs} ch=${f.channelCount} " +
                            "supported=${group.isTrackSupported(i)} selected=${group.isTrackSelected(i)}")
                    }
                }
            }
            override fun onPlayerError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
                Log.e("ZecaAudio", "Playback error for ${currentChannel?.name}: ${error.errorCodeName} / ${error.cause}", error)
            }
            override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) {
                Log.e("ZecaAudio", "Audio codec error: $audioCodecError")
            }
            override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
                Log.e("ZecaAudio", "Audio sink error: $audioSinkError")
            }
        })
        player = newPlayer
        return newPlayer
    }

    // ---- Chromecast ------------------------------------------------------------------------

    /** Sets up the Cast SDK once. Returns false when Cast isn't usable here (no Google Play
     *  services, SDK error) — callers then simply hide their Cast buttons. */
    fun initCast(context: Context): Boolean {
        if (castContext != null) return true
        if (castFailed) return false
        return try {
            val app = context.applicationContext
            appContext = app
            @Suppress("DEPRECATION")
            val ctx = CastContext.getSharedInstance(app)
            val cast = CastPlayer(ctx)
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchToCast()
                override fun onCastSessionUnavailable() = switchToLocal()
            })
            castContext = ctx
            castPlayer = cast
            if (cast.isCastSessionAvailable) switchToCast()
            true
        } catch (t: Throwable) {
            castFailed = true
            false
        }
    }

    /** Name of the Chromecast being used, or null. */
    fun castDeviceName(): String? =
        castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    /** Ends the Cast session; playback returns to the phone (paused). */
    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun addCastListener(listener: () -> Unit) { castListeners.add(listener) }
    fun removeCastListener(listener: () -> Unit) { castListeners.remove(listener) }
    private fun notifyCastChanged() = castListeners.toList().forEach { it() }

    /** The receiver refused the stream. First retry with the playlist's own URL (the ".m3u8" rewrite
     *  may not exist on this panel); if that fails too, tell the user instead of failing silently. */
    private fun onCastLoadFailed() {
        val cast = castPlayer ?: return
        val channel = currentChannel ?: return
        Log.e(TAG, "Chromecast could not play ${CastMedia.castUrl(channel)} (triedOriginal=$triedOriginalUrl)")
        if (!triedOriginalUrl && CastMedia.castUrl(channel) != channel.streamUrl) {
            triedOriginalUrl = true
            cast.setMediaItem(CastMedia.mediaItem(channel, original = true), C.TIME_UNSET)
            cast.prepare()
            cast.playWhenReady = true
            return
        }
        if (castError == null) {
            castError = "failed"
            notifyCastChanged()
        }
    }

    private fun resetCastError() {
        triedOriginalUrl = false
        castError = null
    }

    private fun switchToCast() {
        val cast = castPlayer ?: return
        if (casting) return
        val channel = currentChannel
        if (channel != null) {
            val live = channel.contentType == ContentType.LIVE
            val local = player
            val position = if (live || local == null) C.TIME_UNSET else local.currentPosition.coerceAtLeast(0L)
            // The phone stops its own stream — most providers allow one connection per account.
            local?.stop()
            resetCastError()
            cast.setMediaItem(CastMedia.mediaItem(channel), position)
            cast.prepare()
            cast.playWhenReady = true
        }
        casting = true
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.registerCallback(mediaCallback)
        notifyCastChanged()
    }

    private fun switchToLocal() {
        if (!casting) return
        val cast = castPlayer
        val channel = currentChannel
        val context = appContext
        val live = channel?.contentType == ContentType.LIVE
        val position = if (live || cast == null) 0L else cast.currentPosition.coerceAtLeast(0L)
        casting = false
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.unregisterCallback(mediaCallback)
        resetCastError()
        if (channel != null && context != null) {
            val local = getOrCreate(context)
            local.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)), position)
            local.prepare()
            local.playWhenReady = resumeOnLocal // only start by itself when a live channel was opened
            resumeOnLocal = false
        }
        notifyCastChanged()
    }

    // ---- Playback --------------------------------------------------------------------------

    /** Returns the local player, creating it if needed. Always the ExoPlayer, even while casting. */
    fun ensurePlayer(context: Context): ExoPlayer = getOrCreate(context)

    /** The player screens should drive and listen to: the CastPlayer while casting, else the local one. */
    fun active(context: Context): Player = if (casting) (castPlayer ?: getOrCreate(context)) else getOrCreate(context)

    /** Starts playing [channel] on whichever player is active. A no-op if it's already the current
     *  channel (matched by [Channel.key], the stream URL), preserving the connection/buffer.
     *  [startPositionMs] is where to begin a movie/episode (resume); live channels pass 0. */
    fun playChannel(context: Context, channel: Channel, startPositionMs: Long = 0L) {
        if (currentChannel?.key == channel.key) return
        currentChannel = channel
        val cast = castPlayer
        if (casting && cast != null && channel.contentType == ContentType.LIVE) {
            // Live TV is not castable: end the Cast session and let the phone play it.
            resumeOnLocal = true
            stopCasting()
            return
        }
        if (casting && cast != null) {
            val live = channel.contentType == ContentType.LIVE
            resetCastError()
            cast.setMediaItem(CastMedia.mediaItem(channel), if (live) C.TIME_UNSET else startPositionMs)
            cast.prepare()
            cast.playWhenReady = true
            return
        }
        val exoPlayer = getOrCreate(context)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)), startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Binds the active player to [playerView] so it renders there. */
    fun attach(context: Context, playerView: PlayerView) {
        playerView.player = active(context)
    }

    /** Unbinds whatever player is bound to [playerView] WITHOUT stopping playback. */
    fun detach(playerView: PlayerView) {
        playerView.player = null
    }

    /** Pauses the phone's own playback (app going to the background, leaving a tab). A running
     *  Chromecast session is deliberately left alone. */
    fun pause() {
        player?.playWhenReady = false
    }

    /** Re-attempts the current stream, e.g. after an error. */
    fun retryCurrent() {
        val p: Player = (if (casting) castPlayer else player) ?: return
        if (casting) resetCastError()
        p.prepare()
        p.playWhenReady = true
    }

    /** Stops local playback and tears the local player down. A running Cast session keeps its media. */
    fun release() {
        player?.release()
        player = null
        if (!casting) currentChannel = null
    }
}
