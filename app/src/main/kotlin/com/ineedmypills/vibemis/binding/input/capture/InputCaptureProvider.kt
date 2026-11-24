package com.ineedmypills.vibemis.binding.input.capture

import android.view.MotionEvent

abstract class InputCaptureProvider {
    @JvmField
    protected var isCapturing = false
    @JvmField
    protected var isCursorVisible = false

    open fun enableCapture() {
        isCapturing = true
        hideCursor()
    }

    open fun disableCapture() {
        isCapturing = false
        showCursor()
    }

    open fun destroy() {}

    open fun isCapturingEnabled(): Boolean {
        return isCapturing
    }

    open fun isCapturingActive(): Boolean {
        return isCapturing
    }

    open fun showCursor() {
        isCursorVisible = true
    }

    open fun hideCursor() {
        isCursorVisible = false
    }

    open fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        return false
    }

    open fun getRelativeAxisX(event: MotionEvent, pointerIndex: Int): Float {
        return 0f
    }

    open fun getRelativeAxisX(event: MotionEvent): Float {
        return getRelativeAxisX(event, 0)
    }

    open fun getRelativeAxisY(event: MotionEvent, pointerIndex: Int): Float {
        return 0f
    }

    open fun getRelativeAxisY(event: MotionEvent): Float {
        return getRelativeAxisY(event, 0)
    }

    open fun onWindowFocusChanged(focusActive: Boolean) {}
}
