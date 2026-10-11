package com.ze.iptvplayer.ui

import com.ze.iptvplayer.databinding.ItemSwitchRowBinding
import com.ze.iptvplayer.databinding.ItemThemeCardBinding
import com.ze.iptvplayer.util.ThemeManager
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ze.iptvplayer.AddSourceActivity
import com.ze.iptvplayer.ChannelMappingActivity
import com.ze.iptvplayer.EpgSourcesActivity
import com.ze.iptvplayer.LoadState
import com.ze.iptvplayer.MainViewModel
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.LongPressAction
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.databinding.FragmentMoreBinding
import com.ze.iptvplayer.databinding.ItemPlaylistBinding
import com.ze.iptvplayer.databinding.ItemSettingRowBinding
import com.ze.iptvplayer.model.PlaylistSource
import com.ze.iptvplayer.model.SavedPlaylist
import kotlinx.coroutines.launch

/** More tab: active playlist card, playlist management and the "built with AI" note. */
class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val b get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.refreshButton.setOnClickListener { vm.reload() }
        b.addButton.setOnClickListener {
            startActivity(Intent(requireContext(), AddSourceActivity::class.java))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    b.refreshButton.isEnabled = state !is LoadState.Loading
                    b.refreshButton.setText(if (state is LoadState.Loading) R.string.more_refreshing else R.string.more_refresh)
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun describe(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3uUrl -> getString(R.string.playlist_source_m3u_url, source.url)
        is PlaylistSource.M3uFile ->
            getString(R.string.playlist_source_m3u_file, Uri.parse(source.filePath).lastPathSegment ?: source.filePath)
        is PlaylistSource.Xtream -> getString(R.string.playlist_source_xtream, source.server, source.username)
    }

    private fun render() {
        if (_binding == null) return
        val ctx = requireContext()
        val active = Prefs.activePlaylist(ctx)
        val all = Prefs.getAllPlaylists(ctx)

        b.activeName.text = active?.name ?: getString(R.string.label_no_playlists)
        val loadedCount = PlaylistRepository.channels.size
        b.activeSubtitle.text = when {
            active == null -> ""
            vm.state.value is LoadState.Ready && loadedCount > 0 ->
                getString(R.string.channels_count, loadedCount) + " · " + describe(active.source)
            else -> describe(active.source)
        }

        b.playlistsContainer.removeAllViews()
        all.forEach { playlist ->
            addDividerIfNeeded(b.playlistsContainer, force = true)
            b.playlistsContainer.addView(buildRow(playlist, playlist.id == active?.id))
        }
        renderThemes()
        renderLayoutOptions()
        renderSettings()
    }

    // ---- Layout options (banner / pinned player) -------------------------------------------

    private fun renderLayoutOptions() {
        val ctx = requireContext()
        b.layoutContainer.removeAllViews()
        addSwitchRow(R.string.more_banner_title, R.string.more_banner_subtitle, UiPrefs.getBanner(ctx)) {
            UiPrefs.setBanner(ctx, it)
        }
        addSwitchRow(R.string.more_pinned_title, R.string.more_pinned_subtitle, UiPrefs.getPinnedPlayer(ctx)) {
            UiPrefs.setPinnedPlayer(ctx, it)
        }
    }

    private fun addSwitchRow(titleRes: Int, subtitleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        addDividerIfNeeded(b.layoutContainer, force = true)
        val row = ItemSwitchRowBinding.inflate(layoutInflater, b.layoutContainer, false)
        row.switchTitle.setText(titleRes)
        row.switchSubtitle.setText(subtitleRes)
        row.switchWidget.isChecked = checked
        row.switchWidget.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        row.switchRoot.setOnClickListener { row.switchWidget.toggle() }
        b.layoutContainer.addView(row.root)
    }

    // ---- Theme -----------------------------------------------------------------------------

    private fun renderThemes() {
        val ctx = requireContext()
        val currentId = ThemeManager.current(ctx).id
        val density = resources.displayMetrics.density
        b.themeContainer.removeAllViews()
        for (theme in ThemeManager.all) {
            val card = ItemThemeCardBinding.inflate(layoutInflater, b.themeContainer, false)
            card.themeName.text = getString(theme.nameRes)
            card.themePreview.background = GradientDrawable().apply {
                setColor(theme.previewBg)
                cornerRadius = 10f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), ThemeManager.color(ctx, R.attr.zOutline))
            }
            card.themePreviewSurface.background = GradientDrawable().apply {
                setColor(theme.previewSurface)
                cornerRadius = 6f * density
            }
            card.themePreviewAccent.background = GradientDrawable().apply {
                setColor(theme.previewAccent)
                cornerRadius = 4f * density
            }
            val selected = theme.id == currentId
            card.themeCard.background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setStroke(
                    (2f * density).toInt(),
                    if (selected) ThemeManager.color(ctx, R.attr.zAccent) else Color.TRANSPARENT
                )
            }
            card.themeCard.setOnClickListener {
                if (!selected) {
                    ThemeManager.set(ctx, theme.id)
                    requireActivity().recreate()
                }
            }
            b.themeContainer.addView(card.root)
        }
    }

    // ---- Settings --------------------------------------------------------------------------

    private fun renderSettings() {
        val ctx = requireContext()
        b.epgContainer.removeAllViews()
        b.longPressContainer.removeAllViews()
        b.languageContainer.removeAllViews()

        // EPG card: sources + per-channel assignment.
        val sourceCount = PlaylistRepository.availableEpgSources(ctx).size
        addSettingRow(
            b.epgContainer,
            getString(R.string.more_epg_sources_title),
            if (sourceCount == 0) getString(R.string.label_no_epg_sources) else getString(R.string.more_epg_sources_subtitle, sourceCount)
        ) { startActivity(Intent(ctx, EpgSourcesActivity::class.java)) }

        addSettingRow(b.epgContainer, getString(R.string.mapping_title), getString(R.string.more_assign_epg_subtitle)) {
            ChannelMappingActivity.start(ctx)
        }

        // Long press card.
        val longPress = UiPrefs.getLongPressAction(ctx)
        addSettingRow(
            b.longPressContainer,
            getString(if (longPress == LongPressAction.QUICK_FAVORITE) R.string.radio_long_press_favorite else R.string.radio_long_press_menu),
            getString(R.string.more_long_press_title)
        ) { showLongPressDialog() }

        // About card: replay the fullscreen guide.
        b.aboutContainer.removeAllViews()
        addSettingRow(b.aboutContainer, getString(R.string.more_cast_note_title), getString(R.string.more_cast_note_body)) { }
        addSettingRow(b.aboutContainer, getString(R.string.more_tutorial_title), getString(R.string.more_tutorial_subtitle), force = true) {
            UiPrefs.setTutorialSeen(ctx, false)
            android.widget.Toast.makeText(ctx, R.string.more_tutorial_toast, android.widget.Toast.LENGTH_SHORT).show()
        }

        // Language card.
        addSettingRow(b.languageContainer, currentLanguageLabel(), null) { showLanguageDialog() }
    }

    /** Thin line between two rows of a card (not before the first, unless [force]). */
    private fun addDividerIfNeeded(container: android.widget.LinearLayout, force: Boolean = false) {
        if (container.childCount == 0 && !force) return
        val line = View(requireContext())
        line.setBackgroundColor(ThemeManager.color(requireContext(), R.attr.zOutline))
        val lp = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        val margin = (16 * resources.displayMetrics.density).toInt()
        lp.marginStart = margin
        lp.marginEnd = margin
        container.addView(line, lp)
    }

    private fun addSettingRow(container: android.widget.LinearLayout, title: String, subtitle: String?, force: Boolean = false, onClick: () -> Unit) {
        addDividerIfNeeded(container, force)
        val row = ItemSettingRowBinding.inflate(layoutInflater, container, false)
        row.settingTitle.text = title
        row.settingSubtitle.text = subtitle.orEmpty()
        row.settingSubtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.settingRoot.setOnClickListener { onClick() }
        container.addView(row.root)
    }

    private fun showLongPressDialog() {
        val ctx = requireContext()
        val labels = arrayOf(getString(R.string.radio_long_press_menu), getString(R.string.radio_long_press_favorite))
        val checked = if (UiPrefs.getLongPressAction(ctx) == LongPressAction.QUICK_FAVORITE) 1 else 0
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.more_long_press_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                UiPrefs.setLongPressAction(
                    ctx,
                    if (which == 1) LongPressAction.QUICK_FAVORITE else LongPressAction.SHOW_MENU
                )
                dialog.dismiss()
                renderSettings()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun currentLanguageIndex(): Int {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty()) return 0
        return if (locales.get(0)?.language == "pt") 2 else 1
    }

    private fun currentLanguageLabel(): String = when (currentLanguageIndex()) {
        2 -> getString(R.string.radio_language_portuguese)
        1 -> getString(R.string.radio_language_english)
        else -> getString(R.string.language_system)
    }

    private fun showLanguageDialog() {
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.radio_language_english),
            getString(R.string.radio_language_portuguese)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_header)
            .setSingleChoiceItems(labels, currentLanguageIndex()) { dialog, which ->
                dialog.dismiss()
                // Android stores this per app and recreates the screens in the new language.
                AppCompatDelegate.setApplicationLocales(
                    when (which) {
                        1 -> LocaleListCompat.forLanguageTags("en")
                        2 -> LocaleListCompat.forLanguageTags("pt")
                        else -> LocaleListCompat.getEmptyLocaleList()
                    }
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun buildRow(playlist: SavedPlaylist, isActive: Boolean): View {
        val row = ItemPlaylistBinding.inflate(layoutInflater, b.playlistsContainer, false)
        row.playlistName.text = playlist.name
        row.playlistSource.text = describe(playlist.source)
        row.playlistActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE
        row.playlistUseButton.visibility = if (isActive) View.GONE else View.VISIBLE
        row.playlistUseButton.setOnClickListener { vm.switchPlaylist(playlist.id) }
        row.playlistEditButton.setOnClickListener {
            startActivity(
                Intent(requireContext(), AddSourceActivity::class.java)
                    .putExtra(AddSourceActivity.EXTRA_PLAYLIST_ID, playlist.id)
            )
        }
        row.playlistDeleteButton.setOnClickListener { confirmDelete(playlist) }
        return row.root
    }

    private fun confirmDelete(playlist: SavedPlaylist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.more_confirm_delete_title)
            .setMessage(getString(R.string.more_confirm_delete_body, playlist.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Prefs.removePlaylist(requireContext(), playlist.id)
                vm.syncWithPrefs()
                render()
            }
            .show()
    }
}
