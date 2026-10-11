package com.ze.iptvplayer.data

import android.content.Context
import com.ze.iptvplayer.R
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.util.MediaTitles

/** One poster in the Movies / TV Shows grids: a movie, or a whole show. [key] is the movie's
 *  stream URL, or the show's [Show.key]. */
data class VodItem(
    val key: String,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val isShow: Boolean,
    val progress: Float
)

/** Builds the category list and poster items for the Movies and TV Shows tabs. */
object VodRepository {
    const val CATEGORY_CONTINUE = "__continue__"

    data class Category(val id: String, val label: String, val count: Int)

    // Title parsing (regex per entry) is by far the costliest part of building a grid with tens of
    // thousands of movies, so the progress-free items are built once per loaded playlist.
    private var baseSource: List<Channel>? = null
    private val baseItems = HashMap<String, VodItem>()

    private fun baseItem(channel: Channel): VodItem = synchronized(baseItems) {
        val all = PlaylistRepository.channels
        if (baseSource !== all) {
            baseSource = all
            baseItems.clear()
        }
        baseItems.getOrPut(channel.key) {
            val movie = MediaTitles.movie(channel.name)
            VodItem(channel.key, movie.title, movie.year, channel.logoUrl, false, 0f)
        }
    }

    fun movieItem(channel: Channel, progress: Float = 0f): VodItem {
        val base = baseItem(channel)
        return if (progress > 0f) base.copy(progress = progress) else base
    }

    private var countsSource: List<Show>? = null
    private var groupCounts: Map<String, Int> = emptyMap()

    private fun showGroupCounts(): Map<String, Int> = synchronized(this) {
        val shows = SeriesIndex.shows()
        if (countsSource !== shows) {
            val counts = HashMap<String, Int>()
            for (show in shows) for (g in show.groups) counts[g] = (counts[g] ?: 0) + 1
            groupCounts = counts
            countsSource = shows
        }
        groupCounts
    }

    private fun showItem(show: Show, progress: Float = 0f) =
        VodItem(show.key, show.title, show.year, show.posterUrl, true, progress)

    private fun movieChannels(): List<Channel> = PlaylistRepository.channelsOfType(ContentType.MOVIE)

    private fun continueItems(context: Context, type: ContentType): List<VodItem> {
        val recent = WatchProgress.recent(context)
        if (recent.isEmpty()) return emptyList()
        return if (type == ContentType.MOVIE) {
            val byKey = movieChannels().associateBy { it.key }
            recent.mapNotNull { (key, entry) -> byKey[key]?.let { movieItem(it, entry.fraction) } }
        } else {
            val seen = HashSet<String>()
            recent.mapNotNull { (key, entry) ->
                val show = SeriesIndex.showForEpisode(key) ?: return@mapNotNull null
                if (seen.add(show.key)) showItem(show, entry.fraction) else null
            }
        }
    }

    fun categories(context: Context, type: ContentType): List<Category> {
        val list = mutableListOf<Category>()
        val continueCount = continueItems(context, type).size
        if (continueCount > 0) {
            list.add(Category(CATEGORY_CONTINUE, context.getString(R.string.label_continue_watching), continueCount))
        }
        if (type == ContentType.MOVIE) {
            val favorites = FavoritesManager.getAll(context)
            val movies = movieChannels()
            val favoriteCount = movies.count { it.key in favorites }
            if (favoriteCount > 0) {
                list.add(Category(PlaylistRepository.CATEGORY_FAVORITES, context.getString(R.string.category_favorites), favoriteCount))
            }
            list.add(Category(PlaylistRepository.CATEGORY_ALL, context.getString(R.string.category_all_movies), movies.size))
            // Playlist order, not alphabetical — same rule as Live TV.
            PlaylistRepository.channelsByGroup(ContentType.MOVIE).forEach { (group, items) ->
                list.add(Category(group, groupLabel(context, group), items.size))
            }
        } else {
            val shows = SeriesIndex.shows()
            val counts = showGroupCounts()
            list.add(Category(PlaylistRepository.CATEGORY_ALL, context.getString(R.string.category_all_series), shows.size))
            PlaylistRepository.channelsByGroup(ContentType.SERIES).keys.forEach { group ->
                list.add(Category(group, groupLabel(context, group), counts[group] ?: 0))
            }
        }
        return list
    }

    fun groupLabel(context: Context, group: String): String =
        if (group == Channel.UNGROUPED_GROUP_KEY) context.getString(R.string.category_uncategorized) else group

    fun categoryLabel(context: Context, type: ContentType, id: String): String = when (id) {
        CATEGORY_CONTINUE -> context.getString(R.string.label_continue_watching)
        PlaylistRepository.CATEGORY_FAVORITES -> context.getString(R.string.category_favorites)
        PlaylistRepository.CATEGORY_ALL ->
            context.getString(if (type == ContentType.MOVIE) R.string.category_all_movies else R.string.category_all_series)
        else -> groupLabel(context, id)
    }

    /** Items for [categoryId], or — when [query] is not blank — a title search across the whole section. */
    fun items(context: Context, type: ContentType, categoryId: String, query: String): List<VodItem> {
        val q = query.trim()
        if (categoryId == CATEGORY_CONTINUE && q.isEmpty()) return continueItems(context, type)
        val progress = WatchProgress.all(context)

        if (type == ContentType.MOVIE) {
            val source: List<Channel> = when {
                q.isNotEmpty() -> movieChannels().filter { it.name.contains(q, ignoreCase = true) }.take(500)
                categoryId == PlaylistRepository.CATEGORY_FAVORITES -> {
                    val favorites = FavoritesManager.getAll(context)
                    movieChannels().filter { it.key in favorites }
                }
                categoryId == PlaylistRepository.CATEGORY_ALL -> movieChannels()
                else -> PlaylistRepository.channelsByGroup(ContentType.MOVIE)[categoryId] ?: emptyList()
            }
            return source.map { c ->
                val entry = progress[c.key]
                movieItem(c, if (entry != null && !entry.finished) entry.fraction else 0f)
            }
        }

        val shows = SeriesIndex.shows()
        val source = when {
            q.isNotEmpty() -> shows.filter { it.title.contains(q, ignoreCase = true) }.take(500)
            categoryId == PlaylistRepository.CATEGORY_ALL -> shows
            else -> shows.filter { categoryId in it.groups }
        }
        return source.map { showItem(it) }
    }
}
