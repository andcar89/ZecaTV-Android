package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.SeriesIndex
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.data.WatchProgress
import com.ze.iptvplayer.databinding.ActivityPlayerBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.player.PlayerHolder
import com.ze.iptvplayer.ui.ChannelAdapter
import com.ze.iptvplayer.util.ChannelLogos
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fullscreen landscape player for live channels and movies/episodes.
 *
 * It shares the single ExoPlayer in [PlayerHolder] with the Live tab's mini-player, so opening
 * fullscreen from a previewed channel continues the same connection instead of restarting it.
 *
 * Touch: tap shows/hides the controls; vertical swipe on the left half changes brightness, on the
 * right half volume; swipe left from the right edge (live) opens the channel panel; double-tap
 * seeks ±10 s on movies. Picture-in-picture is entered via the button or when leaving the app.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHANNEL_KEY = "extra_channel_key"
        private const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val CONTROLS_TIMEOUT_MS = 4000L

        private const val EXTRA_START_OVER = "extra_start_over"

        fun start(context: Context, channel: Channel, categoryId: String, startOver: Boolean = false) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_CHANNEL_KEY, channel.key)
                    .putExtra(EXTRA_CATEGORY_ID, categoryId)
                    .putExtra(EXTRA_START_OVER, startOver)
            )
        }
    }

    private data class TrackOption(
        val id: String,
        val label: String,
        val group: Tracks.Group?,
        val trackIndexInGroup: Int
    )

    private enum class ScrollMode { NONE, BRIGHTNESS, VOLUME, PANEL, IGNORE }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var channel: Channel
    private var channels: List<Channel> = emptyList()
    private var panelCategoryId = PlaylistRepository.CATEGORY_ALL
    private var isLive = true

    private var player: Player? = null
    private val handler = Handler(Looper.getMainLooper())

    private var controlsVisible = false
    private var panelVisible = false
    private var inPip = false
    private var hasError = false
    private var userSeeking = false
    private var resizeIndex = 0
    private var speedIndex = 1
    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var tick = 0

    private var audioOptions: List<TrackOption> = emptyList()
    private var subtitleOptions: List<TrackOption> = emptyList()
    private var selectedAudioId: String? = null
    private var selectedSubId: String = "off"

    private lateinit var panelAdapter: ChannelAdapter
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private var scrollMode = ScrollMode.NONE
    private var volumeValue = 0f
    private var brightnessValue = 0.5f

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideHintRunnable = Runnable { binding.gestureHint.visibility = View.GONE }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            hasError = true
            binding.buffering.visibility = View.GONE
            binding.errorText.visibility = View.VISIBLE
            showControls()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !isLive) onVodEnded()
            binding.buffering.isVisible = playbackState == Player.STATE_BUFFERING && !hasError
            if (playbackState == Player.STATE_READY) {
                hasError = false
                binding.errorText.visibility = View.GONE
            }
            updatePlayIcon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
            if (isPlaying) scheduleHideControls()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayIcon()

        override fun onTracksChanged(tracks: Tracks) = refreshTrackOptions(tracks)

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateSubtitleLine()
    }

    private val castListener: () -> Unit = {
        runOnUiThread {
            if (::channel.isInitialized && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (player !== PlayerHolder.active(this)) rebindPlayer() else updateCastOverlay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this, player = true)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
        val found = PlaylistRepository.channels.firstOrNull { it.key == key }
        if (found == null) {
            finish()
            return
        }
        channel = found
        isLive = channel.contentType == ContentType.LIVE
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: PlaylistRepository.CATEGORY_ALL
        channels = if (isLive) buildChannelList(categoryId) else listOf(channel)
        panelCategoryId = if (isLive &&
            PlaylistRepository.channelsForCategory(this, categoryId, ContentType.LIVE).any { it.key == channel.key }
        ) categoryId else PlaylistRepository.CATEGORY_ALL

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupCast()
        setupModeUi()
        setupButtons()
        setupPanel()
        setupGestures()

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.tutorialOverlay.isVisible -> dismissTutorial()
                panelVisible -> closePanel()
                else -> finish()
            }
        }

        var resumeMs = 0L
        if (!isLive) {
            if (intent.getBooleanExtra(EXTRA_START_OVER, false)) {
                WatchProgress.clear(this, channel.key)
            } else {
                resumeMs = WatchProgress.get(this, channel.key)?.takeIf { !it.finished }?.positionMs ?: 0L
            }
        }
        PlayerHolder.playChannel(this, channel, resumeMs)
        updateChannelInfo()
        showControls()
        maybeShowTutorial()
    }

    // ---- First-run tutorial ----------------------------------------------------------------

    private fun maybeShowTutorial() {
        if (UiPrefs.getTutorialSeen(this)) return
        binding.tutorialCenter.setText(if (isLive) R.string.tutorial_center_live else R.string.tutorial_center_vod)
        binding.tutorialExtra.setText(if (isLive) R.string.tutorial_extra_live else R.string.tutorial_extra_vod)
        binding.tutorialGotIt.setOnClickListener { dismissTutorial() }
        binding.tutorialOverlay.visibility = View.VISIBLE
    }

    private fun dismissTutorial() {
        binding.tutorialOverlay.visibility = View.GONE
        UiPrefs.setTutorialSeen(this, true)
        scheduleHideControls()
    }

    override fun onStart() {
        super.onStart()
        if (!::channel.isInitialized) return
        PlayerHolder.fullscreenOpen = true
        PlayerHolder.addCastListener(castListener)
        val exo = PlayerHolder.active(this)
        player = exo
        PlayerHolder.attach(this, binding.playerView)
        exo.addListener(playerListener)
        refreshTrackOptions(exo.currentTracks)
        updatePlayIcon()
        updateSubtitleLine()
        updateCastOverlay()
        // Coming back from the background: playback was paused when we stopped.
        if (!exo.playWhenReady && !hasError) exo.playWhenReady = true
        // Opening fullscreen on a channel that is already playing fires no "started playing" event,
        // so the auto-hide timer must be started here or the controls would linger.
        scheduleHideControls()
        handler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        if (!::channel.isInitialized) return
        PlayerHolder.fullscreenOpen = false
        saveProgress()
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        PlayerHolder.removeCastListener(castListener)
        player?.removeListener(playerListener)
        PlayerHolder.detach(binding.playerView)
        // Leaving for good (back button) keeps live playback going in the Live tab's mini-player;
        // anything else (home, screen off, PiP window closed) pauses it.
        if (!isFinishing && !PlayerHolder.casting) player?.playWhenReady = false
        // Movies don't continue in the background after you leave the player.
        if (isFinishing && !isLive && !PlayerHolder.casting) PlayerHolder.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::channel.isInitialized && !PlayerHolder.casting && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            hideControls()
            closePanel()
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The PiP window was dismissed (the activity is stopped, not expanded back to fullscreen).
            player?.playWhenReady = false
            if (!isLive) PlayerHolder.release()
            finish()
        } else {
            showControls()
        }
    }

    // ---- Chromecast ------------------------------------------------------------------------

    private fun setupCast() {
        val ok = PlayerHolder.initCast(this)
        // Live TV is not castable (see About), so the button only shows for movies and episodes.
        val castable = ok && channel.contentType != ContentType.LIVE
        binding.castButton.isVisible = castable
        if (castable) {
            try {
                CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.castButton)
            } catch (e: Throwable) {
                binding.castButton.isVisible = false
            }
        }
    }

    /** Called when a cast session starts/ends: move the UI over to whichever player is now active. */
    private fun rebindPlayer() {
        player?.removeListener(playerListener)
        val p = PlayerHolder.active(this)
        player = p
        PlayerHolder.attach(this, binding.playerView)
        p.addListener(playerListener)
        hasError = false
        binding.errorText.visibility = View.GONE
        refreshTrackOptions(p.currentTracks)
        updatePlayIcon()
        updateCastOverlay()
    }

    private fun updateCastOverlay() {
        val casting = PlayerHolder.casting
        binding.castOverlay.isVisible = casting
        if (casting) {
            val device = PlayerHolder.castDeviceName() ?: "Chromecast"
            binding.castOverlay.text = if (PlayerHolder.castError != null) {
                getString(R.string.cast_error, device)
            } else {
                getString(R.string.cast_casting_overlay, device)
            }
        }
    }

    // ---- Setup -----------------------------------------------------------------------------

    private fun buildChannelList(categoryId: String): List<Channel> {
        val inCategory = PlaylistRepository.channelsForCategory(this, categoryId, ContentType.LIVE)
        return if (inCategory.any { it.key == channel.key }) {
            inCategory
        } else {
            PlaylistRepository.channelsForCategory(this, PlaylistRepository.CATEGORY_ALL, ContentType.LIVE)
        }
    }

    /** Live shows channel prev/next + EPG progress; movies show ±10 s, seek bar and speed. */
    private fun setupModeUi() {
        val live = isLive
        binding.prevButton.isVisible = live
        binding.nextButton.isVisible = live
        binding.channelsButton.isVisible = live
        binding.liveProgress.isVisible = live
        binding.seekBackButton.isVisible = !live
        binding.seekForwardButton.isVisible = !live
        binding.seekBar.isVisible = !live
        binding.timeRow.isVisible = !live
        binding.speedButton.isVisible = !live
        binding.programmeText.isVisible = live
        binding.nextText.isVisible = live
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }
        binding.pipButton.setOnClickListener { enterPip() }
        binding.playPauseButton.setOnClickListener {
            togglePlayPause()
            scheduleHideControls()
        }
        binding.prevButton.setOnClickListener { switchBy(-1) }
        binding.nextButton.setOnClickListener { switchBy(1) }
        binding.seekBackButton.setOnClickListener {
            seekBy(-10_000L)
            scheduleHideControls()
        }
        binding.seekForwardButton.setOnClickListener {
            seekBy(10_000L)
            scheduleHideControls()
        }
        binding.audioButton.setOnClickListener { showTrackDialog(true) }
        binding.subtitleButton.setOnClickListener { showTrackDialog(false) }
        binding.channelsButton.setOnClickListener { openPanel() }
        binding.aspectButton.setOnClickListener { cycleAspect() }
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.errorText.setOnClickListener { retry() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.timeCurrent.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                player?.seekTo(seekBar.progress.toLong())
                scheduleHideControls()
            }
        })
    }

    private fun setupPanel() {
        panelAdapter = ChannelAdapter(
            compact = true,
            onClick = {
                switchTo(it)
                closePanel()
            }
        )
        binding.panelList.layoutManager = LinearLayoutManager(this)
        binding.panelList.adapter = panelAdapter
        binding.panelList.itemAnimator = null
        binding.panelClose.setOnClickListener { closePanel() }
        binding.panelScrim.setOnClickListener { closePanel() }
        binding.panelTitle.setText(R.string.action_channels)
        binding.panelCategoryRow.setOnClickListener { showPanelCategoryDialog() }
        updatePanelCategoryLabel()
    }

    private fun categoryLabel(id: String): String = when (id) {
        PlaylistRepository.CATEGORY_ALL -> getString(R.string.category_all_channels)
        PlaylistRepository.CATEGORY_FAVORITES -> getString(R.string.category_favorites)
        com.ze.iptvplayer.model.Channel.UNGROUPED_GROUP_KEY -> getString(R.string.category_uncategorized)
        else -> id
    }

    private fun updatePanelCategoryLabel() {
        binding.panelCategory.text = categoryLabel(panelCategoryId)
    }

    /** Lets the user browse another category without leaving fullscreen. */
    private fun showPanelCategoryDialog() {
        val cats = PlaylistRepository.categories(this, ContentType.LIVE)
        val labels = cats.map { it.second }.toTypedArray()
        val checked = cats.indexOfFirst { it.first == panelCategoryId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.label_categories_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                panelCategoryId = cats[which].first
                channels = PlaylistRepository.channelsForCategory(this, panelCategoryId, ContentType.LIVE)
                updatePanelCategoryLabel()
                panelAdapter.submit(channels, FavoritesManager.getAll(this))
                panelAdapter.setPlayingKey(channel.key)
                val position = panelAdapter.positionOf(channel.key)
                binding.panelList.scrollToPosition(if (position >= 0) position else 0)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun setupGestures() {
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLive) {
                    togglePlayPause()
                    return true
                }
                val third = binding.root.width / 3f
                when {
                    e.x < third -> seekBy(-10_000L)
                    e.x > 2 * third -> seekBy(10_000L)
                    else -> togglePlayPause()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val width = binding.root.width.toFloat()
                val height = binding.root.height.toFloat()
                if (width <= 0f || height <= 0f) return false

                if (scrollMode == ScrollMode.NONE) {
                    scrollMode = when {
                        abs(distanceY) > abs(distanceX) && start.x < width / 2f -> ScrollMode.BRIGHTNESS
                        abs(distanceY) > abs(distanceX) -> ScrollMode.VOLUME
                        isLive && start.x > width * 0.88f && distanceX > 0f -> ScrollMode.PANEL
                        else -> ScrollMode.IGNORE
                    }
                    when (scrollMode) {
                        ScrollMode.VOLUME -> volumeValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        ScrollMode.BRIGHTNESS -> brightnessValue = currentBrightness()
                        ScrollMode.PANEL -> openPanel()
                        else -> {}
                    }
                }
                when (scrollMode) {
                    ScrollMode.BRIGHTNESS -> adjustBrightness(distanceY / height)
                    ScrollMode.VOLUME -> adjustVolume(distanceY / height)
                    else -> {}
                }
                return true
            }
        }
        gestureDetector = GestureDetector(this, listener)
        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                scrollMode = ScrollMode.NONE
            }
            true
        }
        // Keep the system's edge-back gesture out of the way of the right-edge channel-panel swipe.
        binding.root.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            val edge = (48 * resources.displayMetrics.density).toInt()
            val half = (100 * resources.displayMetrics.density).toInt()
            ViewCompat.setSystemGestureExclusionRects(v, listOf(Rect(w - edge, h / 2 - half, w, h / 2 + half)))
        }
    }

    // ---- Brightness / volume ---------------------------------------------------------------

    private fun currentBrightness(): Float {
        val set = window.attributes.screenBrightness
        if (set >= 0f) return set
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun adjustBrightness(deltaFraction: Float) {
        brightnessValue = (brightnessValue + deltaFraction).coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = brightnessValue
        window.attributes = lp
        showHint("☀  ${(brightnessValue * 100).roundToInt()}%")
    }

    private fun adjustVolume(deltaFraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeValue = (volumeValue + deltaFraction * max * 1.2f).coerceIn(0f, max.toFloat())
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeValue.roundToInt(), 0)
        showHint("🔊  ${(volumeValue / max * 100).roundToInt()}%")
    }

    private fun showHint(text: String) {
        binding.gestureHint.text = text
        binding.gestureHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 800L)
    }

    // ---- Controls --------------------------------------------------------------------------

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showControls() {
        if (inPip) return
        binding.controlsRoot.visibility = View.VISIBLE
        controlsVisible = true
        updateProgress()
        scheduleHideControls()
    }

    private fun hideControls() {
        binding.controlsRoot.visibility = View.GONE
        controlsVisible = false
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && !userSeeking && !hasError && player?.isPlaying == true &&
            !binding.tutorialOverlay.isVisible
        ) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun updatePlayIcon() {
        val exo = player ?: return
        val showPause = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        binding.playPauseButton.setImageResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (hasError) {
            retry()
            return
        }
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = !exo.playWhenReady
    }

    private fun retry() {
        hasError = false
        binding.errorText.visibility = View.GONE
        PlayerHolder.retryCurrent()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        showHint(if (deltaMs > 0) "+${deltaMs / 1000}s" else "${deltaMs / 1000}s")
    }

    private fun updateProgress() {
        val exo = player ?: return
        if (!isLive) {
            val duration = exo.duration
            if (duration > 0 && duration != C.TIME_UNSET && !userSeeking) {
                binding.seekBar.max = duration.toInt()
                binding.seekBar.progress = exo.currentPosition.toInt()
                binding.seekBar.secondaryProgress = exo.bufferedPosition.toInt()
                binding.timeCurrent.text = formatTime(exo.currentPosition)
                binding.timeTotal.text = formatTime(duration)
            }
            // Every ~5 s (the runnable ticks twice a second) so a crash or swipe-away loses little.
            if (tick++ % 10 == 0 && exo.isPlaying) saveProgress()
        } else {
            // The EPG moves slowly — refresh the programme line twice a minute, not twice a second.
            if (tick++ % 60 == 0) updateProgrammeInfo()
        }
    }

    private fun saveProgress() {
        if (isLive) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        WatchProgress.save(this, channel.key, exo.currentPosition, duration)
    }

    /** A movie/episode finished: remember it as watched, and roll straight into the next episode. */
    private fun onVodEnded() {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0 && duration != C.TIME_UNSET) {
            WatchProgress.save(this, channel.key, duration, duration)
        }
        if (channel.contentType != ContentType.SERIES) return
        val next = SeriesIndex.nextEpisode(channel) ?: return
        channel = next
        hasError = false
        PlayerHolder.playChannel(this, next, WatchProgress.get(this, next.key)?.takeIf { !it.finished }?.positionMs ?: 0L)
        updateChannelInfo()
        showHint(getString(R.string.toast_next_episode, next.name))
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ---- Channel info / switching ----------------------------------------------------------

    private fun updateChannelInfo() {
        binding.titleText.text = channel.name
        ChannelLogos.load(binding.topLogo, channel.logoUrl)
        updateSubtitleLine()
        updateProgrammeInfo()
    }

    private fun updateSubtitleLine() {
        if (!::channel.isInitialized) return
        val height = player?.videoSize?.height ?: 0
        val quality = if (height > 0) getString(R.string.label_quality_format, height) else ""
        binding.subtitleText.text = if (isLive) {
            listOf(getString(R.string.label_live_badge), quality).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            quality
        }
    }

    private fun updateProgrammeInfo() {
        if (!isLive) return
        val (now, next) = PlaylistRepository.nowNext(this, channel)
        if (now != null) {
            val fmt = DateFormat.getTimeFormat(this)
            binding.programmeText.text =
                "${now.title} · ${fmt.format(Date(now.startMillis))} – ${fmt.format(Date(now.stopMillis))}"
            binding.nextText.text = next?.let { getString(R.string.label_next_prefix, it.title) }.orEmpty()
            val length = (now.stopMillis - now.startMillis).coerceAtLeast(1L)
            binding.liveProgress.progress =
                ((System.currentTimeMillis() - now.startMillis) * 100 / length).toInt().coerceIn(0, 100)
            binding.liveProgress.visibility = View.VISIBLE
        } else {
            binding.programmeText.text = getString(R.string.label_no_programme)
            binding.nextText.text = ""
            binding.liveProgress.visibility = View.INVISIBLE
        }
    }

    private fun switchTo(target: Channel) {
        channel = target
        hasError = false
        binding.errorText.visibility = View.GONE
        PlayerHolder.playChannel(this, target)
        updateChannelInfo()
        panelAdapter.setPlayingKey(target.key)
        showControls()
    }

    private fun switchBy(delta: Int) {
        if (channels.size < 2) return
        val index = channels.indexOfFirst { it.key == channel.key }
        val next = ((index + delta) % channels.size + channels.size) % channels.size
        switchTo(channels[next])
    }

    private fun openPanel() {
        panelAdapter.submit(channels, FavoritesManager.getAll(this))
        panelAdapter.setPlayingKey(channel.key)
        binding.panelContainer.visibility = View.VISIBLE
        panelVisible = true
        hideControls()
        val position = panelAdapter.positionOf(channel.key)
        if (position >= 0) binding.panelList.scrollToPosition(position)
    }

    private fun closePanel() {
        binding.panelContainer.visibility = View.GONE
        panelVisible = false
    }

    // ---- Aspect ratio / speed / PiP --------------------------------------------------------

    private fun cycleAspect() {
        resizeIndex = (resizeIndex + 1) % 3
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val labels = intArrayOf(R.string.label_aspect_fit, R.string.label_aspect_fill, R.string.label_aspect_zoom)
        binding.playerView.resizeMode = modes[resizeIndex]
        binding.aspectButton.setText(labels[resizeIndex])
        scheduleHideControls()
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        val speed = speeds[speedIndex]
        player?.setPlaybackSpeed(speed)
        binding.speedButton.text = "${speed}×"
        scheduleHideControls()
    }

    private fun enterPip() {
        if (PlayerHolder.casting) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = player?.videoSize
        var ratio = if (size != null && size.width > 0 && size.height > 0) {
            size.width.toFloat() / size.height
        } else {
            16f / 9f
        }
        ratio = ratio.coerceIn(0.5f, 2.3f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            // PiP isn't allowed right now (e.g. disabled for this app) — just stay fullscreen.
        }
    }

    // ---- Audio / subtitle tracks -----------------------------------------------------------

    /** Rebuilds the Audio/Subtitles lists from whatever the player currently reports. */
    private fun refreshTrackOptions(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val subs = mutableListOf<TrackOption>()
        unsupportedAudioCodec = null
        var audioIndex = 0
        var subIndex = 0
        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) {
                        if (unsupportedAudioCodec == null) unsupportedAudioCodec = codecName(group.getTrackFormat(i))
                        continue
                    }
                    audioIndex++
                    audio.add(TrackOption("a$audioIndex", trackLabel(group.getTrackFormat(i), audioIndex), group, i))
                }
                C.TRACK_TYPE_TEXT -> for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    subIndex++
                    subs.add(TrackOption("s$subIndex", trackLabel(group.getTrackFormat(i), subIndex), group, i))
                }
                else -> {}
            }
        }
        audioOptions = audio
        subtitleOptions = listOf(TrackOption("off", getString(R.string.label_subtitles_off), null, -1)) + subs

        val textDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        selectedAudioId = audio.firstOrNull { it.group?.isTrackSelected(it.trackIndexInGroup) == true }?.id
        selectedSubId = if (textDisabled) {
            "off"
        } else {
            subs.firstOrNull { it.group?.isTrackSelected(it.trackIndexInGroup) == true }?.id ?: "off"
        }
        updateTrackButtons()
    }

    private fun updateTrackButtons() {
        val audioLabel = audioOptions.firstOrNull { it.id == selectedAudioId }?.label ?: "–"
        val subLabel = subtitleOptions.firstOrNull { it.id == selectedSubId }?.label
            ?: getString(R.string.label_subtitles_off)
        val unsupported = unsupportedAudioCodec
        binding.audioButton.text = if (audioOptions.isEmpty() && unsupported != null) {
            getString(R.string.label_audio_unsupported, unsupported)
        } else {
            getString(R.string.label_audio_with_value, audioLabel)
        }
        binding.subtitleButton.text = getString(R.string.label_subtitles_with_value, subLabel)
    }

    /** Set when the stream has audio that this phone has no decoder for (e.g. AC-3, E-AC-3, DTS). */
    private var unsupportedAudioCodec: String? = null

    private fun codecName(format: Format): String = when (val mime = format.sampleMimeType.orEmpty()) {
        "audio/ac3" -> "AC-3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/true-hd" -> "TrueHD"
        "audio/mpeg-L2" -> "MP2"
        else -> if (mime.startsWith("audio/vnd.dts")) "DTS" else mime.substringAfter('/').ifEmpty { "?" }
    }

    private fun trackLabel(format: Format, indexInType: Int): String {
        val name = format.label?.takeIf { it.isNotBlank() }
        if (name != null) return name
        val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
        if (lang != null) {
            val display = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault())
            return display.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: lang.uppercase(Locale.getDefault())
        }
        return getString(R.string.label_track_unnamed, indexInType)
    }

    private fun showTrackDialog(audio: Boolean) {
        val options = if (audio) audioOptions else subtitleOptions
        // Subtitles always contain "Off"; only treat the list as empty when there's nothing else.
        val empty = if (audio) options.isEmpty() else options.size <= 1
        if (empty) {
            Toast.makeText(this, R.string.label_no_tracks_available, Toast.LENGTH_SHORT).show()
            return
        }
        val selectedId = if (audio) selectedAudioId else selectedSubId
        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) R.string.label_audio_tracks else R.string.label_subtitle_tracks)
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                options.indexOfFirst { it.id == selectedId }
            ) { dialog, which ->
                val id = options[which].id
                if (audio) applyAudioTrackSelection(id) else applySubtitleTrackSelection(id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        scheduleHideControls()
    }

    private fun applyAudioTrackSelection(id: String) {
        val option = audioOptions.firstOrNull { it.id == id } ?: return
        val group = option.group ?: return
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup))
            .build()
        selectedAudioId = id
        updateTrackButtons()
    }

    private fun applySubtitleTrackSelection(id: String) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
        if (id == "off") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val option = subtitleOptions.firstOrNull { it.id == id }
            val group = option?.group ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup))
        }
        exo.trackSelectionParameters = builder.build()
        selectedSubId = id
        updateTrackButtons()
    }
}
