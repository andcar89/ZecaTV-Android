package com.ze.iptvplayer.data

import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.util.MediaTitles
import java.util.Locale

class SeriesEpisode(val channel: Channel, val season: Int, val number: Int, val title: String)

class Show(
    val key: String,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val groups: Set<String>,
    val episodes: List<SeriesEpisode>
) {
    val seasons: List<Int> = episodes.map { it.season }.distinct().sorted()
}

/**
 * Groups the playlist's TV-show episode entries (each its own "Show S01E02 …" entry) into shows,
 * seasons and episodes. Rebuilt lazily whenever the playlist's channel list is replaced.
 */
object SeriesIndex {
    private var source: List<Channel>? = null
    private var shows: List<Show> = emptyList()
    private var byKey: Map<String, Show> = emptyMap()
    private var byEpisodeKey: Map<String, Show> = emptyMap()

    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")

    fun shows(): List<Show> {
        ensure()
        return shows
    }

    fun show(key: String): Show? {
        ensure()
        return byKey[key]
    }

    fun showForEpisode(channelKey: String): Show? {
        ensure()
        return byEpisodeKey[channelKey]
    }

    /** The episode that follows [channel] in its show, or null for the last one. */
    fun nextEpisode(channel: Channel): Channel? {
        val show = showForEpisode(channel.key) ?: return null
        val index = show.episodes.indexOfFirst { it.channel.key == channel.key }
        return show.episodes.getOrNull(index + 1)?.channel
    }

    @Synchronized
    private fun ensure() {
        val channels = PlaylistRepository.channels
        if (channels === source) return
        source = channels
        rebuild(channels)
    }

    private fun rebuild(channels: List<Channel>) {
        class Draft(val title: String, var year: String?) {
            val items = mutableListOf<Pair<Channel, MediaTitles.Episode?>>()
        }

        val drafts = LinkedHashMap<String, Draft>()
        for (c in channels) {
            if (c.contentType != ContentType.SERIES) continue
            val ep = MediaTitles.episode(c.name)
            val title = ep?.showTitle ?: c.name
            val key = title.lowercase(Locale.ROOT).replace(nonAlnum, " ").trim().ifEmpty { c.key }
            val draft = drafts.getOrPut(key) { Draft(title, ep?.showYear) }
            if (draft.year == null) draft.year = ep?.showYear
            draft.items.add(c to ep)
        }

        val list = ArrayList<Show>(drafts.size)
        val episodeMap = HashMap<String, Show>()
        for ((key, d) in drafts) {
            val episodes = d.items
                .map { (c, ep) -> SeriesEpisode(c, ep?.season ?: 0, ep?.number ?: 0, ep?.title.orEmpty()) }
                .sortedWith(compareBy({ it.season }, { it.number }))
            val show = Show(
                key = key,
                title = d.title,
                year = d.year,
                posterUrl = d.items.firstOrNull { !it.first.logoUrl.isNullOrBlank() }?.first?.logoUrl,
                groups = d.items.map { it.first.groupTitle }.toCollection(LinkedHashSet()),
                episodes = episodes
            )
            list.add(show)
            episodes.forEach { episodeMap[it.channel.key] = show }
        }
        shows = list
        byKey = list.associateBy { it.key }
        byEpisodeKey = episodeMap
    }
}
