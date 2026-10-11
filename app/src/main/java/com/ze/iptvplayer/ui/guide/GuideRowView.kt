package com.ze.iptvplayer.ui.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.ze.iptvplayer.R
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * One channel's programmes drawn as blocks on the shared timeline. Only the blocks inside the
 * visible window are drawn, so a whole day of programmes costs almost nothing per frame.
 */
class GuideRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GuideBaseView(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val dividerPaint = Paint().apply {
        color = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zOutline)
        strokeWidth = density
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * scaled }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * scaled }

    private val blockColor = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zSurfaceHigh)
    private val nowStroke = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zAccent)
    private val nowColor = androidx.core.graphics.ColorUtils.blendARGB(blockColor, nowStroke, 0.35f)
    private val textPrimary = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zTextPrimary)
    private val textSecondary = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zTextSecondary)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val date = Date()
    private val rect = RectF()

    private var channel: Channel? = null
    private var programmes: List<EpgProgramme> = emptyList()
    private var onProgrammeTap: (Channel, EpgProgramme) -> Unit = { _, _ -> }

    fun bind(channel: Channel, programmes: List<EpgProgramme>, onTap: (Channel, EpgProgramme) -> Unit) {
        this.channel = channel
        this.programmes = programmes
        this.onProgrammeTap = onTap
        invalidate()
    }

    override fun onTap(x: Float, y: Float) {
        val s = state ?: return
        val c = channel ?: return
        val t = s.timeAt(x)
        programmes.firstOrNull { t >= it.startMillis && t < it.stopMillis }?.let { onProgrammeTap(c, it) }
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val now = System.currentTimeMillis()
        val pad = 10f * density
        val gap = 2f * density
        val top = 4f * density
        val bottom = height - 4f * density

        if (programmes.isEmpty()) {
            titlePaint.color = textSecondary
            val baseline = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f
            canvas.drawText(context.getString(R.string.label_guide_no_data), pad, baseline, titlePaint)
        }

        for (p in programmes) {
            val x0 = s.xFor(p.startMillis) - s.scrollX
            val x1 = s.xFor(p.stopMillis) - s.scrollX
            if (x1 < 0f || x0 > width) continue
            val isNow = now >= p.startMillis && now < p.stopMillis

            rect.set(x0 + gap, top, x1 - gap, bottom)
            fillPaint.color = if (isNow) nowColor else blockColor
            canvas.drawRoundRect(rect, 8f * density, 8f * density, fillPaint)
            if (isNow) {
                strokePaint.color = nowStroke
                canvas.drawRoundRect(rect, 8f * density, 8f * density, strokePaint)
            }

            if (rect.width() < 24f * density) continue
            canvas.save()
            canvas.clipRect(rect)
            // Keep the title visible while a long block is partly scrolled off the left edge.
            val textX = max(rect.left, 0f) + pad
            titlePaint.color = if (p.stopMillis <= now) textSecondary else textPrimary
            canvas.drawText(p.title, textX, top + 20f * density, titlePaint)
            timePaint.color = textSecondary
            date.time = p.startMillis
            val start = timeFormat.format(date)
            date.time = p.stopMillis
            canvas.drawText("$start – ${timeFormat.format(date)}", textX, top + 36f * density, timePaint)
            canvas.restore()
        }
        canvas.drawLine(0f, height - 0.5f * density, width.toFloat(), height - 0.5f * density, dividerPaint)
    }
}
