package com.ineedmypills.vibemis.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import java.util.ArrayList

open class DigitalButton(
    controller: VirtualController,
    elementId: Int,
    private val layer: Int,
    context: Context
) : VirtualControllerElement(controller, context, elementId) {

    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners = ArrayList<DigitalButtonListener>()
    private var text = ""
    private var icon = -1
    private var iconPress = -1
    private val timerLongClickTimeout = 3000L
    private val longClickRunnable = Runnable { onLongClickCallback() }

    private val paint = Paint()
    private val rect = RectF()
    private var movingButton: DigitalButton? = null

    internal fun inRange(x: Float, y: Float): Boolean {
        return (this.x < x && this.x + this.width > x) &&
            (this.y < y && this.y + this.height > y)
    }

    fun checkMovement(x: Float, y: Float, movingButton: DigitalButton): Boolean {
        if (movingButton.layer != this.layer) {
            return false
        }

        val wasPressed = isPressed

        if ((this.movingButton == null || movingButton === this.movingButton) && this.inRange(x, y)) {
            if (this.isPressed != movingButton.isPressed) {
                this.isPressed = movingButton.isPressed
            }
        } else if (movingButton === this.movingButton) {
            this.isPressed = false
        }

        if (wasPressed != isPressed) {
            if (isPressed) {
                this.movingButton = movingButton
                onClickCallback()
            } else {
                this.movingButton = null
                onReleaseCallback()
            }

            invalidate()
            return true
        }

        return false
    }

    private fun checkMovementForAllButtons(x: Float, y: Float) {
        for (element in virtualController.elements) {
            if (element !== this && element is DigitalButton) {
                element.checkMovement(x, y, this)
            }
        }
    }

    fun addDigitalButtonListener(listener: DigitalButtonListener) {
        listeners.add(listener)
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setIcon(id: Int) {
        this.icon = id
        invalidate()
    }

    fun setIconPress(iconPress: Int) {
        this.iconPress = iconPress
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(width.toFloat(), 25f)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()
        paint.color = if (isPressed) pressedColor else getDefaultColor()

        rect.left = paint.strokeWidth
        rect.top = paint.strokeWidth
        rect.right = width - rect.left
        rect.bottom = height - rect.top

        val prefs = PreferenceConfiguration.readPreferences(context)
        if (prefs.enableOnScreenStyleOfficial) {
            paint.style = Paint.Style.STROKE
            if (prefs.enableKeyboardSquare) {
                canvas.drawRect(rect, paint)
            } else {
                canvas.drawOval(rect, paint)
            }
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = (getDefaultStrokeWidth() / 2).toFloat()
            canvas.drawText(text, getPercent(width.toFloat(), 50f), getPercent(height.toFloat(), 63f), paint)
            return
        }

        val oscOpacity = prefs.oscOpacity
        if (icon != -1) {
            @Suppress("DEPRECATION")
            val d = resources.getDrawable(if (isPressed) iconPress else icon)
            d.setBounds(5, 5, width - 5, height - 5)
            d.alpha = (oscOpacity * 2.55).toInt()
            d.draw(canvas)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (getDefaultStrokeWidth() / 2).toFloat()
            canvas.drawText(text, getPercent(width.toFloat(), 50f), getPercent(height.toFloat(), 63f), paint)
        }

        val bIsMoving = virtualController.controllerMode == VirtualController.ControllerMode.MoveButtons
        val bIsResizing = virtualController.controllerMode == VirtualController.ControllerMode.ResizeButtons
        val bIsEnable = virtualController.controllerMode == VirtualController.ControllerMode.DisableEnableButtons

        if (bIsMoving || bIsResizing || bIsEnable || icon == -1) {
            paint.style = Paint.Style.STROKE
            canvas.drawRect(rect, paint)
        }
    }

    private fun onClickCallback() {
        _DBG("clicked")
        for (listener in listeners) {
            listener.onClick()
        }

        virtualController.handler.removeCallbacks(longClickRunnable)
        virtualController.handler.postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        _DBG("long click")
        for (listener in listeners) {
            listener.onLongClick()
        }
    }

    private fun onReleaseCallback() {
        _DBG("released")
        for (listener in listeners) {
            listener.onRelease()
        }

        virtualController.handler.removeCallbacks(longClickRunnable)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        val x = getX() + event.x
        val y = getY() + event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingButton = null
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                checkMovementForAllButtons(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                checkMovementForAllButtons(x, y)
                invalidate()
                return true
            }
        }
        return true
    }
}
