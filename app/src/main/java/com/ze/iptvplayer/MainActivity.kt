package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.databinding.ActivityMainBinding
import androidx.media3.common.Player
import com.ze.iptvplayer.player.PlayerHolder
import com.ze.iptvplayer.ui.LiveFragment
import com.ze.iptvplayer.ui.MoreFragment
import com.ze.iptvplayer.ui.GuideFragment
import com.ze.iptvplayer.ui.VodFragment
import com.ze.iptvplayer.model.ContentType
import kotlinx.coroutines.launch

/**
 * Home of the app: a bottom navigation bar switching between Live TV, TV Guide, Movies, TV Shows
 * and More. Playlist loading is owned by [MainViewModel] and shared by every tab.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val tags = mapOf(
        R.id.nav_live to "live",
        R.id.nav_guide to "guide",
        R.id.nav_movies to "movies",
        R.id.nav_shows to "shows",
        R.id.nav_more to "more"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        if (Prefs.activePlaylist(this) == null) {
            startActivity(Intent(this, StartActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        if (savedInstanceState == null) showTab(R.id.nav_live)

        PlayerHolder.initCast(this)
        binding.castBarPlay.setOnClickListener {
            val p = PlayerHolder.active(this)
            p.playWhenReady = !p.playWhenReady
            updateCastBar()
        }
        binding.castBarStop.setOnClickListener { PlayerHolder.stopCasting() }

        binding.loadRetryButton.setOnClickListener { vm.reload() }
        binding.loadEditButton.setOnClickListener {
            Prefs.activePlaylist(this)?.let {
                startActivity(
                    Intent(this, AddSourceActivity::class.java).putExtra(AddSourceActivity.EXTRA_PLAYLIST_ID, it.id)
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) vm.syncWithPrefs()
    }

    private val castListener: () -> Unit = { runOnUiThread { updateCastBar() } }

    private val castPlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateCastBar()
        override fun onPlaybackStateChanged(playbackState: Int) = updateCastBar()
    }
    private var observedPlayer: Player? = null

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        PlayerHolder.addCastListener(castListener)
        updateCastBar()
    }

    private fun updateCastBar() {
        if (!::binding.isInitialized) return
        val channel = PlayerHolder.currentChannel
        val show = PlayerHolder.casting && channel != null
        binding.castBar.visibility = if (show) View.VISIBLE else View.GONE
        observedPlayer?.removeListener(castPlayerListener)
        observedPlayer = null
        if (!show) return
        val p = PlayerHolder.active(this)
        p.addListener(castPlayerListener)
        observedPlayer = p
        binding.castBarTitle.text = channel?.name.orEmpty()
        val device = PlayerHolder.castDeviceName() ?: "Chromecast"
        binding.castBarSubtitle.text = if (PlayerHolder.castError != null) {
            getString(R.string.cast_error_short, device)
        } else {
            getString(R.string.cast_casting_to, device)
        }
        binding.castBarPlay.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onStop() {
        super.onStop()
        if (::binding.isInitialized) {
            PlayerHolder.removeCastListener(castListener)
            observedPlayer?.removeListener(castPlayerListener)
            observedPlayer = null
        }
        // Going to the background (not rotating, not handing over to the fullscreen player): stop
        // the stream so it doesn't keep playing audio behind the home screen.
        if (!isChangingConfigurations && !PlayerHolder.fullscreenOpen) PlayerHolder.pause()
    }

    private fun render(state: LoadState) {
        when (state) {
            LoadState.Idle, LoadState.Loading -> {
                binding.loadOverlay.visibility = View.VISIBLE
                binding.loadProgress.visibility = View.VISIBLE
                binding.loadText.text = getString(R.string.label_loading_playlist)
                binding.loadRetryButton.visibility = View.GONE
                binding.loadEditButton.visibility = View.GONE
            }
            is LoadState.Ready -> binding.loadOverlay.visibility = View.GONE
            is LoadState.Error -> {
                binding.loadOverlay.visibility = View.VISIBLE
                binding.loadProgress.visibility = View.GONE
                binding.loadText.text = getString(R.string.label_load_failed) + "\n\n" + state.message
                binding.loadRetryButton.visibility = View.VISIBLE
                binding.loadEditButton.visibility = View.VISIBLE
            }
            LoadState.NoPlaylist -> {
                startActivity(Intent(this, StartActivity::class.java))
                finish()
            }
        }
    }

    private fun createTab(itemId: Int): Fragment = when (itemId) {
        R.id.nav_live -> LiveFragment()
        R.id.nav_guide -> GuideFragment()
        R.id.nav_movies -> VodFragment.newInstance(ContentType.MOVIE)
        R.id.nav_shows -> VodFragment.newInstance(ContentType.SERIES)
        else -> MoreFragment()
    }

    /** Shows the tab's fragment (creating it on first use) and hides the others, keeping each
     *  tab's scroll position and state alive while you switch between them. */
    private fun showTab(itemId: Int) {
        val tag = tags[itemId] ?: return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.container, createTab(itemId), tag)
        } else {
            tx.show(existing)
        }
        tx.commit()
    }
}
