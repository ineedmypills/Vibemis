package com.ineedmypills.vibemis.binding.input.virtual_controller.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent

class KeyboardDigitalPadButton(
    controller: KeyBoardController,
    context: Context,
    elementId: String
) : keyBoardVirtualControllerElement(controller, context, elementId) {

    @JvmField var direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
    @JvmField var listeners: MutableList<DigitalPadListener> = ArrayList()
    private val paint = Paint()

    fun addDigitalPadListener(listener: DigitalPadListener) {
        listeners.add(listener)
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(getCorrectWidth().toFloat(), 20f)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            paint.style = Paint.Style.STROKE
            paint.color = getDefaultColor()
            canvas.drawRect(
                getPercent(width.toFloat(), 36f), getPercent(height.toFloat(), 36f),
                getPercent(width.toFloat(), 63f), getPercent(height.toFloat(), 63f),
                paint
            )
        }

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_LEFT > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            paint.strokeWidth + DPAD_MARGIN, getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f), getPercent(height.toFloat(), 66f),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_UP > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f), paint.strokeWidth + DPAD_MARGIN,
            getPercent(width.toFloat(), 66f), getPercent(height.toFloat(), 33f),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_RIGHT > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 66f), getPercent(height.toFloat(), 33f),
            width - (paint.strokeWidth + DPAD_MARGIN), getPercent(height.toFloat(), 66f),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_DOWN > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f), getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f), height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_LEFT > 0 && direction and DIGITAL_PAD_DIRECTION_UP > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            paint.strokeWidth + DPAD_MARGIN, getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f), paint.strokeWidth + DPAD_MARGIN,
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_UP > 0 && direction and DIGITAL_PAD_DIRECTION_RIGHT > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 66f), paint.strokeWidth + DPAD_MARGIN,
            width - (paint.strokeWidth + DPAD_MARGIN), getPercent(height.toFloat(), 33f),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_RIGHT > 0 && direction and DIGITAL_PAD_DIRECTION_DOWN > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            width - paint.strokeWidth, getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f), height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        paint.color = if (direction and DIGITAL_PAD_DIRECTION_DOWN > 0 && direction and DIGITAL_PAD_DIRECTION_LEFT > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 33f), height - (paint.strokeWidth + DPAD_MARGIN),
            paint.strokeWidth + DPAD_MARGIN, getPercent(height.toFloat(), 66f),
            paint
        )
    }

    private fun newDirectionCallback(direction: Int) {
        _DBG("direction: $direction")
        for (listener in listeners) {
            listener.onDirectionChange(direction)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                direction = 0
                if (event.x < getPercent(width.toFloat(), 33f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_LEFT
                }
                if (event.x > getPercent(width.toFloat(), 66f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_RIGHT
                }
                if (event.y > getPercent(height.toFloat(), 66f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_DOWN
                }
                if (event.y < getPercent(height.toFloat(), 33f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_UP
                }
                newDirectionCallback(direction)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                direction = 0
                newDirectionCallback(direction)
                invalidate()
                return true
            }
        }
        return true
    }

    fun interface DigitalPadListener {
        fun onDirectionChange(direction: Int)
    }

    companion object {
        const val DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0
        const val DIGITAL_PAD_DIRECTION_LEFT = 1
        const val DIGITAL_PAD_DIRECTION_UP = 2
        const val DIGITAL_PAD_DIRECTION_RIGHT = 4
        const val DIGITAL_PAD_DIRECTION_DOWN = 8
        private const val DPAD_MARGIN = 5
    }
}
