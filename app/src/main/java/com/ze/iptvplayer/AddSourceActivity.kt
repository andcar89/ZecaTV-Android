package com.ze.iptvplayer

import com.ze.iptvplayer.util.ThemeManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.databinding.ActivityAddSourceBinding
import com.ze.iptvplayer.model.PlaylistSource
import com.ze.iptvplayer.model.SavedPlaylist
import kotlinx.coroutines.launch

/**
 * Adds a brand-new saved playlist, or edits an existing one when launched with
 * [EXTRA_PLAYLIST_ID]. The source is loaded once to validate it before being saved.
 */
class AddSourceActivity : AppCompatActivity() {

    companion object {
        /** Id of the [SavedPlaylist] to edit. Absent means "add a new playlist" instead. */
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
    }

    private lateinit var binding: ActivityAddSourceBinding
    private var pickedFileUri: Uri? = null

    /** Null when adding a new playlist; the playlist being edited otherwise. */
    private var editingPlaylist: SavedPlaylist? = null

    /** True only for the very first playlist ever added: saving then proceeds straight into
     *  MainActivity, because this was reached from the welcome screen (nothing to go back to). */
    private var isFirstPlaylistEver = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pickedFileUri = uri
            binding.pickedFileText.text = uri.lastPathSegment ?: uri.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAddSourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstPlaylistEver = Prefs.getAllPlaylists(this).isEmpty()

        binding.sourceTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.m3uUrlFields.visibility = if (checkedId == R.id.radio_m3u_url) View.VISIBLE else View.GONE
            binding.m3uFileFields.visibility = if (checkedId == R.id.radio_m3u_file) View.VISIBLE else View.GONE
            binding.xtreamFields.visibility = if (checkedId == R.id.radio_xtream) View.VISIBLE else View.GONE
        }

        binding.pickFileButton.setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        binding.saveButton.setOnClickListener { onSaveClicked() }

        prefillFromExistingPlaylist()
    }

    /** If launched to edit an existing playlist (EXTRA_PLAYLIST_ID), prefill its current values. */
    private fun prefillFromExistingPlaylist() {
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        val playlist = playlistId?.let { id -> Prefs.getAllPlaylists(this).firstOrNull { it.id == id } }
        editingPlaylist = playlist

        binding.screenTitleText.text = getString(
            if (playlist != null) R.string.edit_playlist_title else R.string.add_source_title
        )

        if (playlist == null) {
            binding.radioM3uUrl.isChecked = true
            return
        }

        binding.playlistNameEdit.setText(playlist.name)
        when (val source = playlist.source) {
            is PlaylistSource.M3uUrl -> {
                binding.m3uUrlEdit.setText(source.url)
                binding.m3uEpgUrlEdit.setText(source.epgUrl ?: "")
                binding.radioM3uUrl.isChecked = true
            }
            is PlaylistSource.M3uFile -> {
                pickedFileUri = Uri.parse(source.filePath)
                binding.pickedFileText.text = pickedFileUri?.lastPathSegment ?: source.filePath
                binding.m3uFileEpgUrlEdit.setText(source.epgUrl ?: "")
                binding.radioM3uFile.isChecked = true
            }
            is PlaylistSource.Xtream -> {
                binding.xtreamServerEdit.setText(source.server)
                binding.xtreamUserEdit.setText(source.username)
                binding.xtreamPassEdit.setText(source.password)
                binding.radioXtream.isChecked = true
            }
        }
    }

    private fun onSaveClicked() {
        val source: PlaylistSource = when (binding.sourceTypeGroup.checkedRadioButtonId) {
            R.id.radio_m3u_url -> {
                val url = binding.m3uUrlEdit.text.toString().trim()
                if (url.isEmpty()) {
                    toast(getString(R.string.toast_enter_playlist_url))
                    return
                }
                val epg = binding.m3uEpgUrlEdit.text.toString().trim().ifBlank { null }
                PlaylistSource.M3uUrl(url, epg)
            }
            R.id.radio_m3u_file -> {
                val uri = pickedFileUri
                if (uri == null) {
                    toast(getString(R.string.toast_pick_file_first))
                    return
                }
                val epg = binding.m3uFileEpgUrlEdit.text.toString().trim().ifBlank { null }
                PlaylistSource.M3uFile(uri.toString(), epg)
            }
            R.id.radio_xtream -> {
                val server = binding.xtreamServerEdit.text.toString().trim()
                val user = binding.xtreamUserEdit.text.toString().trim()
                val pass = binding.xtreamPassEdit.text.toString().trim()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    toast(getString(R.string.toast_fill_xtream_fields))
                    return
                }
                PlaylistSource.Xtream(server, user, pass)
            }
            else -> {
                toast(getString(R.string.toast_choose_source_type))
                return
            }
        }
        val name = binding.playlistNameEdit.text.toString().trim()

        binding.saveButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@AddSourceActivity, source)
            result.onSuccess { channels ->
                if (channels.isEmpty()) {
                    toast(getString(R.string.toast_loaded_no_channels))
                    binding.saveButton.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.progressText.visibility = View.GONE
                    return@onSuccess
                }

                val editing = editingPlaylist
                val savedId: String
                if (editing != null) {
                    Prefs.updatePlaylist(this@AddSourceActivity, editing.id, name, source)
                    savedId = editing.id
                } else {
                    savedId = Prefs.addPlaylist(this@AddSourceActivity, name, source).id
                }

                // The repository now holds this playlist's channels — tell the main screen so it
                // refreshes (or reloads the active playlist, if this wasn't the active one).
                ActivePlaylistTracker.loadedId = savedId
                ActivePlaylistTracker.dirty = true

                toast(getString(R.string.toast_loaded_n_channels, channels.size))
                if (editing == null && isFirstPlaylistEver) {
                    startActivity(Intent(this@AddSourceActivity, MainActivity::class.java))
                }
                finish()
            }.onFailure { e ->
                toast(getString(R.string.toast_failed_prefix, e.message))
                binding.saveButton.isEnabled = true
                binding.progressBar.visibility = View.GONE
                binding.progressText.visibility = View.GONE
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
