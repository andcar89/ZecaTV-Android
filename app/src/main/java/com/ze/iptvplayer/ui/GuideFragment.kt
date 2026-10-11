package com.ze.iptvplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ze.iptvplayer.LoadState
import com.ze.iptvplayer.MainViewModel
import com.ze.iptvplayer.PlayerActivity
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.FragmentGuideBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.model.EpgProgramme
import com.ze.iptvplayer.ui.guide.GuideState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The TV Guide tab: channels down the left, one scrollable timeline across (drag sideways on any
 * row), a red "now" line, one day at a time. Tap a logo to watch, tap a programme for details.
 */
class GuideFragment : Fragment() {

    private val vm: MainViewModel by activityViewModels()
    private var _binding: FragmentGuideBinding? = null
    private val b get() = _binding!!

    private lateinit var state: GuideState
    private lateinit var adapter: GuideAdapter

    private var categoryId = PlaylistRepository.CATEGORY_ALL
    private var dayOffset = 0
    private var query = ""
    private var searching = false
    private var shownGeneration = -1

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (_binding == null) return
            state.notifyChanged()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGuideBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        state = GuideState(ctx, pxPerMinute = 4f * resources.displayMetrics.density)
        b.timeHeader.state = state
        b.nowLine.state = state

        adapter = GuideAdapter(
            state,
            onChannelClick = { channel -> watch(channel) },
            onProgrammeClick = { channel, programme -> showProgramme(channel, programme) }
        )
        b.guideRows.layoutManager = LinearLayoutManager(ctx)
        b.guideRows.adapter = adapter
        b.guideRows.itemAnimator = null
        b.guideRows.setHasFixedSize(true)

        b.dayButton.setOnClickListener { showDayMenu(it) }
        b.categoryBar.setOnClickListener {
            CategorySheet.newInstance(ContentType.LIVE, categoryId).show(childFragmentManager, "categories")
        }
        b.searchButton.setOnClickListener { if (searching) closeSearch() else openSearch() }
        b.searchEdit.addTextChangedListener {
            query = it?.toString().orEmpty()
            refreshChannels()
        }
        b.searchEdit.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }

        childFragmentManager.setFragmentResultListener(CategorySheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(CategorySheet.RESULT_ID)?.let {
                categoryId = it
                if (searching) closeSearch() else refreshChannels()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        applyDay(scrollToNow = true)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    if (s is LoadState.Ready && s.generation != shownGeneration) {
                        shownGeneration = s.generation
                        rebuild()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 60_000L)
        // EPG data may have finished loading since the rows were last bound.
        if (!isHidden && shownGeneration >= 0) adapter.invalidateProgrammes()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (hidden) {
            if (searching) closeSearch() else hideKeyboard()
        } else {
            adapter.invalidateProgrammes()
            state.notifyChanged()
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    // ---- Data ------------------------------------------------------------------------------

    private fun rebuild() {
        val ctx = requireContext()
        // Start on whatever category Live TV was last showing.
        val wanted = Prefs.activePlaylist(ctx)?.lastCategoryId
        val valid = wanted != null && PlaylistRepository.categories(ctx, ContentType.LIVE).any { it.first == wanted }
        categoryId = if (valid) wanted!! else PlaylistRepository.CATEGORY_ALL
        if (searching) closeSearch()
        dayOffset = 0
        applyDay(scrollToNow = true)
        refreshChannels()
    }

    private fun refreshChannels() {
        if (_binding == null) return
        val ctx = requireContext()
        val trimmed = query.trim()
        val list: List<Channel> = if (trimmed.isNotEmpty()) {
            PlaylistRepository.channels
                .filter { it.contentType == ContentType.LIVE && it.name.contains(trimmed, ignoreCase = true) }
                .take(500)
        } else {
            PlaylistRepository.channelsForCategory(ctx, categoryId, ContentType.LIVE)
        }
        adapter.submit(list)
        b.categoryTitle.text = if (trimmed.isNotEmpty()) getString(R.string.nav_search) else labelFor(categoryId)
        b.categoryCount.text = getString(R.string.channels_count, list.size)
        b.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) b.emptyText.setText(R.string.label_no_results)
        b.guideRows.scrollToPosition(0)
    }

    private fun labelFor(id: String): String = when (id) {
        PlaylistRepository.CATEGORY_ALL -> getString(R.string.category_all_channels)
        PlaylistRepository.CATEGORY_FAVORITES -> getString(R.string.category_favorites)
        Channel.UNGROUPED_GROUP_KEY -> getString(R.string.category_uncategorized)
        else -> id
    }

    // ---- Day -------------------------------------------------------------------------------

    private fun dayStart(offset: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, offset)
        return cal.timeInMillis
    }

    private fun dayLabel(offset: Int): String = when (offset) {
        0 -> getString(R.string.guide_today)
        1 -> getString(R.string.guide_tomorrow)
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dayStart(offset)))
    }

    private fun applyDay(scrollToNow: Boolean) {
        state.windowStart = dayStart(dayOffset)
        b.dayButton.text = dayLabel(dayOffset)
        // Today opens around "now"; other days open at the start of the evening.
        val target = if (dayOffset == 0) System.currentTimeMillis() - 30 * 60_000L else state.windowStart + 18 * 3_600_000L
        if (scrollToNow) state.scrollTo(state.xFor(target)) else state.scrollTo(0f)
        adapter.invalidateProgrammes()
        state.notifyChanged()
    }

    private fun showDayMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            for (offset in 0..3) {
                val prefix = if (offset == dayOffset) "✓  " else "     "
                menu.add(0, offset, offset, prefix + dayLabel(offset))
            }
            setOnMenuItemClickListener { item ->
                dayOffset = item.itemId
                applyDay(scrollToNow = true)
                true
            }
            show()
        }
    }

    // ---- Actions ---------------------------------------------------------------------------

    private fun watch(channel: Channel) {
        val inCategory = !searching && query.isBlank()
        PlayerActivity.start(requireContext(), channel, if (inCategory) categoryId else PlaylistRepository.CATEGORY_ALL)
    }

    private fun showProgramme(channel: Channel, programme: EpgProgramme) {
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val end = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = "${fmt.format(Date(programme.startMillis))} – ${end.format(Date(programme.stopMillis))}"
        val body = listOfNotNull(channel.name, time, programme.description?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(programme.title)
            .setMessage(body)
            .setPositiveButton(R.string.action_watch) { _, _ -> watch(channel) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- Search ----------------------------------------------------------------------------

    private fun openSearch() {
        searching = true
        backCallback.isEnabled = true
        b.titleText.visibility = View.GONE
        b.dayButton.visibility = View.GONE
        b.appBarSpacer.visibility = View.GONE
        b.searchEdit.visibility = View.VISIBLE
        b.searchEdit.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java).showSoftInput(b.searchEdit, 0)
    }

    private fun closeSearch() {
        if (_binding == null) return
        searching = false
        backCallback.isEnabled = false
        query = ""
        b.searchEdit.setText("")
        b.searchEdit.visibility = View.GONE
        b.titleText.visibility = View.VISIBLE
        b.dayButton.visibility = View.VISIBLE
        b.appBarSpacer.visibility = View.VISIBLE
        hideKeyboard()
        refreshChannels()
    }

    private fun hideKeyboard() {
        val view = _binding?.searchEdit ?: return
        requireContext().getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
}
