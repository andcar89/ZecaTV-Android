package com.ze.iptvplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.ze.iptvplayer.DetailActivity
import com.ze.iptvplayer.LoadState
import com.ze.iptvplayer.MainViewModel
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.data.VodRepository
import com.ze.iptvplayer.databinding.FragmentVodBinding
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.data.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Movies and TV Shows tabs: a poster grid with the same category sheet and search as Live TV.
 * One class for both — [ContentType.MOVIE] or [ContentType.SERIES] decides what it lists.
 */
class VodFragment : Fragment() {

    companion object {
        private const val ARG_TYPE = "type"
        fun newInstance(type: ContentType) = VodFragment().apply {
            arguments = bundleOf(ARG_TYPE to type.name)
        }
    }

    private val vm: MainViewModel by activityViewModels()
    private var _binding: FragmentVodBinding? = null
    private val b get() = _binding!!

    private lateinit var type: ContentType
    private lateinit var adapter: PosterAdapter
    private lateinit var bannerAdapter: BannerAdapter

    private var categoryId = PlaylistRepository.CATEGORY_ALL
    private var query = ""
    private var searching = false
    private var shownGeneration = -1

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVodBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        type = ContentType.valueOf(requireArguments().getString(ARG_TYPE) ?: ContentType.MOVIE.name)
        b.titleText.setText(if (type == ContentType.MOVIE) R.string.nav_movies else R.string.nav_series)

        adapter = PosterAdapter { item -> DetailActivity.start(requireContext(), item.isShow, item.key) }
        val dm = resources.displayMetrics
        val spans = ((dm.widthPixels / dm.density) / 118f).toInt().coerceIn(3, 8)
        bannerAdapter = BannerAdapter { item -> DetailActivity.start(requireContext(), item.isShow, item.key) }
        val grid = GridLayoutManager(requireContext(), spans)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            // The banner (position 0, only when it exists) spans the whole row.
            override fun getSpanSize(position: Int): Int = if (position == 0 && bannerAdapter.hasBanner) spans else 1
        }
        b.vodGrid.layoutManager = grid
        b.vodGrid.adapter = ConcatAdapter(bannerAdapter, adapter)
        b.vodGrid.itemAnimator = null
        b.vodGrid.setHasFixedSize(true)

        b.categoryBar.setOnClickListener {
            CategorySheet.newInstance(type, categoryId).show(childFragmentManager, "categories")
        }
        b.searchButton.setOnClickListener { if (searching) closeSearch() else openSearch() }
        b.searchEdit.addTextChangedListener {
            query = it?.toString().orEmpty()
            searchDebounce?.cancel()
            searchDebounce = viewLifecycleOwner.lifecycleScope.launch {
                delay(180)
                refreshList(resetScroll = true)
            }
        }
        b.searchEdit.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }

        childFragmentManager.setFragmentResultListener(CategorySheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(CategorySheet.RESULT_ID)?.let { onCategoryChosen(it) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    if (state is LoadState.Ready && state.generation != shownGeneration) {
                        shownGeneration = state.generation
                        rebuild()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Progress bars / "Continue watching" may have changed while a detail screen or the player was open.
        if (!isHidden && shownGeneration >= 0) refreshList(resetScroll = false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (hidden) {
            if (searching) closeSearch() else hideKeyboard()
        } else if (shownGeneration >= 0) {
            refreshList(resetScroll = false)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun rebuild() {
        categoryId = PlaylistRepository.CATEGORY_ALL
        if (searching) closeSearch()
        refreshList(resetScroll = true)
    }

    private var refreshJob: Job? = null
    private var searchDebounce: Job? = null

    private class Computed(val categoryId: String, val items: List<VodItem>, val banner: VodItem?, val bannerIsContinue: Boolean)

    /** Builds the list on a background thread (tens of thousands of entries) and applies it on the
     *  main thread; a newer request cancels an older one still in flight (fast typing in search). */
    private fun refreshList(resetScroll: Boolean) {
        if (_binding == null) return
        val ctx = requireContext().applicationContext
        val trimmed = query.trim()
        val wantedCategory = categoryId
        val contentType = type
        val wantBanner = UiPrefs.getBanner(ctx)
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                var cat = wantedCategory
                // A chosen category can vanish (e.g. "Continue watching" once everything is finished).
                if (trimmed.isEmpty() && cat != PlaylistRepository.CATEGORY_ALL &&
                    VodRepository.categories(ctx, contentType).none { it.id == cat }
                ) {
                    cat = PlaylistRepository.CATEGORY_ALL
                }
                val items = VodRepository.items(ctx, contentType, cat, trimmed)
                var banner: VodItem? = null
                var bannerIsContinue = false
                if (wantBanner && trimmed.isEmpty()) {
                    banner = VodRepository.items(ctx, contentType, VodRepository.CATEGORY_CONTINUE, "").firstOrNull()
                    bannerIsContinue = banner != null
                    if (banner == null) {
                        // Featured: one title per day (stable while you browse), preferring ones with artwork.
                        val withArt = items.filter { !it.posterUrl.isNullOrBlank() }.ifEmpty { items }
                        if (withArt.isNotEmpty()) {
                            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                            banner = withArt[day % withArt.size]
                        }
                    }
                }
                Computed(cat, items, banner, bannerIsContinue)
            }
            if (_binding == null) return@launch
            categoryId = computed.categoryId
            bannerAdapter.set(computed.banner, computed.bannerIsContinue)
            applyItems(computed.items, trimmed, resetScroll)
        }
    }

    private fun applyItems(items: List<VodItem>, trimmed: String, resetScroll: Boolean) {
        val ctx = requireContext()
        adapter.submit(items)
        b.categoryTitle.text =
            if (trimmed.isNotEmpty()) getString(R.string.nav_search) else VodRepository.categoryLabel(ctx, type, categoryId)
        b.categoryCount.text = if (type == ContentType.MOVIE) {
            getString(R.string.movies_count, items.size)
        } else {
            getString(R.string.shows_count, items.size)
        }

        if (items.isEmpty()) {
            b.emptyText.text = when {
                trimmed.isNotEmpty() -> getString(R.string.label_no_titles)
                categoryId == PlaylistRepository.CATEGORY_ALL ->
                    getString(if (type == ContentType.MOVIE) R.string.label_no_movies else R.string.label_no_shows)
                else -> getString(R.string.label_no_titles)
            }
            b.emptyText.visibility = View.VISIBLE
        } else {
            b.emptyText.visibility = View.GONE
        }
        if (resetScroll) b.vodGrid.scrollToPosition(0)
    }

    private fun onCategoryChosen(id: String) {
        categoryId = id
        if (searching) closeSearch() else refreshList(resetScroll = true)
    }

    private fun openSearch() {
        searching = true
        backCallback.isEnabled = true
        b.titleText.visibility = View.GONE
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
        b.appBarSpacer.visibility = View.VISIBLE
        hideKeyboard()
        refreshList(resetScroll = true)
    }

    private fun hideKeyboard() {
        val view = _binding?.searchEdit ?: return
        requireContext().getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
}
