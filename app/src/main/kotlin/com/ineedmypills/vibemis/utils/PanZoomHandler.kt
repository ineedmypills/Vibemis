package com.ineedmypills.vibemis.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import kotlin.math.max
import kotlin.math.min

class PanZoomHandler(
    context: Context,
    private val game: Game,
    private val streamView: View,
    private var parent: View?,
    prefConfig: PreferenceConfiguration
) {
    private val isTopMode = prefConfig.alignDisplayTopCenter
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    var scaleFactor = 1.0f
        private set
    var childX = 0f
        private set
    var childY = 0f
        private set

    private var parentWidth = 0f
    private var parentHeight = 0f
    private var childWidth = 0f
    private var childHeight = 0f

    init {
        streamView.pivotX = 0f
        streamView.pivotY = 0f
    }

    fun handleTouchEvent(motionEvent: MotionEvent) {
        scaleGestureDetector.onTouchEvent(motionEvent)
        gestureDetector.onTouchEvent(motionEvent)
    }

    private fun updateDimensions() {
        childHeight = streamView.height * scaleFactor
        childWidth = streamView.width * scaleFactor
        parentWidth = parent?.width?.toFloat() ?: 0f
        parentHeight = parent?.height?.toFloat() ?: 0f
    }

    private fun constrainToBounds() {
        updateDimensions()

        childX = if (parentWidth >= childWidth) {
            (parentWidth - childWidth) / 2
        } else {
            val boundaryX = parentWidth - childWidth
            max(boundaryX, min(childX, 0f))
        }

        childY = if (parentHeight >= childHeight) {
            if (isTopMode) 0f else (parentHeight - childHeight) / 2
        } else {
            val boundaryY = parentHeight - childHeight
            max(boundaryY, min(childY, 0f))
        }

        streamView.x = childX
        streamView.y = childY
    }

    fun handleSurfaceChange() {
        if (childWidth == 0f || parent == null) {
            parent = streamView.parent as? View
            return
        }

        val prevChildWidth = childWidth
        val prevChildHeight = childHeight
        val prevParentWidth = parentWidth
        val prevParentHeight = parentHeight

        updateDimensions()

        val viewScaleX = childWidth / prevChildWidth
        val viewScaleY = childHeight / prevChildHeight

        val dPivotX1 = childX - prevParentWidth / 2
        val dPivotY1 = childY - prevParentHeight / 2

        val dPivotX2 = dPivotX1 * viewScaleX
        val dPivotY2 = dPivotY1 * viewScaleY

        childX = dPivotX2 + parentWidth / 2
        childY = dPivotY2 + parentHeight / 2

        streamView.x = childX
        streamView.y = childY

        constrainToBounds()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var newScaleFactor = scaleFactor * detector.scaleFactor
            newScaleFactor = max(1f, min(newScaleFactor, MAX_SCALE))

            val focusX = detector.focusX
            val focusY = detector.focusY

            val dPivotX = (childX - focusX) / scaleFactor * newScaleFactor
            val dPivotY = (childY - focusY) / scaleFactor * newScaleFactor

            childX = focusX + dPivotX
            childY = focusY + dPivotY

            scaleFactor = newScaleFactor

            streamView.scaleX = scaleFactor
            streamView.scaleY = scaleFactor

            streamView.x = childX
            streamView.y = childY

            constrainToBounds()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            game.updatePipAutoEnter()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            childX = streamView.x - distanceX
            childY = streamView.y - distanceY

            streamView.x = childX
            streamView.y = childY

            constrainToBounds()
            return true
        }
    }

    fun setInitialZoomAndPan(scale: Float, offsetX: Float, offsetY: Float) {
        this.scaleFactor = scale
        streamView.scaleX = scaleFactor
        streamView.scaleY = scaleFactor
        this.childX = offsetX
        this.childY = offsetY
        streamView.x = childX
        streamView.y = childY
    }

    companion object {
        private const val MAX_SCALE = 10.0f
    }
}
