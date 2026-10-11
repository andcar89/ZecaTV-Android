package com.ze.iptvplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.VodRepository
import com.ze.iptvplayer.databinding.ItemCategoryBinding
import com.ze.iptvplayer.databinding.SheetCategoriesBinding
import com.ze.iptvplayer.model.ContentType

/**
 * Bottom sheet listing a section's categories (Favorites / All / each group, in the playlist's
 * own order) with channel counts. Reports the choice through a fragment result keyed [RESULT_KEY],
 * delivered to the parent fragment's child fragment manager.
 */
class CategorySheet : BottomSheetDialogFragment() {

    companion object {
        const val RESULT_KEY = "category_sheet_result"
        const val RESULT_ID = "category_id"
        private const val ARG_TYPE = "type"
        private const val ARG_SELECTED = "selected"

        fun newInstance(type: ContentType, selectedId: String) = CategorySheet().apply {
            arguments = bundleOf(ARG_TYPE to type.name, ARG_SELECTED to selectedId)
        }
    }

    private data class Row(val id: String, val label: String, val count: Int)

    private var _binding: SheetCategoriesBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetCategoriesBinding.inflate(inflater, container, false)
        _binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val type = ContentType.valueOf(requireArguments().getString(ARG_TYPE) ?: ContentType.LIVE.name)
        val selectedId = requireArguments().getString(ARG_SELECTED)

        val rows: List<Row> = if (type == ContentType.LIVE) {
            val ofType = PlaylistRepository.channels.filter { it.contentType == type }
            val groups = PlaylistRepository.channelsByGroup(type)
            val favorites = FavoritesManager.getAll(ctx)
            PlaylistRepository.categories(ctx, type).map { (id, label) ->
                val count = when (id) {
                    PlaylistRepository.CATEGORY_ALL -> ofType.size
                    PlaylistRepository.CATEGORY_FAVORITES -> ofType.count { it.key in favorites }
                    else -> groups[id]?.size ?: 0
                }
                Row(id, label, count)
            }
        } else {
            // Movies count titles; TV Shows count shows (not episodes).
            VodRepository.categories(ctx, type).map { Row(it.id, it.label, it.count) }
        }

        val recycler = _binding!!.categoryList
        recycler.layoutManager = LinearLayoutManager(ctx)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(binding.root) {}
            }

            override fun getItemCount(): Int = rows.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                val binding = ItemCategoryBinding.bind(holder.itemView)
                binding.categoryLabel.text = row.label
                binding.categoryItemCount.text = row.count.toString()
                binding.categoryCheck.visibility = if (row.id == selectedId) View.VISIBLE else View.INVISIBLE
                binding.categoryRoot.setOnClickListener {
                    parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_ID to row.id))
                    dismiss()
                }
            }
        }
        val selectedIndex = rows.indexOfFirst { it.id == selectedId }
        if (selectedIndex > 0) recycler.scrollToPosition(selectedIndex)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
