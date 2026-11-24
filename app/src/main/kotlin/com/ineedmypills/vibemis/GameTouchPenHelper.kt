package com.ineedmypills.vibemis

import android.content.res.Resources
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.ineedmypills.vibemis.nvstream.NvConnection
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.StreamContainer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class GameTouchPenHelper(
    private val resources: Resources,
    private val streamContainer: StreamContainer,
    private val prefConfig: PreferenceConfiguration
) {
    private val sensitivityMap = HashMap<String, SensitivityBean>()

    fun getLiTouchTypeFromEvent(event: MotionEvent): Byte {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> VibemisBridge.VB_TOUCH_EVENT_DOWN

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    VibemisBridge.VB_TOUCH_EVENT_CANCEL
                } else {
                    VibemisBridge.VB_TOUCH_EVENT_UP
                }
            }

            MotionEvent.ACTION_MOVE -> VibemisBridge.VB_TOUCH_EVENT_MOVE
            MotionEvent.ACTION_CANCEL -> VibemisBridge.VB_TOUCH_EVENT_CANCEL_ALL

            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> VibemisBridge.VB_TOUCH_EVENT_HOVER

            MotionEvent.ACTION_HOVER_EXIT -> VibemisBridge.VB_TOUCH_EVENT_HOVER_LEAVE

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> VibemisBridge.VB_TOUCH_EVENT_BUTTON_ONLY

            else -> (-1).toByte()
        }
    }

    private fun getStreamViewRelativeSensitivityXY(
        event: MotionEvent,
        initialX: Float,
        initialY: Float,
        pointerIndex: Int
    ): FloatArray {
        var normalizedX = initialX
        var normalizedY = initialY
        val normalized = floatArrayOf(normalizedX, normalizedY)

        if (!prefConfig.touchSensitivityGlobal && normalizedX < resources.displayMetrics.widthPixels / 2f) {
            return normalized
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val key = event.getPointerId(pointerIndex).toString()
            val bean = sensitivityMap[key] ?: SensitivityBean()
            if (bean.lastAbsoluteX != -1f) {
                var dx = normalizedX - bean.lastAbsoluteX
                var dy = normalizedY - bean.lastAbsoluteY
                dx *= 0.01f * prefConfig.touchSensitivityX
                dy *= 0.01f * prefConfig.touchSensitivityY
                normalizedX = bean.lastRelativelyX + dx
                normalizedY = bean.lastRelativelyY + dy
            }
            if (prefConfig.touchSensitivityRotationAuto) {
                if (normalizedX >= streamContainer.width) {
                    normalizedX = streamContainer.width / 2.0f
                }
                if (normalizedY >= streamContainer.height) {
                    normalizedY = streamContainer.height / 2.0f
                }
            }
            bean.lastAbsoluteX = event.getX(pointerIndex)
            bean.lastAbsoluteY = event.getY(pointerIndex)
            bean.lastRelativelyX = normalizedX
            bean.lastRelativelyY = normalizedY
            sensitivityMap[key] = bean
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            sensitivityMap.remove(event.getPointerId(pointerIndex).toString())
        }
        normalized[0] = normalizedX
        normalized[1] = normalizedY
        return normalized
    }

    fun getNormalizedCoordinates(streamView: View, rawX: Float, rawY: Float): FloatArray {
        val scaleX = streamView.scaleX
        val scaleY = streamView.scaleY
        val normalizedX = (rawX - streamView.x) / scaleX
        val normalizedY = (rawY - streamView.y) / scaleY
        return floatArrayOf(normalizedX, normalizedY)
    }

    fun getStreamViewRelativeNormalizedXY(view: View, event: MotionEvent, pointerIndex: Int): FloatArray {
        var normalizedX = event.getX(pointerIndex)
        var normalizedY = event.getY(pointerIndex)
        if (prefConfig.enableTouchSensitivity && (prefConfig.touchSensitivityX != 100 || prefConfig.touchSensitivityY != 100)) {
            val normalized = getStreamViewRelativeSensitivityXY(event, normalizedX, normalizedY, pointerIndex)
            normalizedX = normalized[0]
            normalizedY = normalized[1]
        }
        if (view !== streamContainer) {
            val normalized = getNormalizedCoordinates(streamContainer, normalizedX, normalizedY)
            normalizedX = normalized[0]
            normalizedY = normalized[1]
        }

        val w = streamContainer.width.toFloat().coerceAtLeast(1f)
        val h = streamContainer.height.toFloat().coerceAtLeast(1f)
        normalizedX = min(max(normalizedX, 0.0f), w) / w
        normalizedY = min(max(normalizedY, 0.0f), h) / h

        return floatArrayOf(normalizedX, normalizedY)
    }

    private fun normalizeValueInRange(value: Float, range: InputDevice.MotionRange): Float {
        return (value - range.min) / range.range
    }

    private fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
        val dev = event.device
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (dev != null) {
                    val distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange)
                    }
                }
                0.0f
            }
            else -> event.getPressure(pointerIndex)
        }
    }

    private fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
        val dev = event.device
        if (dev != null && dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) != null) {
            var rotationDegrees = Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt().toShort()
            if (rotationDegrees < 0) {
                rotationDegrees = (rotationDegrees + 360).toShort()
            }
            return rotationDegrees
        }
        return VibemisBridge.VB_ROT_UNKNOWN
    }

    private fun polarToCartesian(r: Float, theta: Float): FloatArray {
        return floatArrayOf(r * cos(theta.toDouble()).toFloat(), r * sin(theta.toDouble()).toFloat())
    }

    private fun cartesianToR(point: FloatArray): Float {
        return sqrt((point[0] * point[0] + point[1] * point[1]).toDouble()).toFloat()
    }

    private fun getStreamViewNormalizedContactArea(event: MotionEvent, pointerIndex: Int): FloatArray {
        val orientation: Float = if (event.device == null ||
            event.device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) == null
        ) {
            (Math.PI / 4).toFloat()
        } else {
            event.getOrientation(pointerIndex)
        }

        val contactAreaMajor: Float
        val contactAreaMinor: Float
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> {
                contactAreaMajor = event.getToolMajor(pointerIndex)
                contactAreaMinor = event.getToolMinor(pointerIndex)
            }
            else -> {
                contactAreaMajor = event.getTouchMajor(pointerIndex)
                contactAreaMinor = event.getTouchMinor(pointerIndex)
            }
        }

        val contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation)
        val contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (orientation + (Math.PI / 2)).toFloat())

        val w = streamContainer.width.toFloat().coerceAtLeast(1f)
        val h = streamContainer.height.toFloat().coerceAtLeast(1f)
        contactAreaMajorCartesian[0] = min(abs(contactAreaMajorCartesian[0]), w) / w
        contactAreaMinorCartesian[0] = min(abs(contactAreaMinorCartesian[0]), w) / w
        contactAreaMajorCartesian[1] = min(abs(contactAreaMajorCartesian[1]), h) / h
        contactAreaMinorCartesian[1] = min(abs(contactAreaMinorCartesian[1]), h) / h

        return floatArrayOf(cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian))
    }

    private fun sendPenEventForPointer(
        conn: NvConnection,
        view: View,
        event: MotionEvent,
        eventType: Byte,
        toolType: Byte,
        pointerIndex: Int
    ): Boolean {
        var penButtons: Byte = 0
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons = (penButtons.toInt() or VibemisBridge.VB_PEN_BUTTON_PRIMARY.toInt()).toByte()
        }
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons = (penButtons.toInt() or VibemisBridge.VB_PEN_BUTTON_SECONDARY.toInt()).toByte()
        }

        var tiltDegrees: Byte = VibemisBridge.VB_TILT_UNKNOWN
        val dev = event.device
        if (dev != null && dev.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null) {
            tiltDegrees = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()).toInt().toByte()
        }

        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex)
        return conn.sendPenEvent(
            eventType,
            toolType,
            penButtons,
            normalizedCoords[0],
            normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0],
            normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex),
            tiltDegrees
        ) != VibemisBridge.VB_ERR_UNSUPPORTED
    }

    private fun convertToolTypeToStylusToolType(event: MotionEvent, pointerIndex: Int): Byte {
        return when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_ERASER -> VibemisBridge.VB_TOOL_TYPE_ERASER
            MotionEvent.TOOL_TYPE_STYLUS -> VibemisBridge.VB_TOOL_TYPE_PEN
            else -> VibemisBridge.VB_TOOL_TYPE_UNKNOWN
        }
    }

    fun trySendPenEvent(conn: NvConnection, view: View, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var handledStylusEvent = false
                for (i in 0 until event.pointerCount) {
                    val toolType = convertToolTypeToStylusToolType(event, i)
                    if (toolType == VibemisBridge.VB_TOOL_TYPE_UNKNOWN) {
                        continue
                    } else {
                        handledStylusEvent = true
                    }
                    if (!sendPenEventForPointer(conn, view, event, eventType, toolType, i)) {
                        return false
                    }
                }
                handledStylusEvent
            }
            MotionEvent.ACTION_CANCEL -> {
                conn.sendPenEvent(
                    VibemisBridge.VB_TOUCH_EVENT_CANCEL_ALL,
                    VibemisBridge.VB_TOOL_TYPE_UNKNOWN,
                    0,
                    0f, 0f, 0f, 0f, 0f,
                    VibemisBridge.VB_ROT_UNKNOWN,
                    VibemisBridge.VB_TILT_UNKNOWN
                ) != VibemisBridge.VB_ERR_UNSUPPORTED
            }
            else -> {
                val toolType = convertToolTypeToStylusToolType(event, event.actionIndex)
                if (toolType == VibemisBridge.VB_TOOL_TYPE_UNKNOWN) {
                    false
                } else {
                    sendPenEventForPointer(conn, view, event, eventType, toolType, event.actionIndex)
                }
            }
        }
    }

    private fun sendTouchEventForPointer(
        conn: NvConnection,
        view: View,
        event: MotionEvent,
        eventType: Byte,
        pointerIndex: Int
    ): Boolean {
        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex)
        return conn.sendTouchEvent(
            eventType,
            event.getPointerId(pointerIndex),
            normalizedCoords[0],
            normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0],
            normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex)
        ) != VibemisBridge.VB_ERR_UNSUPPORTED
    }

    fun trySendTouchEvent(conn: NvConnection, view: View, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (!sendTouchEventForPointer(conn, view, event, eventType, i)) {
                        return false
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                conn.sendTouchEvent(
                    VibemisBridge.VB_TOUCH_EVENT_CANCEL_ALL,
                    0,
                    0f, 0f, 0f, 0f, 0f,
                    VibemisBridge.VB_ROT_UNKNOWN
                ) != VibemisBridge.VB_ERR_UNSUPPORTED
            }
            else -> sendTouchEventForPointer(conn, view, event, eventType, event.actionIndex)
        }
    }
}
