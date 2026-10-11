package com.ze.iptvplayer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.VodItem
import com.ze.iptvplayer.databinding.ItemVodBannerBinding

/**
 * One full-width banner row shown above the Movies / Shows grid: the title you were last watching
 * ("Continue watching", with a progress bar), or else a featured title. Zero rows when [item] is null.
 */
class BannerAdapter(private val onClick: (VodItem) -> Unit) : RecyclerView.Adapter<BannerAdapter.VH>() {

    class VH(val binding: ItemVodBannerBinding) : RecyclerView.ViewHolder(binding.root)

    private var item: VodItem? = null
    private var isContinue = false

    fun set(newItem: VodItem?, continueWatching: Boolean) {
        if (newItem == item && continueWatching == isContinue) return
        val had = item != null
        item = newItem
        isContinue = continueWatching
        when {
            had && newItem != null -> notifyItemChanged(0)
            had -> notifyItemRemoved(0)
            newItem != null -> notifyItemInserted(0)
        }
    }

    val hasBanner: Boolean get() = item != null

    override fun getItemCount(): Int = if (item != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVodBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val it = item ?: return
        val ctx = b.root.context

        b.bannerTitle.text = it.title
        b.bannerMeta.text = it.year.orEmpty()
        b.bannerMeta.visibility = if (it.year.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.bannerLabel.text = ctx.getString(if (isContinue) R.string.label_continue_watching else R.string.banner_featured)
        b.bannerProgress.visibility = if (isContinue && it.progress > 0f) View.VISIBLE else View.GONE
        b.bannerProgress.progress = (it.progress * 100).toInt()
        b.bannerButton.setText(if (isContinue) R.string.banner_resume else R.string.action_play)

        val hue = ((it.title.hashCode() % 360) + 360) % 360
        b.bannerFallback.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f)),
                Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.5f, 0.2f))
            )
        )
        if (it.posterUrl.isNullOrBlank()) {
            Glide.with(b.bannerImage).clear(b.bannerImage)
            b.bannerImage.setImageDrawable(null)
        } else {
            Glide.with(b.bannerImage).load(it.posterUrl).override(900, 600).centerCrop().into(b.bannerImage)
        }
        b.bannerCard.setOnClickListener { _ -> onClick(it) }
        b.bannerButton.setOnClickListener { _ -> onClick(it) }
    }
}
