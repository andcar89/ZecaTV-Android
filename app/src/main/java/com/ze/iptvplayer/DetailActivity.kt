package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import com.ze.iptvplayer.player.PlayerHolder
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.SeriesEpisode
import com.ze.iptvplayer.data.SeriesIndex
import com.ze.iptvplayer.data.Show
import com.ze.iptvplayer.data.VodRepository
import com.ze.iptvplayer.data.WatchProgress
import com.ze.iptvplayer.databinding.ActivityDetailBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.ui.EpisodeAdapter
import com.ze.iptvplayer.ui.PosterAdapter
import com.ze.iptvplayer.util.MediaTitles
import java.util.Locale

/**
 * Detail page for one movie or one TV show. Movies: play / resume, favorite, "more like this".
 * Shows: season chips and an episode list, with the primary button continuing where you left off.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IS_SHOW = "is_show"
        private const val EXTRA_KEY = "key"

        fun start(context: Context, isShow: Boolean, key: String) {
            context.startActivity(
                android.content.Intent(context, DetailActivity::class.java)
                    .putExtra(EXTRA_IS_SHOW, isShow)
                    .putExtra(EXTRA_KEY, key)
            )
        }
    }

    private lateinit var b: ActivityDetailBinding
    private var movie: Channel? = null
    private var currentShow: Show? = null
    private var selectedSeason = Int.MIN_VALUE
    private var seasonChipsBuilt = false
    private lateinit var episodeAdapter: EpisodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        val key = intent.getStringExtra(EXTRA_KEY)
        if (key == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_IS_SHOW, false)) {
            currentShow = SeriesIndex.show(key)
        } else {
            movie = PlaylistRepository.channels.firstOrNull { it.key == key && it.contentType == ContentType.MOVIE }
        }
        if (movie == null && currentShow == null) {
            finish()
            return
        }

        b.backButton.setOnClickListener { finish() }
        if (PlayerHolder.initCast(this)) {
            try {
                com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(applicationContext, b.castButton)
                b.castButton.visibility = android.view.View.VISIBLE
            } catch (e: Throwable) {
                b.castButton.visibility = android.view.View.GONE
            }
        }

        movie?.let { setupMovie(it) }
        currentShow?.let { setupShow(it) }
    }

    override fun onResume() {
        super.onResume()
        // Progress changes while the player is open; refresh everything that shows it.
        if (!isFinishing) {
            movie?.let { refreshMovie(it) }
            currentShow?.let { refreshShow(it) }
        }
    }

    // ---- Movie -----------------------------------------------------------------------------

    private fun setupMovie(channel: Channel) {
        val info = MediaTitles.movie(channel.name)
        b.detailTitle.text = info.title
        b.detailMeta.text = info.year.orEmpty()
        b.detailMeta.visibility = if (info.year.isNullOrEmpty()) View.GONE else View.VISIBLE
        loadHero(channel.logoUrl, info.title)

        b.favoriteButton.setOnClickListener {
            FavoritesManager.toggle(this, channel.key)
            refreshMovie(channel)
        }
        b.restartButton.setOnClickListener { play(channel, startOver = true) }
        b.primaryButton.setOnClickListener { play(channel, startOver = false) }

        val related = (PlaylistRepository.channelsByGroup(ContentType.MOVIE)[channel.groupTitle] ?: emptyList())
            .filter { it.key != channel.key }
            .take(12)
            .map { VodRepository.movieItem(it) }
        if (related.isNotEmpty()) {
            val adapter = PosterAdapter(fixedWidthDp = 112) { item -> start(this, false, item.key) }
            b.relatedList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            b.relatedList.adapter = adapter
            adapter.submit(related)
            b.relatedSection.visibility = View.VISIBLE
        }
    }

    private fun refreshMovie(channel: Channel) {
        val entry = WatchProgress.get(this, channel.key)?.takeIf { !it.finished }
        if (entry != null) {
            b.primaryButton.text = getString(R.string.action_resume_at, formatTime(entry.positionMs))
            b.watchProgress.progress = (entry.fraction * 100).toInt()
            b.watchProgress.visibility = View.VISIBLE
            b.restartButton.visibility = View.VISIBLE
        } else {
            b.primaryButton.setText(R.string.action_play)
            b.watchProgress.visibility = View.GONE
            b.restartButton.visibility = View.GONE
        }
        val favorite = FavoritesManager.isFavorite(this, channel.key)
        b.favoriteButton.setIconResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border)
        b.favoriteButton.iconTint = ColorStateList.valueOf(
            if (favorite) ContextCompat.getColor(this, R.color.favorite_gold_color) else com.ze.iptvplayer.util.ThemeManager.color(this, R.attr.zTextPrimary)
        )
        b.favoriteButton.setText(if (favorite) R.string.action_favorited else R.string.action_favorite)
    }

    // ---- Show ------------------------------------------------------------------------------

    private fun setupShow(show: Show) {
        b.detailTitle.text = show.title
        b.favoriteButton.visibility = View.GONE
        b.restartButton.visibility = View.GONE
        b.buttonsRow.visibility = View.GONE
        loadHero(show.posterUrl, show.title)

        val parts = mutableListOf<String>()
        show.year?.let { parts.add(it) }
        val realSeasons = show.seasons.filter { it > 0 }
        if (realSeasons.isNotEmpty()) {
            parts.add(resources.getQuantityString(R.plurals.seasons_count, realSeasons.size, realSeasons.size))
        }
        b.detailMeta.text = parts.joinToString("  ·  ")
        b.detailMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        episodeAdapter = EpisodeAdapter(
            onClick = { ep -> play(ep.channel, startOver = false) },
            onLongClick = { ep, anchor -> showEpisodeMenu(ep, anchor) }
        )
        b.episodeList.layoutManager = LinearLayoutManager(this)
        b.episodeList.adapter = episodeAdapter
        b.episodeList.itemAnimator = null
        b.episodesSection.visibility = View.VISIBLE
    }

    private fun refreshShow(show: Show) {
        val progress = WatchProgress.all(this)
        val (nextEp, entry) = nextUp(show, progress)

        val code = if (nextEp.season == 0 && nextEp.number == 0) "" else MediaTitles.episodeCode(nextEp.season, nextEp.number)
        b.primaryButton.text = when {
            entry != null && code.isNotEmpty() -> getString(R.string.action_resume_episode, code, formatTime(entry.positionMs))
            entry != null -> getString(R.string.action_resume_at, formatTime(entry.positionMs))
            code.isNotEmpty() -> getString(R.string.action_play_episode, code)
            else -> getString(R.string.action_play)
        }
        b.primaryButton.setOnClickListener { play(nextEp.channel, startOver = false) }

        if (!seasonChipsBuilt) {
            selectedSeason = nextEp.season
            buildSeasonChips(show)
            seasonChipsBuilt = true
        }
        showSeason(show, progress)
    }

    /** The episode the primary button should play: the one in progress, else the one after the
     *  most recently finished, else the first. */
    private fun nextUp(show: Show, progress: Map<String, WatchProgress.Entry>): Pair<SeriesEpisode, WatchProgress.Entry?> {
        val latest = show.episodes
            .mapNotNull { ep -> progress[ep.channel.key]?.let { ep to it } }
            .maxByOrNull { it.second.updatedAt }
            ?: return show.episodes.first() to null
        val (ep, entry) = latest
        if (!entry.finished) return ep to entry
        val next = show.episodes.getOrNull(show.episodes.indexOf(ep) + 1)
        return if (next != null) next to progress[next.channel.key]?.takeIf { !it.finished } else show.episodes.first() to null
    }

    private fun buildSeasonChips(show: Show) {
        b.seasonChips.removeAllViews()
        val seasons = show.seasons
        if (seasons.size <= 1) {
            (b.seasonChips.parent as View).visibility = View.GONE
            selectedSeason = seasons.firstOrNull() ?: 0
            return
        }
        if (selectedSeason !in seasons) selectedSeason = seasons.first()
        for (season in seasons) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_season_chip, b.seasonChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = if (season == 0) getString(R.string.label_season_other) else getString(R.string.label_season_n, season)
            chip.isChecked = season == selectedSeason
            chip.setOnClickListener {
                selectedSeason = season
                showSeason(show, WatchProgress.all(this))
            }
            b.seasonChips.addView(chip)
        }
    }

    private fun showSeason(show: Show, progress: Map<String, WatchProgress.Entry>) {
        val episodes = if (show.seasons.size <= 1) show.episodes else show.episodes.filter { it.season == selectedSeason }
        episodeAdapter.submit(episodes, progress, show.posterUrl)
    }

    private fun showEpisodeMenu(ep: SeriesEpisode, anchor: View) {
        val entry = WatchProgress.get(this, ep.channel.key)
        PopupMenu(this, anchor).apply {
            if (entry == null || !entry.finished) menu.add(0, 1, 0, getString(R.string.menu_mark_watched))
            if (entry != null) menu.add(0, 2, 1, getString(R.string.menu_mark_unwatched))
            setOnMenuItemClickListener { item ->
                if (item.itemId == 1) WatchProgress.markWatched(this@DetailActivity, ep.channel.key)
                else WatchProgress.clear(this@DetailActivity, ep.channel.key)
                currentShow?.let { refreshShow(it) }
                true
            }
            show()
        }
    }

    // ---- Shared ----------------------------------------------------------------------------

    private fun play(channel: Channel, startOver: Boolean) {
        PlayerActivity.start(this, channel, PlaylistRepository.CATEGORY_ALL, startOver)
    }

    private fun loadHero(url: String?, title: String) {
        val hue = ((title.hashCode() % 360) + 360) % 360
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f))
        val bottom = Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.45f, 0.75f))
        b.heroPlaceholder.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        if (url.isNullOrBlank()) {
            b.heroImage.setImageDrawable(null)
        } else {
            Glide.with(this).load(url).into(b.heroImage)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
