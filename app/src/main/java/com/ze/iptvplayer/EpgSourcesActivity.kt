package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ze.iptvplayer.data.EpgSourceManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ActivityEpgSourcesBinding
import com.ze.iptvplayer.databinding.ItemEpgSourceBinding
import kotlinx.coroutines.launch

/** EPG (XMLTV) sources: the playlist's own guide plus any extra ones you add, with load status. */
class EpgSourcesActivity : AppCompatActivity() {

    private lateinit var b: ActivityEpgSourcesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityEpgSourcesBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.backButton.setOnClickListener { finish() }
        b.refreshEpgButton.setOnClickListener { onRefresh() }
        b.addSourceButton.setOnClickListener { onAdd() }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun showStatus(text: String) {
        b.statusText.visibility = View.VISIBLE
        b.statusText.text = text
    }

    private fun onRefresh() {
        b.refreshEpgButton.isEnabled = false
        showStatus(getString(R.string.status_refreshing_epg))
        lifecycleScope.launch {
            PlaylistRepository.reloadAllEpg(this@EpgSourcesActivity)
            showStatus(getString(R.string.status_epg_refreshed))
            b.refreshEpgButton.isEnabled = true
            refreshList()
        }
    }

    private fun onAdd() {
        val name = b.newSourceNameEdit.text.toString().trim()
        val url = b.newSourceUrlEdit.text.toString().trim()
        if (name.isEmpty() || url.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_name_and_url, Toast.LENGTH_SHORT).show()
            return
        }
        val source = EpgSourceManager.add(this, name, url)
        b.newSourceNameEdit.text?.clear()
        b.newSourceUrlEdit.text?.clear()
        refreshList()
        showStatus(getString(R.string.status_loading_named, name))
        lifecycleScope.launch {
            PlaylistRepository.reloadEpgSource(source.id, source.url)
            showStatus(getString(R.string.status_loaded_named, name))
            refreshList()
        }
    }

    private fun applyStatus(row: ItemEpgSourceBinding, sourceId: String) {
        val status = PlaylistRepository.epgSourceStatus(sourceId)
        when {
            status.errorMessage != null -> {
                row.sourceStatus.text = getString(R.string.epg_status_error, status.errorMessage)
                row.sourceStatus.setTextColor(0xFFE57373.toInt())
            }
            status.isLoaded -> {
                row.sourceStatus.text = getString(R.string.epg_status_loaded, status.channelCount, status.programmeCount)
                row.sourceStatus.setTextColor(0xFF6FA8DC.toInt())
            }
            else -> {
                row.sourceStatus.text = getString(R.string.epg_status_not_loaded)
                row.sourceStatus.setTextColor(com.ze.iptvplayer.util.ThemeManager.color(this@EpgSourcesActivity, R.attr.zTextSecondary))
            }
        }
    }

    private fun applyActiveToggle(row: ItemEpgSourceBinding, sourceId: String) {
        val isActive = PlaylistRepository.activeEpgSourceOverride(this) == sourceId
        row.activeBadge.visibility = if (isActive) View.VISIBLE else View.GONE
        row.activeNote.visibility = if (isActive) View.VISIBLE else View.GONE
        row.toggleActiveButton.setText(if (isActive) R.string.action_deactivate else R.string.action_activate)
        row.toggleActiveButton.setOnClickListener {
            val current = PlaylistRepository.activeEpgSourceOverride(this)
            PlaylistRepository.setActiveEpgSourceOverride(this, if (current == sourceId) null else sourceId)
            refreshList()
        }
    }

    private fun refreshList() {
        b.sourcesList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val all = PlaylistRepository.availableEpgSources(this)

        // The playlist's own guide(s): can be activated, never removed.
        all.filter { EpgSourceManager.isPrimarySourceId(it.first) }.forEach { (id, label) ->
            val row = ItemEpgSourceBinding.inflate(inflater, b.sourcesList, false)
            row.sourceName.text = label
            row.sourceUrl.setText(R.string.label_loaded_automatically)
            row.removeSourceButton.visibility = View.GONE
            applyStatus(row, id)
            applyActiveToggle(row, id)
            b.sourcesList.addView(row.root)
        }

        EpgSourceManager.getAll(this).forEach { source ->
            val row = ItemEpgSourceBinding.inflate(inflater, b.sourcesList, false)
            row.sourceName.text = source.name
            row.sourceUrl.text = source.url
            applyStatus(row, source.id)
            applyActiveToggle(row, source.id)
            row.removeSourceButton.setOnClickListener {
                EpgSourceManager.remove(this, source.id)
                refreshList()
            }
            b.sourcesList.addView(row.root)
        }

        if (all.isEmpty()) {
            b.sourcesList.addView(TextView(this).apply {
                setText(R.string.label_no_epg_sources)
                setTextColor(com.ze.iptvplayer.util.ThemeManager.color(this@EpgSourcesActivity, R.attr.zTextSecondary))
            })
        }
    }
}
