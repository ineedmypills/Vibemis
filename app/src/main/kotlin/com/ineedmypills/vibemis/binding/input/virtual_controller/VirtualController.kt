package com.ineedmypills.vibemis.binding.input.virtual_controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.binding.input.ControllerHandler
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import java.util.ArrayList

class VirtualController(
    private val controllerHandler: ControllerHandler?,
    private val frame_layout: FrameLayout?,
    private val context: Context
) {
    class ControllerInputContext {
        @JvmField
        var inputMap: Int = 0
        @JvmField
        var leftTrigger: Byte = 0x00
        @JvmField
        var rightTrigger: Byte = 0x00
        @JvmField
        var rightStickX: Short = 0x0000
        @JvmField
        var rightStickY: Short = 0x0000
        @JvmField
        var leftStickX: Short = 0x0000
        @JvmField
        var leftStickY: Short = 0x0000
    }

    enum class ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    val handler = Handler(Looper.getMainLooper())

    private val delayedRetransmitRunnable = Runnable {
        sendControllerInputContextInternal()
    }

    @JvmField
    var currentMode = ControllerMode.Active
    @JvmField
    var inputContext = ControllerInputContext()

    private val buttonConfigure: Button
    val elements: MutableList<VirtualControllerElement> = ArrayList()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val defaultVibrationEffect: VibrationEffect =
        VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)

    init {
        buttonConfigure = Button(context).apply {
            alpha = 0.25f
            isFocusable = false
            setBackgroundResource(R.drawable.ic_settings)
            setOnClickListener {
                val message: String
                when (currentMode) {
                    ControllerMode.Active -> {
                        currentMode = ControllerMode.DisableEnableButtons
                        showElements()
                        message = context.getString(R.string.configuration_mode_disable_enable_buttons)
                    }
                    ControllerMode.DisableEnableButtons -> {
                        currentMode = ControllerMode.MoveButtons
                        showEnabledElements()
                        message = context.getString(R.string.configuration_mode_move_buttons)
                    }
                    ControllerMode.MoveButtons -> {
                        currentMode = ControllerMode.ResizeButtons
                        message = context.getString(R.string.configuration_mode_resize_buttons)
                    }
                    else -> {
                        currentMode = ControllerMode.Active
                        VirtualControllerConfigurationLoader.saveProfile(this@VirtualController, context)
                        message = context.getString(R.string.configuration_mode_exiting)
                    }
                }

                (context as? Game)?.showTransientOverlayMessage(message)
                invalidate()

                for (element in elements) {
                    element.invalidate()
                }
            }
        }
    }

    fun hide() {
        for (element in elements) {
            element.visibility = View.GONE
        }
        buttonConfigure.visibility = View.GONE
    }

    fun show() {
        showEnabledElements()
        buttonConfigure.visibility = View.VISIBLE
    }

    fun switchShowHide(): Int {
        return if (buttonConfigure.visibility == View.VISIBLE) {
            hide()
            0
        } else {
            show()
            1
        }
    }

    fun showElements() {
        for (element in elements) {
            element.visibility = View.VISIBLE
        }
    }

    fun showEnabledElements() {
        for (element in elements) {
            element.visibility = if (element.enabled) View.VISIBLE else View.GONE
        }
    }

    fun removeElements() {
        for (element in elements) {
            frame_layout?.removeView(element)
        }
        elements.clear()
        frame_layout?.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) {
        for (element in elements) {
            element.setOpacity(opacity)
        }
    }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.setMargins(x, y, 0, 0)
        frame_layout?.addView(element, layoutParams)
    }

    fun refreshLayout() {
        removeElements()

        val screen = context.resources.displayMetrics
        val buttonSize = (screen.heightPixels * 0.06f).toInt()
        val params = FrameLayout.LayoutParams(buttonSize, buttonSize)
        params.leftMargin = 15
        params.topMargin = 15
        frame_layout?.addView(buttonConfigure, params)

        VirtualControllerConfigurationLoader.createDefaultLayout(this, context)
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context)
    }

    val controllerMode: ControllerMode
        get() = currentMode

    val controllerInputContext: ControllerInputContext
        get() = inputContext

    private fun sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + ${inputContext.inputMap}")
        _DBG("LEFT_TRIGGER ${inputContext.leftTrigger}")
        _DBG("RIGHT_TRIGGER ${inputContext.rightTrigger}")
        _DBG("LEFT STICK X: ${inputContext.leftStickX} Y: ${inputContext.leftStickY}")
        _DBG("RIGHT STICK X: ${inputContext.rightStickX} Y: ${inputContext.rightStickY}")

        controllerHandler?.reportOscState(
            inputContext.inputMap,
            inputContext.leftStickX,
            inputContext.leftStickY,
            inputContext.rightStickX,
            inputContext.rightStickY,
            inputContext.leftTrigger,
            inputContext.rightTrigger
        )
    }

    @JvmOverloads
    fun sendControllerInputContext(vibrationDuration: Long = 0, vibrationAmplitude: Int = 0) {
        handler.removeCallbacks(delayedRetransmitRunnable)

        sendControllerInputContextInternal()
        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            val effect = if (vibrationDuration == 0L) {
                defaultVibrationEffect
            } else {
                VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude)
            }
            vibrator.vibrate(effect)
        }

        handler.postDelayed(delayedRetransmitRunnable, 25)
        handler.postDelayed(delayedRetransmitRunnable, 50)
        handler.postDelayed(delayedRetransmitRunnable, 75)
    }

    companion object {
        private const val _PRINT_DEBUG_INFORMATION = false

        private fun _DBG(text: String) {
            if (_PRINT_DEBUG_INFORMATION) {
                VibemisLog.info("VirtualController: $text")
            }
        }
    }
}
