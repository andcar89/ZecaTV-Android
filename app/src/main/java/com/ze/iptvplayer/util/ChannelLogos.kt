package com.ze.iptvplayer.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.R

/** Loads a channel logo into [view], falling back to the default placeholder. */
object ChannelLogos {
    fun load(view: ImageView, url: String?) {
        if (!url.isNullOrBlank()) {
            Glide.with(view.context)
                .load(url)
                .override(192, 192) // logos are small; avoid decoding oversized originals
                .placeholder(R.drawable.default_channel_logo)
                .error(R.drawable.default_channel_logo)
                .into(view)
        } else {
            Glide.with(view.context).clear(view)
            view.setImageResource(R.drawable.default_channel_logo)
        }
    }
}
