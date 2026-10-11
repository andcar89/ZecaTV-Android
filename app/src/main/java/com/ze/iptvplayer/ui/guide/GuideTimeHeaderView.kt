package com.ze.iptvplayer.ui.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.ze.iptvplayer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The row of half-hour labels above the guide. */
class GuideTimeHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GuideBaseView(context, attrs) {

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zTextSecondary)
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val tickPaint = Paint().apply {
        color = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zOutline)
        strokeWidth = density
    }
    private val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val date = Date()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        state?.let {
            it.viewportWidth = w
            it.scrollTo(it.scrollX)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        s.viewportWidth = width
        val halfHour = 30 * s.pxPerMinute
        val first = (s.scrollX / halfHour).toInt()
        val last = ((s.scrollX + width) / halfHour).toInt() + 1
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        for (i in first..last) {
            val x = i * halfHour - s.scrollX
            canvas.drawLine(x, height - 8 * density, x, height.toFloat(), tickPaint)
            date.time = s.windowStart + i * 30 * 60_000L
            canvas.drawText(format.format(date), x + 6 * density, baseline, textPaint)
        }
    }
}
