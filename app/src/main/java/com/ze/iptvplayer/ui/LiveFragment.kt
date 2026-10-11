package com.ze.iptvplayer.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import com.ze.iptvplayer.AddSourceActivity
import com.ze.iptvplayer.ChannelMappingActivity
import com.ze.iptvplayer.LoadState
import com.ze.iptvplayer.MainViewModel
import com.ze.iptvplayer.PlayerActivity
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.LongPressAction
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.databinding.FragmentLiveBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.player.PlayerHolder
import com.ze.iptvplayer.util.ChannelLogos
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Live TV tab: channel list with now/next progress, category bottom sheet, inline search and a
 * mini-player. In portrait, tapping a channel previews it at the top (tap the preview for
 * fullscreen); in landscape there's no room for a preview, so a tap goes straight to fullscreen.
 */
class LiveFragment : Fragment() {

    private var _binding: FragmentLiveBinding? = null
    private val b get() = _binding!!

    private val vm: MainViewModel by activityViewModels()
    private lateinit var adapter: ChannelAdapter
    private lateinit var favStripAdapter: FavStripAdapter

    private var shownGeneration = -1
    private var categoryId = PlaylistRepository.CATEGORY_ALL
    private var query = ""
    private var searching = false
    private var hasError = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (_binding == null) return
            adapter.refreshTimes()
            refreshNowPlaying()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            hasError = true
            updatePreviewState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) hasError = false
            updatePreviewState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePreviewState()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePreviewState()
    }

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChannelAdapter(
            compact = false,
            onClick = ::onChannelClicked,
            onLongClick = ::onChannelLongClicked,
            onFavorite = ::toggleFavorite
        )
        favStripAdapter = FavStripAdapter(::onChannelClicked)
        b.favStrip.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.favStrip.adapter = favStripAdapter
        b.favStrip.itemAnimator = null
        b.channelList.layoutManager = LinearLayoutManager(requireContext())
        b.channelList.adapter = adapter
        b.channelList.itemAnimator = null
        b.channelList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dx == 0 && dy == 0) return // layout pass, not a real scroll
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                if (first < 0) return
                vm.liveScrollGeneration = shownGeneration
                vm.liveScrollPos = first
                vm.liveScrollOffset = lm.findViewByPosition(first)?.top ?: 0
            }
        })

        b.categoryBar.setOnClickListener {
            CategorySheet.newInstance(ContentType.LIVE, categoryId).show(childFragmentManager, "categories")
        }
        b.searchButton.setOnClickListener { if (searching) closeSearch() else openSearch() }
        b.searchEdit.addTextChangedListener {
            query = it?.toString().orEmpty()
            refreshList(resetScroll = true)
        }
        b.searchEdit.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        b.playlistButton.setOnClickListener { showPlaylistMenu(it) }

        b.playerView.setOnClickListener { PlayerHolder.currentChannel?.let { openFullscreen(it) } }
        b.previewFullscreen.setOnClickListener { PlayerHolder.currentChannel?.let { openFullscreen(it) } }
        b.previewPlayButton.setOnClickListener {
            if (hasError) {
                hasError = false
                PlayerHolder.retryCurrent()
            } else {
                PlayerHolder.ensurePlayer(requireContext()).playWhenReady = true
            }
            updatePreviewState()
        }
        b.previewError.setOnClickListener {
            hasError = false
            PlayerHolder.retryCurrent()
            updatePreviewState()
        }
        b.npStar.setOnClickListener { PlayerHolder.currentChannel?.let { toggleFavorite(it) } }

        childFragmentManager.setFragmentResultListener(CategorySheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(CategorySheet.RESULT_ID)?.let { onCategoryChosen(it) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    if (state is LoadState.Ready && state.generation != shownGeneration) {
                        shownGeneration = state.generation
                        rebuild()
                    }
                }
            }
        }
    }

    private val castListener: () -> Unit = {
        activity?.runOnUiThread { if (_binding != null && !isHidden) syncPreviewFromHolder() }
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.addCastListener(castListener)
        PlayerHolder.ensurePlayer(requireContext()).addListener(playerListener)
        syncPreviewFromHolder()
    }

    override fun onResume() {
        super.onResume()
        adapter.setFavorites(FavoritesManager.getAll(requireContext()))
        adapter.refreshTimes() // a guide mapping may have changed while another screen was open
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 60_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        PlayerHolder.removeCastListener(castListener)
        PlayerHolder.ensurePlayer(requireContext()).removeListener(playerListener)
        PlayerHolder.detach(b.playerView)
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (hidden) {
            PlayerHolder.pause()
            hideKeyboard()
        } else {
            syncPreviewFromHolder()
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    // ---- List ------------------------------------------------------------------------------

    /** The active playlist was (re)loaded: restore its last category and rebuild everything. */
    private fun rebuild() {
        val ctx = requireContext()
        val playlist = Prefs.activePlaylist(ctx)
        b.playlistButton.text = playlist?.name.orEmpty()

        val wanted = playlist?.lastCategoryId
        val valid = wanted != null &&
            PlaylistRepository.categories(ctx, ContentType.LIVE).any { it.first == wanted }
        categoryId = if (valid) wanted!! else PlaylistRepository.CATEGORY_ALL

        if (searching) closeSearch()
        // Same data as before (the screen was only re-created): put the list back where it was.
        val restore = vm.liveScrollGeneration == shownGeneration && vm.liveScrollPos > 0
        val pos = vm.liveScrollPos
        val offset = vm.liveScrollOffset
        refreshList(resetScroll = !restore)
        if (restore) {
            b.channelList.post {
                if (_binding != null) {
                    (b.channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, offset)
                }
            }
        }
        syncPreviewFromHolder()
    }

    private fun labelFor(id: String): String = when (id) {
        PlaylistRepository.CATEGORY_ALL -> getString(R.string.category_all_channels)
        PlaylistRepository.CATEGORY_FAVORITES -> getString(R.string.category_favorites)
        Channel.UNGROUPED_GROUP_KEY -> getString(R.string.category_uncategorized)
        else -> id
    }

    private fun refreshList(resetScroll: Boolean) {
        if (_binding == null) return
        val ctx = requireContext()
        val trimmed = query.trim()
        val list: List<Channel>
        val title: String
        if (trimmed.isNotEmpty()) {
            list = PlaylistRepository.channels
                .filter { it.contentType == ContentType.LIVE && it.name.contains(trimmed, ignoreCase = true) }
                .take(500)
            title = getString(R.string.nav_search)
        } else {
            list = PlaylistRepository.channelsForCategory(ctx, categoryId, ContentType.LIVE)
            title = labelFor(categoryId)
        }

        adapter.setPlayingKey(PlayerHolder.currentChannel?.takeIf { it.contentType == ContentType.LIVE }?.key)
        adapter.submit(list, FavoritesManager.getAll(ctx))
        b.categoryTitle.text = title
        b.categoryCount.text = getString(R.string.channels_count, list.size)

        if (list.isEmpty()) {
            b.emptyText.text = if (trimmed.isEmpty() && categoryId == PlaylistRepository.CATEGORY_FAVORITES) {
                getString(R.string.label_empty_favorites)
            } else {
                getString(R.string.label_no_results)
            }
            b.emptyText.visibility = View.VISIBLE
        } else {
            b.emptyText.visibility = View.GONE
        }
        if (resetScroll) b.channelList.scrollToPosition(0)
    }

    private fun onCategoryChosen(id: String) {
        val ctx = requireContext()
        categoryId = id
        vm.liveScrollPos = 0
        vm.liveScrollOffset = 0
        Prefs.activePlaylist(ctx)?.let { Prefs.setLastCategoryForPlaylist(ctx, it.id, id) }
        if (searching) closeSearch() else refreshList(resetScroll = true)
    }

    private fun toggleFavorite(channel: Channel) {
        val ctx = requireContext()
        FavoritesManager.toggle(ctx, channel.key)
        if (categoryId == PlaylistRepository.CATEGORY_FAVORITES && query.isBlank()) {
            refreshList(resetScroll = false)
        } else {
            adapter.setFavorites(FavoritesManager.getAll(ctx))
        }
        refreshNowPlaying()
        refreshFavStrip()
    }

    private fun onChannelClicked(channel: Channel) {
        if (PlayerHolder.casting) {
            // No local preview while casting: switch the TV to this channel and open the remote screen.
            PlayerHolder.playChannel(requireContext(), channel)
            openFullscreen(channel)
            return
        }
        if (isLandscape) {
            openFullscreen(channel)
            return
        }
        val current = PlayerHolder.currentChannel
        if (current?.key == channel.key && b.previewContainer.isVisible) {
            if (hasError) {
                hasError = false
                PlayerHolder.retryCurrent()
                updatePreviewState()
            } else {
                openFullscreen(channel)
            }
            return
        }
        hasError = false
        PlayerHolder.playChannel(requireContext(), channel)
        showPreview(channel)
        refreshFavStrip()
    }

    private fun onChannelLongClicked(channel: Channel, anchor: View) {
        val ctx = requireContext()
        if (UiPrefs.getLongPressAction(ctx) == LongPressAction.QUICK_FAVORITE) {
            toggleFavorite(channel)
            return
        }
        val isFavorite = FavoritesManager.isFavorite(ctx, channel.key)
        PopupMenu(ctx, anchor).apply {
            menu.add(
                0, MENU_FAVORITE, 0,
                getString(if (isFavorite) R.string.action_remove_from_favorites else R.string.action_add_to_favorites)
            )
            menu.add(0, MENU_ASSIGN_EPG, 1, getString(R.string.popup_action_assign_epg))
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_ASSIGN_EPG) {
                    ChannelMappingActivity.start(ctx, channel.key)
                } else {
                    toggleFavorite(channel)
                }
                true
            }
            show()
        }
    }

    private fun showPlaylistMenu(anchor: View) {
        val ctx = requireContext()
        val playlists = Prefs.getAllPlaylists(ctx)
        val activeId = Prefs.activePlaylist(ctx)?.id
        PopupMenu(ctx, anchor).apply {
            playlists.forEachIndexed { index, playlist ->
                val prefix = if (playlist.id == activeId) "✓  " else "     "
                menu.add(0, index, index, prefix + playlist.name)
            }
            menu.add(1, ADD_PLAYLIST_ITEM, playlists.size, getString(R.string.button_add_playlist))
            setOnMenuItemClickListener { item ->
                if (item.itemId == ADD_PLAYLIST_ITEM) {
                    startActivity(Intent(ctx, AddSourceActivity::class.java))
                } else {
                    val chosen = playlists[item.itemId]
                    if (chosen.id != activeId) vm.switchPlaylist(chosen.id)
                }
                true
            }
            show()
        }
    }

    // ---- Search ----------------------------------------------------------------------------

    private fun openSearch() {
        searching = true
        backCallback.isEnabled = true
        b.titleText.visibility = View.GONE
        b.playlistButton.visibility = View.GONE
        b.appBarSpacer.visibility = View.GONE
        b.searchEdit.visibility = View.VISIBLE
        b.searchEdit.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java).showSoftInput(b.searchEdit, 0)
    }

    private fun closeSearch() {
        if (_binding == null) return
        searching = false
        backCallback.isEnabled = false
        query = ""
        b.searchEdit.setText("")
        b.searchEdit.visibility = View.GONE
        b.titleText.visibility = View.VISIBLE
        b.playlistButton.visibility = View.VISIBLE
        b.appBarSpacer.visibility = View.VISIBLE
        hideKeyboard()
        refreshList(resetScroll = true)
    }

    private fun hideKeyboard() {
        val view = _binding?.searchEdit ?: return
        requireContext().getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---- Preview ---------------------------------------------------------------------------

    private fun openFullscreen(channel: Channel) {
        val inCategory = !searching && query.isBlank()
        PlayerActivity.start(requireContext(), channel, if (inCategory) categoryId else PlaylistRepository.CATEGORY_ALL)
    }

    /** Shows or hides the mini-player to match whatever the shared player is currently playing. */
    private fun syncPreviewFromHolder() {
        if (_binding == null) return
        val current = PlayerHolder.currentChannel
        val pinned = UiPrefs.getPinnedPlayer(requireContext()) && !isLandscape && !PlayerHolder.casting
        if (current != null && current.contentType == ContentType.LIVE && !isLandscape && !PlayerHolder.casting) {
            showPreview(current)
        } else if (pinned) {
            // Pinned player: keep the video area on screen even before anything is playing.
            b.previewContainer.visibility = View.VISIBLE
            b.previewEmpty.visibility = View.VISIBLE
            b.liveBadge.visibility = View.GONE
            b.previewFullscreen.visibility = View.GONE
            b.previewBuffering.visibility = View.GONE
            b.previewPlayButton.visibility = View.GONE
            b.nowPlayingRow.visibility = View.GONE
            adapter.setPlayingKey(null)
        } else {
            b.previewContainer.visibility = View.GONE
            b.nowPlayingRow.visibility = View.GONE
            adapter.setPlayingKey(current?.takeIf { it.contentType == ContentType.LIVE }?.key)
        }
        refreshFavStrip()
    }

    /** Favorites strip under the pinned player (only when that layout option is on). */
    private fun refreshFavStrip() {
        if (_binding == null) return
        val ctx = requireContext()
        val show = UiPrefs.getPinnedPlayer(ctx) && !isLandscape && !PlayerHolder.casting
        if (!show) {
            b.favStrip.visibility = View.GONE
            return
        }
        val favorites = FavoritesManager.getAll(ctx)
        val list = PlaylistRepository.channelsOfType(ContentType.LIVE).filter { it.key in favorites }
        favStripAdapter.submit(list, PlayerHolder.currentChannel?.takeIf { it.contentType == ContentType.LIVE }?.key)
        b.favStrip.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showPreview(channel: Channel) {
        b.previewContainer.visibility = View.VISIBLE
        b.previewEmpty.visibility = View.GONE
        b.liveBadge.visibility = View.VISIBLE
        b.previewFullscreen.visibility = View.VISIBLE
        b.nowPlayingRow.visibility = View.VISIBLE
        PlayerHolder.attach(requireContext(), b.playerView)
        adapter.setPlayingKey(channel.key)
        updatePreviewState()
        refreshNowPlaying()
    }

    private fun updatePreviewState() {
        if (_binding == null) return
        val player = PlayerHolder.ensurePlayer(requireContext())
        val buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !hasError
        b.previewBuffering.isVisible = buffering
        b.previewError.isVisible = hasError
        b.previewPlayButton.isVisible = !hasError && !buffering &&
            (!player.playWhenReady || player.playbackState == Player.STATE_ENDED)
    }

    private fun refreshNowPlaying() {
        if (_binding == null) return
        val ctx = requireContext()
        val channel = PlayerHolder.currentChannel?.takeIf { it.contentType == ContentType.LIVE } ?: return
        b.npName.text = channel.name
        ChannelLogos.load(b.npLogo, channel.logoUrl)

        val (now, next) = PlaylistRepository.nowNext(ctx, channel)
        val timeFormat = DateFormat.getTimeFormat(ctx)
        if (now != null) {
            b.npProgramme.text = "${now.title} · ${timeFormat.format(Date(now.startMillis))} – ${timeFormat.format(Date(now.stopMillis))}"
            b.npNext.text = next?.let { getString(R.string.label_next_prefix, it.title) }.orEmpty()
        } else {
            b.npProgramme.text = getString(R.string.label_no_programme)
            b.npNext.text = ""
        }
        val isFavorite = FavoritesManager.isFavorite(ctx, channel.key)
        b.npStar.setImageResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
        b.npStar.imageTintList = ColorStateList.valueOf(
            if (isFavorite) ContextCompat.getColor(ctx, R.color.favorite_gold_color) else com.ze.iptvplayer.util.ThemeManager.color(ctx, R.attr.zTextSecondary)
        )
    }

    private companion object {
        const val ADD_PLAYLIST_ITEM = 1000
        const val MENU_FAVORITE = 1
        const val MENU_ASSIGN_EPG = 2
    }
}
