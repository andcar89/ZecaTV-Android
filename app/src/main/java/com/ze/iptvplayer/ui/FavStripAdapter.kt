package com.ze.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ze.iptvplayer.databinding.ItemFavChipBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.util.ChannelLogos

/** The horizontal row of favorite channels under the pinned player in the Live tab. */
class FavStripAdapter(private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<FavStripAdapter.VH>() {

    class VH(val binding: ItemFavChipBinding) : RecyclerView.ViewHolder(binding.root)

    private var channels: List<Channel> = emptyList()
    private var playingKey: String? = null

    fun submit(list: List<Channel>, playing: String?) {
        if (list == channels && playing == playingKey) return
        channels = list
        playingKey = playing
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFavChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val channel = channels[position]
        b.favName.text = channel.name
        ChannelLogos.load(b.favLogo, channel.logoUrl)
        b.favChip.alpha = if (playingKey == null || channel.key == playingKey) 1f else 0.7f
        b.favChip.setOnClickListener { onClick(channel) }
    }
}
