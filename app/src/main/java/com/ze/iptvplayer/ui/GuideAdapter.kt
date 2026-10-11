package com.ze.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ItemGuideRowBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.EpgProgramme
import com.ze.iptvplayer.ui.guide.GuideState
import com.ze.iptvplayer.util.ChannelLogos

/** One row per channel: fixed logo on the left, the channel's programme blocks on the shared timeline. */
class GuideAdapter(
    private val state: GuideState,
    private val onChannelClick: (Channel) -> Unit,
    private val onProgrammeClick: (Channel, EpgProgramme) -> Unit
) : RecyclerView.Adapter<GuideAdapter.VH>() {

    class VH(val binding: ItemGuideRowBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<Channel> = emptyList()
    // Resolving a channel's guide (id / name matching) is the expensive part; remember it while
    // the same day is shown so scrolling back over rows is instant.
    private val cache = HashMap<String, List<EpgProgramme>>()

    fun submit(list: List<Channel>) {
        items = list
        cache.clear()
        notifyDataSetChanged()
    }

    /** The day or the EPG data changed: re-resolve every row's programmes. */
    fun invalidateProgrammes() {
        cache.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGuideRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.guideRow.state = state
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val channel = items[position]
        ChannelLogos.load(b.guideLogo, channel.logoUrl)
        b.guideLogoColumn.setOnClickListener { onChannelClick(channel) }

        val programmes = cache.getOrPut(channel.key) {
            PlaylistRepository.programmesFor(b.root.context, channel, state.windowStart, state.windowEnd)
        }
        b.guideRow.bind(channel, programmes, onProgrammeClick)
    }
}
