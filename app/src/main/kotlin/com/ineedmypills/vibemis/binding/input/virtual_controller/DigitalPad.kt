package com.ineedmypills.vibemis.binding.input.virtual_controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import java.util.ArrayList

class DigitalPad(
    controller: VirtualController,
    context: Context
) : VirtualControllerElement(controller, context, EID_DPAD) {

    @JvmField
    var direction: Int = DIGITAL_PAD_DIRECTION_NO_DIRECTION
    @JvmField
    val listeners = ArrayList<DigitalPadListener>()

    private val rect = RectF()
    private val paint = Paint()

    fun addDigitalPadListener(listener: DigitalPadListener) {
        listeners.add(listener)
    }

    @Suppress("DEPRECATION")
    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(getCorrectWidth().toFloat(), 20f)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        val prefs = PreferenceConfiguration.readPreferences(context)
        if (!prefs.enableOnScreenStyleOfficial) {
            val oscOpacity = prefs.oscOpacity

            paint.color = if (isPressed) pressedColor else getDefaultColor()
            rect.left = paint.strokeWidth
            rect.top = paint.strokeWidth
            rect.right = width - rect.left
            rect.bottom = height - rect.top

            val bIsMoving = virtualController.controllerMode == VirtualController.ControllerMode.MoveButtons
            val bIsResizing = virtualController.controllerMode == VirtualController.ControllerMode.ResizeButtons
            val bIsEnable = virtualController.controllerMode == VirtualController.ControllerMode.DisableEnableButtons

            if (bIsMoving || bIsResizing || bIsEnable) {
                paint.style = Paint.Style.STROKE
                canvas.drawRect(rect, paint)
            }

            val alpha = (oscOpacity * 2.55).toInt()

            if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad)
                d.setBounds(5, 5, width - 5, height - 5)
                d.alpha = alpha
                d.draw(canvas)
            }
            if (direction == DIGITAL_PAD_DIRECTION_UP) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up)
                d.setBounds(5, 5, width - 5, height - 5)
                d.alpha = alpha
                d.draw(canvas)
            }
            if (direction == DIGITAL_PAD_DIRECTION_DOWN) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up)
                val newD = rotateDrawable(d, 180f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            if (direction == DIGITAL_PAD_DIRECTION_LEFT) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up)
                val newD = rotateDrawable(d, 270f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            if (direction == DIGITAL_PAD_DIRECTION_RIGHT) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up)
                val newD = rotateDrawable(d, 90f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up_right)
                val newD = rotateDrawable(d, 90f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up_right)
                d.setBounds(5, 5, width - 5, height - 5)
                d.alpha = alpha
                d.draw(canvas)
            }
            if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up_right)
                val newD = rotateDrawable(d, 180f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) {
                val d = resources.getDrawable(R.drawable.facebutton_dpad_up_right)
                val newD = rotateDrawable(d, 270f)
                newD.setBounds(5, 5, width - 5, height - 5)
                newD.alpha = alpha
                newD.draw(canvas)
            }
            return
        }

        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            paint.style = Paint.Style.STROKE
            paint.color = getDefaultColor()
            canvas.drawRect(
                getPercent(width.toFloat(), 36f),
                getPercent(height.toFloat(), 36f),
                getPercent(width.toFloat(), 63f),
                getPercent(height.toFloat(), 63f),
                paint
            )
        }

        // draw left rect
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f),
            getPercent(height.toFloat(), 66f),
            paint
        )

        // draw up rect
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f),
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(width.toFloat(), 66f),
            getPercent(height.toFloat(), 33f),
            paint
        )

        // draw right rect
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 66f),
            getPercent(height.toFloat(), 33f),
            width - (paint.strokeWidth + DPAD_MARGIN),
            getPercent(height.toFloat(), 66f),
            paint
        )

        // draw down rect
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f),
            getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        // draw left up line
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f),
            paint.strokeWidth + DPAD_MARGIN,
            paint
        )

        // draw up right line
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0 && (direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 66f),
            paint.strokeWidth + DPAD_MARGIN,
            width - (paint.strokeWidth + DPAD_MARGIN),
            getPercent(height.toFloat(), 33f),
            paint
        )

        // draw right down line
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            width - paint.strokeWidth,
            getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        // draw down left line
        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0 && (direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) {
            pressedColor
        } else {
            getDefaultColor()
        }
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 33f),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height.toFloat(), 66f),
            paint
        )
    }

    fun rotateDrawable(vectorDrawable: Drawable, angle: Float): Drawable {
        val w = vectorDrawable.intrinsicWidth
        val h = vectorDrawable.intrinsicHeight
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
        vectorDrawable.draw(canvas)

        val matrix = Matrix()
        matrix.postRotate(angle)
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return BitmapDrawable(resources, rotatedBitmap)
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
