package com.ineedmypills.vibemis.binding.input

import android.annotation.TargetApi
import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.binding.input.driver.UsbDriverService
import com.ineedmypills.vibemis.nvstream.input.ControllerPacket
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.utils.Vector2d
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal object ControllerRumbleMapper {
    const val MOUSE_EMULATION_SPEED_SCALE = 0.2f
    const val MOUSE_SCROLL_FAST_MULTIPLIER = 3
    const val REMAP_IGNORE = -1
    const val REMAP_CONSUME = -2

    val ANDROID_TO_LI_BUTTON_MAP: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerPacket.A_FLAG,
        KeyEvent.KEYCODE_BUTTON_B to ControllerPacket.B_FLAG,
        KeyEvent.KEYCODE_BUTTON_X to ControllerPacket.X_FLAG,
        KeyEvent.KEYCODE_BUTTON_Y to ControllerPacket.Y_FLAG,
        KeyEvent.KEYCODE_DPAD_UP to ControllerPacket.UP_FLAG,
        KeyEvent.KEYCODE_DPAD_DOWN to ControllerPacket.DOWN_FLAG,
        KeyEvent.KEYCODE_DPAD_LEFT to ControllerPacket.LEFT_FLAG,
        KeyEvent.KEYCODE_DPAD_RIGHT to ControllerPacket.RIGHT_FLAG,
        KeyEvent.KEYCODE_DPAD_UP_LEFT to (ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG),
        KeyEvent.KEYCODE_DPAD_UP_RIGHT to (ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG),
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT to (ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG),
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT to (ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG),
        KeyEvent.KEYCODE_BUTTON_L1 to ControllerPacket.LB_FLAG,
        KeyEvent.KEYCODE_BUTTON_R1 to ControllerPacket.RB_FLAG,
        KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerPacket.LS_CLK_FLAG,
        KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerPacket.RS_CLK_FLAG,
        KeyEvent.KEYCODE_BUTTON_START to ControllerPacket.PLAY_FLAG,
        KeyEvent.KEYCODE_MENU to ControllerPacket.PLAY_FLAG,
        KeyEvent.KEYCODE_BUTTON_SELECT to ControllerPacket.BACK_FLAG,
        KeyEvent.KEYCODE_BACK to ControllerPacket.BACK_FLAG,
        KeyEvent.KEYCODE_BUTTON_MODE to ControllerPacket.SPECIAL_BUTTON_FLAG,
        KeyEvent.KEYCODE_MEDIA_RECORD to ControllerPacket.MISC_FLAG,
        KeyEvent.KEYCODE_BUTTON_1 to ControllerPacket.TOUCHPAD_FLAG
    )

    fun applyMouseSpeedScale(delta: Int): Int {
        if (delta == 0) return 0
        val result = (delta * MOUSE_EMULATION_SPEED_SCALE).roundToInt()
        return if (result == 0) (if (delta > 0) 1 else -1) else result
    }

    fun maxByMagnitude(a: Byte, b: Byte): Byte =
        if (abs(a.toInt()) > abs(b.toInt())) a else b

    fun maxByMagnitude(a: Short, b: Short): Short =
        if (abs(a.toInt()) > abs(b.toInt())) a else b

    fun areBatteryCapacitiesEqual(first: Float, second: Float): Boolean {
        return if (!first.isNaN() && !second.isNaN()) {
            first == second
        } else {
            first.isNaN() == second.isNaN()
        }
    }

    fun handleFlipFaceButtons(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> keyCode
    }

    fun handleDeadZone(stickVector: Vector2d, deadzoneRadius: Float) {
        if (deadzoneRadius > 0f) {
            if (stickVector.magnitude <= deadzoneRadius) {
                stickVector.initialize(0f, 0f)
            }
        } else {
            val currentMagnitude = stickVector.magnitude
            if (currentMagnitude < 0.01) {
                stickVector.initialize(0f, 0f)
                return
            }
            val remainingMagnitude = 1.0 + deadzoneRadius
            val normalizedMagnitude = -deadzoneRadius + currentMagnitude * remainingMagnitude
            if (normalizedMagnitude >= 1.0) return
            val scaleFactor = normalizedMagnitude / currentMagnitude
            stickVector.x = (stickVector.x * scaleFactor).toFloat()
            stickVector.y = (stickVector.y * scaleFactor).toFloat()
        }
    }

    fun convertRawStickAxisToPixelMovement(stickX: Short, stickY: Short): Vector2d {
        val vector = Vector2d()
        vector.initialize(stickX.toFloat(), stickY.toFloat())
        vector.scalarMultiply(1.0 / 32766.0)
        vector.scalarMultiply(4.0)
        if (vector.magnitude > 0) {
            vector.scalarMultiply(vector.magnitude.pow(2.0))
        }
        return vector
    }

    fun normalizeRawValueWithRange(valueIn: Float, range: InputDevice.MotionRange): Float {
        val clamped = valueIn.coerceIn(range.min, range.max) - range.min
        return clamped / range.range
    }

    fun getMotionRangeForJoystickAxis(dev: InputDevice, axis: Int): InputDevice.MotionRange? {
        return dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
    }

    fun hasJoystickAxes(device: InputDevice): Boolean {
        return (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
            getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
    }

    fun hasGamepadButtons(device: InputDevice): Boolean {
        return (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    fun isGameControllerDevice(device: InputDevice?): Boolean {
        if (device == null) return true
        if (hasJoystickAxes(device) || hasGamepadButtons(device)) return true

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && device.id == -1) {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (hasJoystickAxes(dev) || hasGamepadButtons(dev)) return true
            }
        }
        return device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    private fun enumerateControllerMasks(context: Context): Pair<Short, Short> {
        var count = 0
        var mask = 0
        var psMask = 0

        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (hasJoystickAxes(dev)) {
                VibemisLog.info("Counting InputDevice: ${dev.name}")
                val bit = 1 shl count++
                mask = mask or bit
                if (dev.vendorId == 0x054c) {
                    psMask = psMask or bit
                }
            }
        }

        val prefs = PreferenceConfiguration.readPreferences(context)
        if (prefs.usbDriver) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager != null) {
                for (dev in usbManager.deviceList.values) {
                    if (UsbDriverService.shouldClaimDevice(dev, false) &&
                        !UsbDriverService.isRecognizedInputDevice(dev)
                    ) {
                        VibemisLog.info("Counting UsbDevice: ${dev.deviceName}")
                        val bit = 1 shl count++
                        mask = mask or bit
                        if (dev.vendorId == 0x054c) {
                            psMask = psMask or bit
                        }
                    }
                }
            }
        }

        if (prefs.onscreenController) {
            VibemisLog.info("Counting OSC gamepad")
            mask = mask or 1
        }

        VibemisLog.info("Enumerated $count gamepads (psMask=$psMask)")
        return mask.toShort() to psMask.toShort()
    }

    fun getAttachedControllerMask(context: Context): Short =
        enumerateControllerMasks(context).first

    fun getAttachedPlayStationControllerMask(context: Context): Short =
        enumerateControllerMasks(context).second

    fun hasButtonUnderTouchpad(dev: InputDevice, type: Byte): Boolean {
        if ((dev.sources and InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
            return false
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            try {
                return dev.javaClass.getMethod("hasButtonUnderPad").invoke(dev) as? Boolean ?: false
            } catch (_: Throwable) {
            }
        }
        return type == VibemisBridge.VB_CTYPE_PS
    }

    fun isExternal(dev: InputDevice): Boolean {
        if (Build.MODEL == "Tinker Board") return true

        val deviceName = dev.name ?: ""
        if (deviceName.contains("gpio") ||
            deviceName.contains("joy_key") ||
            deviceName.contains("keypad") ||
            deviceName.equals("NVIDIA Corporation NVIDIA Controller v01.01", ignoreCase = true) ||
            deviceName.equals("NVIDIA Corporation NVIDIA Controller v01.02", ignoreCase = true) ||
            deviceName.equals("GR0006", ignoreCase = true)
        ) {
            VibemisLog.info("${dev.name} is internal by hardcoded mapping")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return dev.isExternal
        }
        return try {
            dev.javaClass.getMethod("isExternal").invoke(dev) as? Boolean ?: true
        } catch (_: Throwable) {
            true
        }
    }

    @TargetApi(31)
    fun hasDualAmplitudeControlledRumbleVibrators(vm: VibratorManager?): Boolean {
        if (vm == null) return false
        val vibratorIds = vm.vibratorIds
        if (vibratorIds.size != 2) return false
        return vibratorIds.all { vm.getVibrator(it).hasAmplitudeControl() }
    }

    @TargetApi(31)
    fun rumbleDualVibrators(vm: VibratorManager, lowFreqMotorIn: Short, highFreqMotorIn: Short) {
        val highFreqMotor = (highFreqMotorIn.toInt() shr 8) and 0xFF
        val lowFreqMotor = (lowFreqMotorIn.toInt() shr 8) and 0xFF

        if (lowFreqMotor == 0 && highFreqMotor == 0) {
            vm.cancel()
            return
        }

        val vibratorIds = vm.vibratorIds
        val vibratorAmplitudes = intArrayOf(highFreqMotor, lowFreqMotor)
        val combo = CombinedVibration.startParallel()

        for (i in vibratorIds.indices) {
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(vibratorIds[i], VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]))
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vm.vibrate(combo.combine(), vibrationAttributes.build())
    }

    @TargetApi(31)
    fun hasQuadAmplitudeControlledRumbleVibrators(vm: VibratorManager?): Boolean {
        if (vm == null) return false
        val vibratorIds = vm.vibratorIds
        if (vibratorIds.size != 4) return false
        return vibratorIds.all { vm.getVibrator(it).hasAmplitudeControl() }
    }

    @TargetApi(31)
    fun rumbleQuadVibrators(
        vm: VibratorManager,
        lowFreqMotorIn: Short,
        highFreqMotorIn: Short,
        leftTriggerIn: Short,
        rightTriggerIn: Short
    ) {
        val highFreqMotor = (highFreqMotorIn.toInt() shr 8) and 0xFF
        val lowFreqMotor = (lowFreqMotorIn.toInt() shr 8) and 0xFF
        val leftTrigger = (leftTriggerIn.toInt() shr 8) and 0xFF
        val rightTrigger = (rightTriggerIn.toInt() shr 8) and 0xFF

        if (lowFreqMotor == 0 && highFreqMotor == 0 && leftTrigger == 0 && rightTrigger == 0) {
            vm.cancel()
            return
        }

        val vibratorIds = vm.vibratorIds
        val vibratorAmplitudes = intArrayOf(highFreqMotor, lowFreqMotor, leftTrigger, rightTrigger)
        val combo = CombinedVibration.startParallel()

        for (i in vibratorIds.indices) {
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(vibratorIds[i], VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]))
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vm.vibrate(combo.combine(), vibrationAttributes.build())
    }

    @Suppress("DEPRECATION")
    fun rumbleSingleVibrator(vibrator: Vibrator, lowFreqMotor: Short, highFreqMotor: Short) {
        val lowFreqMotorMSB = (lowFreqMotor.toInt() shr 8) and 0xFF
        val highFreqMotorMSB = (highFreqMotor.toInt() shr 8) and 0xFF
        val simulatedAmplitude = min(255, (lowFreqMotorMSB * 0.80 + highFreqMotorMSB * 0.33).toInt())

        if (simulatedAmplitude == 0) {
            vibrator.cancel()
            return
        }

        if (vibrator.hasAmplitudeControl()) {
            val effect = VibrationEffect.createOneShot(60000, simulatedAmplitude)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val vibrationAttributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
                vibrator.vibrate(effect, vibrationAttributes)
            } else {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build()
                vibrator.vibrate(effect, audioAttributes)
            }
            return
        }

        val pwmPeriod = 20L
        val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
        val offTime = pwmPeriod - onTime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_MEDIA)
                .build()
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, onTime, offTime), 0), vibrationAttributes)
        } else {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
        }
    }
}
