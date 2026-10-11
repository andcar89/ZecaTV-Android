package com.ze.iptvplayer.ui.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.ze.iptvplayer.R

/** The red "now" line drawn over the whole guide. Not interactive — touches pass through to the rows. */
class GuideNowLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GuideBaseView(context, attrs) {

    private val paint = Paint().apply {
        color = com.ze.iptvplayer.util.ThemeManager.color(context, R.attr.zLive)
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val x = s.xFor(System.currentTimeMillis()) - s.scrollX
        if (x < 0f || x > width) return
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }
}
