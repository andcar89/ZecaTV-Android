package com.ze.iptvplayer.ui.guide

import android.content.Context
import android.view.View
import android.widget.OverScroller

/**
 * Shared horizontal timeline state for the TV Guide: which day is shown, how many pixels a minute
 * takes, and the current scroll offset. Every row, the time header and the "now" line read from
 * one instance and redraw whenever it changes, which is what keeps them all in sync.
 */
class GuideState(context: Context, val pxPerMinute: Float) {

    companion object {
        const val DAY_MINUTES = 24 * 60
    }

    var windowStart: Long = 0L
    val windowEnd: Long get() = windowStart + DAY_MINUTES * 60_000L
    var viewportWidth: Int = 0
    var scrollX: Float = 0f
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val scroller = OverScroller(context)
    private var flingRunnable: Runnable? = null

    val totalWidth: Float get() = DAY_MINUTES * pxPerMinute
    private val maxScroll: Float get() = (totalWidth - viewportWidth).coerceAtLeast(0f)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun xFor(timeMillis: Long): Float = (timeMillis - windowStart) / 60_000f * pxPerMinute

    /** Time at horizontal position [x] within the visible timeline area (0 = its left edge). */
    fun timeAt(x: Float): Long = windowStart + ((x + scrollX) / pxPerMinute * 60_000f).toLong()

    fun scrollTo(x: Float) {
        val clamped = x.coerceIn(0f, maxScroll)
        if (clamped == scrollX) return
        scrollX = clamped
        listeners.toList().forEach { it() }
    }

    fun scrollBy(dx: Float) = scrollTo(scrollX + dx)

    fun notifyChanged() = listeners.toList().forEach { it() }

    fun stopFling() {
        scroller.abortAnimation()
    }

    fun fling(view: View, velocityX: Float) {
        scroller.fling(scrollX.toInt(), 0, velocityX.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
        val step = object : Runnable {
            override fun run() {
                if (scroller.computeScrollOffset()) {
                    scrollTo(scroller.currX.toFloat())
                    view.postOnAnimation(this)
                }
            }
        }
        flingRunnable = step
        view.postOnAnimation(step)
    }
}
