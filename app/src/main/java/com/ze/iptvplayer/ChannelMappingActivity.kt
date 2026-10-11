package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ze.iptvplayer.data.EpgMappingStore
import com.ze.iptvplayer.data.EpgSourceManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ActivityChannelMappingBinding
import com.ze.iptvplayer.databinding.ItemMappingBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.model.EpgChannelMapping
import com.ze.iptvplayer.ui.SearchPicker
import kotlinx.coroutines.launch

/**
 * Point one channel at a specific guide entry: pick the channel, the EPG source, and which guide
 * channel inside that source should supply its programmes. Each pick opens a searchable list.
 */
class ChannelMappingActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PRESELECT_CHANNEL_KEY = "extra_preselect_channel_key"

        fun start(context: Context, preselectChannelKey: String? = null) {
            context.startActivity(
                Intent(context, ChannelMappingActivity::class.java)
                    .putExtra(EXTRA_PRESELECT_CHANNEL_KEY, preselectChannelKey)
            )
        }
    }

    private lateinit var b: ActivityChannelMappingBinding

    private var channels: List<Channel> = emptyList()
    private var sources: List<Pair<String, String>> = emptyList() // (id, name)
    private var guideIds: List<String> = emptyList()
    private var guideLabels: List<String> = emptyList()

    private var channelIndex = -1
    private var sourceIndex = -1
    private var guideIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityChannelMappingBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.backButton.setOnClickListener { finish() }

        // Only live channels ever have a guide entry.
        channels = PlaylistRepository.channels
            .filter { it.contentType == ContentType.LIVE }
            .sortedBy { it.name.lowercase() }
        sources = PlaylistRepository.availableEpgSources(this)

        intent.getStringExtra(EXTRA_PRESELECT_CHANNEL_KEY)?.let { key ->
            channelIndex = channels.indexOfFirst { it.key == key }
        }
        if (sources.isNotEmpty()) selectSource(0)

        b.pickChannel.pickerLabel.setText(R.string.mapping_label_channel)
        b.pickSource.pickerLabel.setText(R.string.label_epg_source)
        b.pickGuide.pickerLabel.setText(R.string.label_guide_channel)

        b.pickChannel.pickerRoot.setOnClickListener {
            SearchPicker.show(this, getString(R.string.mapping_label_channel), channels.map { it.name }) {
                channelIndex = it
                renderPickers()
            }
        }
        b.pickSource.pickerRoot.setOnClickListener {
            if (sources.isEmpty()) return@setOnClickListener
            SearchPicker.show(this, getString(R.string.label_epg_source), sources.map { it.second }) { selectSource(it) }
        }
        b.pickGuide.pickerRoot.setOnClickListener {
            if (guideLabels.isEmpty()) return@setOnClickListener
            SearchPicker.show(this, getString(R.string.label_guide_channel), guideLabels) {
                guideIndex = it
                renderPickers()
            }
        }
        b.saveMappingButton.setOnClickListener { onSave() }

        renderPickers()
        refreshMappings()
    }

    private fun renderPickers() {
        val none = getString(R.string.mapping_tap_to_choose)
        b.pickChannel.pickerValue.text = channels.getOrNull(channelIndex)?.name ?: none
        b.pickSource.pickerValue.text = sources.getOrNull(sourceIndex)?.second ?: getString(R.string.label_no_epg_sources)
        b.pickGuide.pickerValue.text = guideLabels.getOrNull(guideIndex)
            ?: if (guideLabels.isEmpty()) getString(R.string.label_no_guide_channels_loaded) else none
    }

    /** Picks an EPG source and loads the list of guide channels inside it. */
    private fun selectSource(position: Int) {
        if (position !in sources.indices) return
        sourceIndex = position
        guideIndex = -1
        val (sourceId, _) = sources[position]
        val names = PlaylistRepository.channelNamesForSource(sourceId)

        if (names.isEmpty() && !EpgSourceManager.isPrimarySourceId(sourceId)) {
            // An extra source that hasn't been fetched yet this session: fetch it now.
            val source = EpgSourceManager.getAll(this).firstOrNull { it.id == sourceId }
            if (source != null) {
                guideIds = emptyList()
                guideLabels = emptyList()
                renderPickers()
                lifecycleScope.launch {
                    PlaylistRepository.reloadEpgSource(source.id, source.url)
                    if (sourceIndex == position) selectSource(position)
                }
                return
            }
        }

        guideIds = names.keys.sortedBy { names[it]?.lowercase() ?: it }
        guideLabels = guideIds.map { id -> "${names[id] ?: id} ($id)" }
        renderPickers()
    }

    private fun onSave() {
        if (channelIndex !in channels.indices || sourceIndex !in sources.indices || guideIndex !in guideIds.indices) {
            Toast.makeText(this, R.string.toast_pick_channel_source_guide, Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channels[channelIndex]
        val guideChannelId = guideIds[guideIndex]
        EpgMappingStore.setMapping(this, EpgChannelMapping(channel.key, sources[sourceIndex].first, guideChannelId))
        b.statusText.visibility = View.VISIBLE
        b.statusText.text = getString(R.string.status_saved_mapping, channel.name, guideChannelId)
        refreshMappings()
    }

    private fun refreshMappings() {
        b.mappingsList.removeAllViews()
        val mappings = EpgMappingStore.getAll(this)
        if (mappings.isEmpty()) {
            b.mappingsList.addView(TextView(this).apply {
                setText(R.string.label_no_mappings_yet)
                setTextColor(com.ze.iptvplayer.util.ThemeManager.color(this@ChannelMappingActivity, R.attr.zTextSecondary))
            })
            return
        }
        val inflater = LayoutInflater.from(this)
        mappings.values.forEach { mapping ->
            val row = ItemMappingBinding.inflate(inflater, b.mappingsList, false)
            val channelName = channels.firstOrNull { it.key == mapping.channelId }?.name ?: mapping.channelId
            val sourceName = sources.firstOrNull { it.first == mapping.epgSourceId }?.second ?: mapping.epgSourceId
            val guideName = PlaylistRepository.channelNamesForSource(mapping.epgSourceId)[mapping.epgChannelId]
                ?: mapping.epgChannelId
            row.mappingText.text = getString(R.string.mapping_row_format, channelName, sourceName, guideName)
            row.removeMappingButton.setOnClickListener {
                EpgMappingStore.clearMapping(this, mapping.channelId)
                refreshMappings()
            }
            b.mappingsList.addView(row.root)
        }
    }
}
