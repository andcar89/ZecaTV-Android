package com.ze.iptvplayer.ui

import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ItemChannelBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.util.ChannelLogos
import java.util.Date

/**
 * One row per channel: logo, name, current programme with a progress bar, and a favorite star.
 * [compact] drops the progress bar and star (used by the fullscreen player's channel panel).
 */
class ChannelAdapter(
    private val compact: Boolean,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel, View) -> Unit = { _, _ -> },
    private val onFavorite: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var playingKey: String? = null

    fun submit(list: List<Channel>, favs: Set<String>) {
        items = list
        favorites = favs
        notifyDataSetChanged()
    }

    fun setFavorites(favs: Set<String>) {
        favorites = favs
        notifyDataSetChanged()
    }

    fun setPlayingKey(key: String?) {
        if (key == playingKey) return
        playingKey = key
        notifyDataSetChanged()
    }

    /** Re-binds every visible row so programme titles and progress bars move along with the clock. */
    fun refreshTimes() = notifyDataSetChanged()

    fun positionOf(key: String): Int = items.indexOfFirst { it.key == key }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val ctx = b.root.context
        val channel = items[position]
        val playing = channel.key == playingKey

        b.itemRoot.isSelected = playing
        b.channelName.text = channel.name
        ChannelLogos.load(b.channelLogo, channel.logoUrl)
        b.playingIcon.visibility = if (playing) View.VISIBLE else View.GONE

        val (now, _) = PlaylistRepository.nowNext(ctx, channel)
        if (now != null) {
            val timeFormat = DateFormat.getTimeFormat(ctx)
            b.channelProgramme.text = "${now.title} · ${timeFormat.format(Date(now.stopMillis))}"
            val length = (now.stopMillis - now.startMillis).coerceAtLeast(1L)
            val elapsed = System.currentTimeMillis() - now.startMillis
            b.channelProgress.progress = (elapsed * 100 / length).toInt().coerceIn(0, 100)
            b.channelProgress.visibility = if (compact) View.GONE else View.VISIBLE
        } else {
            b.channelProgramme.text = ctx.getString(R.string.label_no_programme)
            b.channelProgress.visibility = if (compact) View.GONE else View.INVISIBLE
        }

        if (compact) {
            b.favoriteButton.visibility = View.GONE
        } else {
            val isFavorite = channel.key in favorites
            b.favoriteButton.visibility = View.VISIBLE
            b.favoriteButton.setImageResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_border)
            b.favoriteButton.imageTintList = ColorStateList.valueOf(
                if (isFavorite) ContextCompat.getColor(ctx, R.color.favorite_gold_color) else com.ze.iptvplayer.util.ThemeManager.color(ctx, R.attr.zTextSecondary)
            )
            b.favoriteButton.setOnClickListener { onFavorite(channel) }
        }

        b.root.setOnClickListener { onClick(channel) }
        b.root.setOnLongClickListener {
            onLongClick(channel, it)
            true
        }
    }
}
