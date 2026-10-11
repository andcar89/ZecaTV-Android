package com.ze.iptvplayer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ze.iptvplayer.data.VodItem
import com.ze.iptvplayer.databinding.ItemPosterBinding

/**
 * Poster cards for the Movies / TV Shows grids and the "More like this" row. Entries without a
 * poster get a gradient (picked from the title) with the title written on it.
 * [fixedWidthDp] > 0 gives every card that width (horizontal rows); 0 lets the grid size them.
 */
class PosterAdapter(
    private val fixedWidthDp: Int = 0,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private companion object {
        const val POSTER_W_PX = 300
        const val POSTER_H_PX = 450
    }

    class VH(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<VodItem> = emptyList()

    fun submit(list: List<VodItem>) {
        if (list == items) return // nothing changed (e.g. returning to the tab) — keep scroll and avoid a full rebind
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (fixedWidthDp > 0) {
            val width = (fixedWidthDp * parent.resources.displayMetrics.density).toInt()
            binding.root.layoutParams = RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val item = items[position]

        b.posterTitle.text = item.title
        b.posterYear.text = item.year.orEmpty()
        b.posterYear.visibility = if (item.year.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.posterFallbackTitle.text = item.title
        b.posterFallbackBackground.background = gradientFor(item.title)

        b.posterProgress.visibility = if (item.progress > 0f) View.VISIBLE else View.GONE
        b.posterProgress.progress = (item.progress * 100).toInt()

        if (item.posterUrl.isNullOrBlank()) {
            Glide.with(b.posterImage).clear(b.posterImage)
            b.posterImage.setImageDrawable(null)
            b.posterFallbackGroup.visibility = View.VISIBLE
        } else {
            b.posterFallbackGroup.visibility = View.VISIBLE
            Glide.with(b.posterImage)
                .load(item.posterUrl)
                .override(POSTER_W_PX, POSTER_H_PX) // decode at card size, not at the provider's full-size image
                .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                        b.posterFallbackGroup.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: Target<android.graphics.drawable.Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        b.posterFallbackGroup.visibility = View.GONE
                        return false
                    }
                })
                .into(b.posterImage)
        }

        b.root.setOnClickListener { onClick(item) }
    }

    private fun gradientFor(title: String): GradientDrawable {
        val hue = ((title.hashCode() % 360) + 360) % 360
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f))
        val bottom = Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.45f, 0.75f))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
    }
}
