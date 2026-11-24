package com.ineedmypills.vibemis.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View
import com.ineedmypills.vibemis.nvstream.NvConnection
import com.ineedmypills.vibemis.nvstream.input.MouseButtonPacket
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class AbsoluteTouchContext(
    private val conn: NvConnection,
    private val actionIndex: Int,
    private val targetView: View,
    swapped: Boolean
) : TouchContext {
    private var lastTouchDownX = 0
    private var lastTouchDownY = 0
    private var lastTouchDownTime = 0L
    private var lastTouchUpX = 0
    private var lastTouchUpY = 0
    private var lastTouchUpTime = 0L
    private var lastTouchLocationX = 0
    private var lastTouchLocationY = 0
    private var cancelled = false
    private var confirmedLongPress = false
    private var confirmedTap = false

    private val buttonPrimary: Byte = if (swapped) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT
    private val buttonSecondary: Byte = if (swapped) MouseButtonPacket.BUTTON_LEFT else MouseButtonPacket.BUTTON_RIGHT

    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        // This timer should have already expired, but cancel it just in case
        cancelTapDownTimer()

        // Switch from a left click to a right click after a long press
        confirmedLongPress = true
        if (confirmedTap) {
            conn.sendMouseButtonUp(buttonPrimary)
        }
        conn.sendMouseButtonDown(buttonSecondary)
    }

    private val tapDownRunnable = Runnable {
        // Start our tap
        tapConfirmed()
    }

    private val leftButtonUpRunnable = Runnable {
        conn.sendMouseButtonUp(buttonPrimary)
    }

    override fun getActionIndex(): Int = actionIndex

    override fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true
        }

        lastTouchDownX = eventX
        lastTouchLocationX = eventX
        lastTouchDownY = eventY
        lastTouchLocationY = eventY
        lastTouchDownTime = eventTime
        confirmedLongPress = false
        confirmedTap = false
        cancelled = false

        if (actionIndex == 0) {
            // Start the timers
            startTapDownTimer()
            startLongPressTimer()
        }

        return true
    }

    private fun distanceExceeds(deltaX: Int, deltaY: Int, limit: Double): Boolean {
        return hypot(deltaX.toDouble(), deltaY.toDouble()) > limit
    }

    private fun updatePosition(eventX: Int, eventY: Int) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size.
        val clampedX = min(max(eventX, 0), targetView.width)
        val clampedY = min(max(eventY, 0), targetView.height)

        conn.sendMousePosition(
            clampedX.toShort(),
            clampedY.toShort(),
            targetView.width.toShort(),
            targetView.height.toShort()
        )
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        if (actionIndex == 0) {
            // Cancel the timers
            cancelLongPressTimer()
            cancelTapDownTimer()

            // Raise the mouse buttons that we currently have down
            if (confirmedLongPress) {
                conn.sendMouseButtonUp(buttonSecondary)
            } else if (confirmedTap) {
                conn.sendMouseButtonUp(buttonPrimary)
            } else {
                // If we get here, this means that the tap completed within the touch down
                // deadzone time. We'll need to send the touch down and up events now at the
                // original touch down position.
                tapConfirmed()

                // Release the left mouse button in 100ms to allow for apps that use polling
                // to detect mouse button presses.
                handler.removeCallbacks(leftButtonUpRunnable)
                handler.postDelayed(leftButtonUpRunnable, 100)
            }
        }

        lastTouchUpX = eventX
        lastTouchLocationX = eventX
        lastTouchUpY = eventY
        lastTouchLocationY = eventY
        lastTouchUpTime = eventTime
    }

    private fun startLongPressTimer() {
        cancelLongPressTimer()
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD.toLong())
    }

    private fun cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun startTapDownTimer() {
        cancelTapDownTimer()
        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD.toLong())
    }

    private fun cancelTapDownTimer() {
        handler.removeCallbacks(tapDownRunnable)
    }

    private fun tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return
        }

        confirmedTap = true
        cancelTapDownTimer()

        // Left button down at original position
        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
            distanceExceeds(
                lastTouchDownX - lastTouchUpX,
                lastTouchDownY - lastTouchUpY,
                DOUBLE_TAP_DISTANCE_THRESHOLD.toDouble()
            )
        ) {
            // Don't reposition for finger down events within the deadzone. This makes double-clicking easier.
            updatePosition(lastTouchDownX, lastTouchDownY)
        }
        conn.sendMouseButtonDown(buttonPrimary)
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        if (actionIndex == 0) {
            if (distanceExceeds(
                    eventX - lastTouchDownX,
                    eventY - lastTouchDownY,
                    LONG_PRESS_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                // Moved too far since touch down. Cancel the long press timer.
                cancelLongPressTimer()
            }

            // Ignore motion within the deadzone period after touch down
            if (confirmedTap || distanceExceeds(
                    eventX - lastTouchDownX,
                    eventY - lastTouchDownY,
                    TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                tapConfirmed()
                updatePosition(eventX, eventY)
            }
        } else if (actionIndex == 1) {
            conn.sendMouseHighResScroll(((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR).toShort())
        }

        lastTouchLocationX = eventX
        lastTouchLocationY = eventY

        return true
    }

    override fun cancelTouch() {
        cancelled = true

        // Cancel the timers
        cancelLongPressTimer()
        cancelTapDownTimer()

        // Raise the mouse buttons
        if (confirmedLongPress) {
            conn.sendMouseButtonUp(buttonSecondary)
        } else if (confirmedTap) {
            conn.sendMouseButtonUp(buttonPrimary)
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun setPointerCount(pointerCount: Int) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch()
        }
    }

    companion object {
        private const val SCROLL_SPEED_FACTOR = 3

        private const val LONG_PRESS_TIME_THRESHOLD = 650
        private const val LONG_PRESS_DISTANCE_THRESHOLD = 30

        private const val DOUBLE_TAP_TIME_THRESHOLD = 250
        private const val DOUBLE_TAP_DISTANCE_THRESHOLD = 60

        private const val TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100
        private const val TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20
    }
}
