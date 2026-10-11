package com.ze.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.SeriesEpisode
import com.ze.iptvplayer.data.WatchProgress
import com.ze.iptvplayer.databinding.ItemEpisodeBinding
import com.ze.iptvplayer.util.MediaTitles

/** Episode rows for the show detail screen: thumbnail, "S01E02 · Title", watch status. */
class EpisodeAdapter(
    private val onClick: (SeriesEpisode) -> Unit,
    private val onLongClick: (SeriesEpisode, View) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    class VH(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<SeriesEpisode> = emptyList()
    private var progress: Map<String, WatchProgress.Entry> = emptyMap()
    private var fallbackPoster: String? = null

    fun submit(list: List<SeriesEpisode>, progress: Map<String, WatchProgress.Entry>, fallbackPoster: String?) {
        this.items = list
        this.progress = progress
        this.fallbackPoster = fallbackPoster
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val ctx = b.root.context
        val ep = items[position]
        val entry = progress[ep.channel.key]

        b.episodeTitle.text = when {
            ep.season == 0 && ep.number == 0 -> ep.channel.name
            ep.title.isNotEmpty() -> "${MediaTitles.episodeCode(ep.season, ep.number)} · ${ep.title}"
            else -> MediaTitles.episodeCode(ep.season, ep.number)
        }

        val poster = ep.channel.logoUrl?.takeIf { it.isNotBlank() } ?: fallbackPoster
        if (poster.isNullOrBlank()) {
            Glide.with(b.episodeThumb).clear(b.episodeThumb)
            b.episodeThumb.setImageDrawable(null)
        } else {
            Glide.with(b.episodeThumb).load(poster).override(320, 180).into(b.episodeThumb)
        }

        when {
            entry == null -> {
                b.episodeStatus.text = ""
                b.episodeProgress.visibility = View.GONE
            }
            entry.finished -> {
                b.episodeStatus.text = ctx.getString(R.string.label_watched)
                b.episodeProgress.visibility = View.GONE
            }
            else -> {
                val minLeft = ((entry.durationMs - entry.positionMs) / 60_000L).toInt().coerceAtLeast(1)
                b.episodeStatus.text = ctx.getString(R.string.label_min_left, minLeft)
                b.episodeProgress.visibility = View.VISIBLE
                b.episodeProgress.progress = (entry.fraction * 100).toInt()
            }
        }
        b.episodeStatus.visibility = if (b.episodeStatus.text.isEmpty()) View.GONE else View.VISIBLE

        b.root.setOnClickListener { onClick(ep) }
        b.root.setOnLongClickListener {
            onLongClick(ep, it)
            true
        }
    }
}
