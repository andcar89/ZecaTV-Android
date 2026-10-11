package com.ze.iptvplayer.ui.guide

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Base for the guide's custom-drawn views: owns the [GuideState] hookup (redraw on change) and the
 * horizontal pan / fling gesture, so dragging sideways on ANY row or the time header scrolls the
 * whole guide, while vertical drags still reach the RecyclerView.
 */
abstract class GuideBaseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: GuideState? = null
        set(value) {
            if (isAttachedToWindow) field?.removeListener(redraw)
            field = value
            if (isAttachedToWindow) value?.addListener(redraw)
            invalidate()
        }

    private val redraw: () -> Unit = { invalidate() }
    private var panning = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            state?.stopFling()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!panning && abs(distanceX) > abs(distanceY)) {
                panning = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (panning) state?.scrollBy(distanceX)
            return panning
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (panning && abs(velocityX) > abs(velocityY)) {
                state?.fling(this@GuideBaseView, -velocityX)
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }
    })

    open fun onTap(x: Float, y: Float) {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            panning = false
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        state?.addListener(redraw)
    }

    override fun onDetachedFromWindow() {
        state?.removeListener(redraw)
        super.onDetachedFromWindow()
    }
}
