package com.ineedmypills.vibemis.binding.input

import android.app.Activity
import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.GameMenu
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.ANDROID_TO_LI_BUTTON_MAP
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.MOUSE_SCROLL_FAST_MULTIPLIER
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.REMAP_CONSUME
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.REMAP_IGNORE
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.applyMouseSpeedScale
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.areBatteryCapacitiesEqual
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.convertRawStickAxisToPixelMovement
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.getMotionRangeForJoystickAxis
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.handleDeadZone
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.handleFlipFaceButtons
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.hasButtonUnderTouchpad
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.hasDualAmplitudeControlledRumbleVibrators
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.hasGamepadButtons
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.hasJoystickAxes
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.hasQuadAmplitudeControlledRumbleVibrators
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.isExternal
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.maxByMagnitude
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.normalizeRawValueWithRange
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.rumbleDualVibrators
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.rumbleQuadVibrators
import com.ineedmypills.vibemis.binding.input.ControllerRumbleMapper.rumbleSingleVibrator
import com.ineedmypills.vibemis.binding.input.driver.AbstractController
import com.ineedmypills.vibemis.binding.input.driver.UsbDriverListener
import com.ineedmypills.vibemis.nvstream.NvConnection
import com.ineedmypills.vibemis.nvstream.input.ControllerPacket
import com.ineedmypills.vibemis.nvstream.input.MouseButtonPacket
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.GameGestures
import com.ineedmypills.vibemis.utils.Vector2d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ControllerHandler(
    private val activityContext: Activity,
    private val conn: NvConnection,
    private val gestures: GameGestures,
    private val prefConfig: PreferenceConfiguration
) : InputManager.InputDeviceListener, UsbDriverListener {

    private val inputVector = Vector2d()
    private val inputDeviceContexts = SparseArray<InputDeviceContext>()
    private val usbDeviceContexts = SparseArray<UsbDeviceContext>()

    private val stickDeadzone: Double
    private val defaultContext = InputDeviceContext()
    private val inputManager: InputManager = activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    @Suppress("DEPRECATION")
    private val deviceVibrator: Vibrator = activityContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val deviceVibratorManager: VibratorManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        activityContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
    } else {
        null
    }
    private val deviceSensorManager: SensorManager = activityContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val backgroundHandlerThread = HandlerThread("ControllerHandler").apply { start() }
    private val backgroundThreadHandler = Handler(backgroundHandlerThread.looper)
    private val inputSender: InputSender? = if (prefConfig.snappyInput) InputSender(null) else null

    private var hasGameController = false
    private var stopped = false

    private val lastSentLeftStickX = ShortArray(MAX_CONTROLLERS)
    private val lastSentLeftStickY = ShortArray(MAX_CONTROLLERS)
    private val lastSentRightStickX = ShortArray(MAX_CONTROLLERS)
    private val lastSentRightStickY = ShortArray(MAX_CONTROLLERS)
    private val lastSentLeftTrigger = ByteArray(MAX_CONTROLLERS)
    private val lastSentRightTrigger = ByteArray(MAX_CONTROLLERS)
    private val lastSentInputMap = IntArray(MAX_CONTROLLERS)

    private var currentControllers: Short = 0
    private var initialControllers: Short = 0

    init {
        val deadzonePercentage = prefConfig.deadzonePercentage

        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if ((dev.sources and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (dev.sources and InputDevice.SOURCE_GAMEPAD) != 0
            ) {
                if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                    getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null
                ) {
                    hasGameController = true
                }
            }
        }

        stickDeadzone = deadzonePercentage / 100.0

        defaultContext.leftStickXAxis = MotionEvent.AXIS_X
        defaultContext.leftStickYAxis = MotionEvent.AXIS_Y
        defaultContext.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        defaultContext.rightStickXAxis = MotionEvent.AXIS_Z
        defaultContext.rightStickYAxis = MotionEvent.AXIS_RZ
        defaultContext.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        defaultContext.leftTriggerAxis = MotionEvent.AXIS_BRAKE
        defaultContext.rightTriggerAxis = MotionEvent.AXIS_GAS
        defaultContext.hatXAxis = MotionEvent.AXIS_HAT_X
        defaultContext.hatYAxis = MotionEvent.AXIS_HAT_Y
        defaultContext.controllerNumber = 0
        defaultContext.assignedControllerNumber = true
        defaultContext.external = false
        defaultContext.ignoreBack = true

        initialControllers = getAttachedControllerMask(activityContext)
        inputManager.registerInputDeviceListener(this, null)
    }

    fun hasController(): Boolean = hasGameController

    override fun onInputDeviceAdded(deviceId: Int) {}

    override fun onInputDeviceRemoved(deviceId: Int) {
        val context = inputDeviceContexts.get(deviceId)
        if (context != null) {
            VibemisLog.info("Removed controller: ${context.name} ($deviceId)")
            releaseControllerNumber(context)
            context.destroy()
            inputDeviceContexts.remove(deviceId)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        val existingContext = inputDeviceContexts.get(deviceId) ?: return

        VibemisLog.info("Device changed: ${existingContext.name} ($deviceId)")

        val newContext = createInputDeviceContextForDevice(device)
        newContext.migrateContext(existingContext)
        inputDeviceContexts.put(deviceId, newContext)
    }

    fun stop() {
        if (stopped) return
        stopped = true

        inputManager.unregisterInputDeviceListener(this)

        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).destroy()
        }
        for (i in 0 until usbDeviceContexts.size()) {
            usbDeviceContexts.valueAt(i).destroy()
        }

        deviceVibrator.cancel()
    }

    fun destroy() {
        if (!stopped) {
            stop()
        }
        try {
            inputSender?.shutdown()
        } catch (_: Throwable) {
        }
        backgroundHandlerThread.quit()
    }

    fun disableSensors() {
        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).disableSensors()
        }
    }

    fun enableSensors() {
        if (stopped) return
        for (i in 0 until inputDeviceContexts.size()) {
            inputDeviceContexts.valueAt(i).enableSensors()
        }
    }

    private fun releaseControllerNumber(context: GenericControllerContext) {
        if (context.reservedControllerNumber) {
            VibemisLog.info("Controller number ${context.controllerNumber} is now available")
            currentControllers = (currentControllers.toInt() and (1 shl context.controllerNumber.toInt()).inv()).toShort()
        }

        if (context.assignedControllerNumber) {
            conn.sendControllerInput(
                context.controllerNumber,
                getActiveControllerMask(),
                0,
                0,
                0,
                0,
                0,
                0,
                0
            )
        }
    }

    private fun isAssociatedJoystick(originalDevice: InputDevice, possibleAssociatedJoystick: InputDevice?): Boolean {
        if (possibleAssociatedJoystick == null) return false
        if ((possibleAssociatedJoystick.sources and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (possibleAssociatedJoystick.name == originalDevice.name) return false
        return possibleAssociatedJoystick.descriptor == originalDevice.descriptor
    }

    private fun assignControllerNumberIfNeeded(context: GenericControllerContext) {
        if (context.assignedControllerNumber) return

        if (context is InputDeviceContext) {
            if (context is UsbDeviceContext) {
                if (prefConfig.multiController) {
                    VibemisLog.info("Reserving the next available controller number for USB device")
                    for (i in 0 until MAX_GAMEPADS) {
                        if ((currentControllers.toInt() and (1 shl i)) == 0) {
                            currentControllers = (currentControllers.toInt() or (1 shl i)).toShort()
                            initialControllers = (initialControllers.toInt() and (1 shl i).inv()).toShort()
                            context.controllerNumber = i.toShort()
                            context.reservedControllerNumber = true
                            break
                        }
                    }
                } else {
                    VibemisLog.info("Not reserving a controller number")
                    context.controllerNumber = 0
                }

                if (prefConfig.gamepadMotionSensorsFallbackToDevice &&
                    context.controllerNumber.toInt() == 0 &&
                    (prefConfig.forceMotionSensorsFallbackToDevice || context.sensorManager == null)
                ) {
                    context.sensorManager = deviceSensorManager
                }
            } else {
                VibemisLog.info("${context.name} (${context.id}) needs a controller number assigned")
                if (!context.external) {
                    VibemisLog.info("Built-in buttons hardcoded as controller 0")
                    context.controllerNumber = 0
                } else if (prefConfig.multiController && context.hasJoystickAxes) {
                    VibemisLog.info("Reserving the next available controller number")
                    for (i in 0 until MAX_GAMEPADS) {
                        if ((currentControllers.toInt() and (1 shl i)) == 0) {
                            currentControllers = (currentControllers.toInt() or (1 shl i)).toShort()
                            initialControllers = (initialControllers.toInt() and (1 shl i).inv()).toShort()
                            context.controllerNumber = i.toShort()
                            context.reservedControllerNumber = true
                            break
                        }
                    }
                } else if (!context.hasJoystickAxes) {
                    context.controllerNumber = 0
                    val inDev = context.inputDevice
                    var associatedDevice: InputDevice? = if (inDev != null) InputDevice.getDevice(context.id + 1) else null
                    if (inDev != null && !isAssociatedJoystick(inDev, associatedDevice)) {
                        associatedDevice = InputDevice.getDevice(context.id - 1)
                        if (!isAssociatedJoystick(inDev, associatedDevice)) {
                            VibemisLog.info("No associated joystick device found")
                            associatedDevice = null
                        }
                    }

                    if (associatedDevice != null) {
                        var associatedDeviceContext = inputDeviceContexts.get(associatedDevice.id)
                        if (associatedDeviceContext == null) {
                            associatedDeviceContext = createInputDeviceContextForDevice(associatedDevice)
                            inputDeviceContexts.put(associatedDevice.id, associatedDeviceContext)
                        }
                        if (!associatedDeviceContext.assignedControllerNumber) {
                            assignControllerNumberIfNeeded(associatedDeviceContext)
                        }
                        context.controllerNumber = associatedDeviceContext.controllerNumber
                        VibemisLog.info("Propagated controller number from ${associatedDeviceContext.name}")
                    }
                } else {
                    VibemisLog.info("Not reserving a controller number")
                    context.controllerNumber = 0
                }

                if (prefConfig.gamepadMotionSensorsFallbackToDevice &&
                    context.controllerNumber.toInt() == 0 &&
                    (prefConfig.forceMotionSensorsFallbackToDevice || context.sensorManager == null)
                ) {
                    context.sensorManager = deviceSensorManager
                }
            }
        }

        VibemisLog.info("Assigned as controller ${context.controllerNumber}")
        context.assignedControllerNumber = true
        context.sendControllerArrival()
    }

    private fun createUsbDeviceContextForDevice(device: AbstractController): UsbDeviceContext {
        val context = UsbDeviceContext()
        context.id = device.controllerId
        context.device = device
        context.external = true
        context.vendorId = device.vendorId
        context.productId = device.productId
        context.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        context.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        context.triggerDeadzone = 0.13f
        return context
    }

    private fun shouldIgnoreBack(dev: InputDevice): Boolean {
        val devName = dev.name ?: ""
        if (devName.contains("Razer Serval")) return true
        if (!hasJoystickAxes(dev) && devName.lowercase().contains("remote")) return true

        if (!isExternal(dev)) {
            val im = activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager
            var foundInternalGamepad = false
            var foundInternalSelect = false
            for (id in im.inputDeviceIds) {
                val currentDev = im.getInputDevice(id)
                if (currentDev == null || isExternal(currentDev)) continue
                if (currentDev.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0]) {
                    foundInternalSelect = true
                }
                if (hasGamepadButtons(currentDev)) {
                    foundInternalGamepad = true
                }
            }
            return !foundInternalGamepad || foundInternalSelect
        } else {
            return !hasJoystickAxes(dev) && !hasGamepadButtons(dev)
        }
    }

    private fun createInputDeviceContextForDevice(dev: InputDevice): InputDeviceContext {
        val context = InputDeviceContext()
        val devName = dev.name

        VibemisLog.info("Creating controller context for device: $devName")
        VibemisLog.info("Vendor ID: ${dev.vendorId}")
        VibemisLog.info("Product ID: ${dev.productId}")
        VibemisLog.info(dev.toString())

        context.inputDevice = dev
        context.name = devName
        context.id = dev.id
        context.external = isExternal(dev)
        context.vendorId = dev.vendorId
        context.productId = dev.productId

        context.hasPaddles = VibemisBridge.guessControllerHasPaddles(context.vendorId, context.productId)
        context.hasShare = VibemisBridge.guessControllerHasShareButton(context.vendorId, context.productId)

        if (prefConfig.enableDeviceRumble) {
            context.vibrator = deviceVibrator
        } else {
            @Suppress("DEPRECATION")
            val devVibrator = dev.vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasQuadAmplitudeControlledRumbleVibrators(dev.vibratorManager)) {
                context.vibratorManager = dev.vibratorManager
                context.quadVibrators = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasDualAmplitudeControlledRumbleVibrators(dev.vibratorManager)) {
                context.vibratorManager = dev.vibratorManager
                context.quadVibrators = false
            } else if (devVibrator.hasVibrator()) {
                context.vibrator = devVibrator
            } else if (!context.external) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasQuadAmplitudeControlledRumbleVibrators(deviceVibratorManager)) {
                    context.vibratorManager = deviceVibratorManager
                    context.quadVibrators = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasDualAmplitudeControlledRumbleVibrators(deviceVibratorManager)) {
                    context.vibratorManager = deviceVibratorManager
                    context.quadVibrators = false
                } else if (deviceVibrator.hasVibrator()) {
                    context.vibrator = deviceVibrator
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                context.vendorId == 0x054c || context.vendorId == 0x057e) &&
            prefConfig.gamepadMotionSensors
        ) {
            if (dev.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                dev.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            ) {
                context.sensorManager = dev.sensorManager
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (light in dev.lightsManager.lights) {
                if (light.hasRgbControl()) {
                    context.hasRgbLed = true
                    break
                }
            }
        }

        val buttons = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK, 0)
        context.hasMode = buttons[0]
        context.hasSelect = buttons[1] || buttons[2]

        context.touchpadXRange = dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadYRange = dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadPressureRange = dev.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)

        context.leftStickXAxis = MotionEvent.AXIS_X
        context.leftStickYAxis = MotionEvent.AXIS_Y
        if (getMotionRangeForJoystickAxis(dev, context.leftStickXAxis) != null &&
            getMotionRangeForJoystickAxis(dev, context.leftStickYAxis) != null
        ) {
            hasGameController = true
            context.hasJoystickAxes = true
        }

        context.isDualShockStandaloneTouchpad =
            context.vendorId == 0x054c &&
                devName != null &&
                devName.endsWith(" Touchpad") &&
                dev.sources == (InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE)

        val leftTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_LTRIGGER)
        val rightTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RTRIGGER)
        val brakeRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_BRAKE)
        val gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS)
        val throttleRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_THROTTLE)

        if (leftTriggerRange != null && rightTriggerRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_LTRIGGER
            context.rightTriggerAxis = MotionEvent.AXIS_RTRIGGER
        } else if (brakeRange != null && gasRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_GAS
        } else if (brakeRange != null && throttleRange != null) {
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_THROTTLE
        } else {
            val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
            val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)
            if (rxRange != null && ryRange != null && devName != null) {
                if (dev.vendorId == 0x054c) {
                    if (dev.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]) {
                        VibemisLog.info("Detected non-standard DualShock 4 mapping")
                        context.isNonStandardDualShock4 = true
                    } else {
                        VibemisLog.info("Detected DualShock 4 (Linux standard mapping)")
                        context.usesLinuxGamepadStandardFaceButtons = true
                    }
                }

                if (context.isNonStandardDualShock4) {
                    context.leftTriggerAxis = MotionEvent.AXIS_RX
                    context.rightTriggerAxis = MotionEvent.AXIS_RY
                    context.hasSelect = true
                    context.hasMode = true
                } else {
                    context.rightStickXAxis = MotionEvent.AXIS_RX
                    context.rightStickYAxis = MotionEvent.AXIS_RY
                    if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z) != null &&
                        getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ) != null
                    ) {
                        context.leftTriggerAxis = MotionEvent.AXIS_Z
                        context.rightTriggerAxis = MotionEvent.AXIS_RZ
                    }
                }
                context.triggersIdleNegative = true
            }
        }

        if (context.rightStickXAxis == -1 && context.rightStickYAxis == -1) {
            val zRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z)
            val rzRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ)
            if (zRange != null && rzRange != null) {
                context.rightStickXAxis = MotionEvent.AXIS_Z
                context.rightStickYAxis = MotionEvent.AXIS_RZ
            } else {
                val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
                val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)
                if (rxRange != null && ryRange != null) {
                    context.rightStickXAxis = MotionEvent.AXIS_RX
                    context.rightStickYAxis = MotionEvent.AXIS_RY
                }
            }
        }

        val hatXRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_X)
        val hatYRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_Y)
        if (hatXRange != null && hatYRange != null) {
            context.hatXAxis = MotionEvent.AXIS_HAT_X
            context.hatYAxis = MotionEvent.AXIS_HAT_Y
        }

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            context.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            context.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            val ltRange = getMotionRangeForJoystickAxis(dev, context.leftTriggerAxis)
            val rtRange = getMotionRangeForJoystickAxis(dev, context.rightTriggerAxis)
            if (ltRange != null && rtRange != null) {
                context.triggerDeadzone = max(abs(ltRange.flat), abs(rtRange.flat))
            }
            if (context.triggerDeadzone < 0.13f || context.triggerDeadzone > 0.30f) {
                context.triggerDeadzone = 0.13f
            }
        }

        if (dev.vendorId == 0x18d1 && dev.productId == 0x2c40) {
            context.backIsStart = true
            context.modeIsSelect = true
            context.triggerDeadzone = 0.30f
            context.hasSelect = true
            context.hasMode = false
        }

        context.ignoreBack = shouldIgnoreBack(dev)

        if (devName != null) {
            when {
                devName.contains("ASUS Gamepad") -> {
                    val hasStartKey = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, 0)
                    if (!hasStartKey[0] && !hasStartKey[1]) {
                        context.backIsStart = true
                        context.modeIsSelect = true
                        context.hasSelect = true
                        context.hasMode = false
                    }
                    context.triggerDeadzone = 0.30f
                }
                devName.contains("SHIELD") || devName.contains("NVIDIA Controller") -> {
                    if (devName.contains("NVIDIA Controller v01.03") || devName.contains("NVIDIA Controller v01.04")) {
                        context.searchIsMode = true
                        context.hasMode = true
                    }
                }
                devName.contains("Razer Serval") -> {
                    context.isServal = true
                    context.hasMode = true
                    context.hasSelect = true
                }
                devName == "Xbox Wireless Controller" -> {
                    if (gasRange == null) {
                        context.isNonStandardXboxBtController = true
                        context.hasMode = true
                        context.hasSelect = true
                    }
                }
            }
        }

        if (dev.vendorId == 0x044f && dev.productId == 0xb328) {
            context.hasMode = false
        }

        VibemisLog.info("Analog stick deadzone: ${context.leftStickDeadzoneRadius} ${context.rightStickDeadzoneRadius}")
        VibemisLog.info("Trigger deadzone: ${context.triggerDeadzone}")

        return context
    }

    private fun getContextForEvent(event: InputEvent): InputDeviceContext? {
        if (stopped) return null
        if (event.deviceId == 0) return defaultContext
        val dev = event.device ?: return null

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && event.deviceId == -1) {
            return defaultContext
        }

        var context = inputDeviceContexts.get(event.deviceId)
        if (context != null) return context

        context = createInputDeviceContextForDevice(dev)
        inputDeviceContexts.put(event.deviceId, context)
        return context
    }

    private fun getActiveControllerMask(): Short {
        return if (prefConfig.multiController) {
            (currentControllers.toInt() or initialControllers.toInt() or (if (prefConfig.onscreenController) 1 else 0)).toShort()
        } else {
            1
        }
    }

    private fun sendControllerBatteryPacket(context: InputDeviceContext) {
        val inDev = context.inputDevice ?: return
        var currentBatteryStatus = BatteryState.STATUS_FULL
        var currentBatteryCapacity = 0f
        var batteryPresent = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val batteryState = inDev.batteryState
            batteryPresent = batteryState.isPresent
            if (batteryPresent) {
                currentBatteryStatus = batteryState.status
                currentBatteryCapacity = batteryState.capacity
            }
        }

        if (!batteryPresent) {
            return
        }

        if (currentBatteryStatus != context.lastReportedBatteryStatus ||
            !areBatteryCapacitiesEqual(currentBatteryCapacity, context.lastReportedBatteryCapacity)
        ) {
            val state: Byte = when (currentBatteryStatus) {
                BatteryState.STATUS_UNKNOWN -> VibemisBridge.VB_BATTERY_STATE_UNKNOWN
                BatteryState.STATUS_CHARGING -> VibemisBridge.VB_BATTERY_STATE_CHARGING
                BatteryState.STATUS_DISCHARGING -> VibemisBridge.VB_BATTERY_STATE_DISCHARGING
                BatteryState.STATUS_NOT_CHARGING -> VibemisBridge.VB_BATTERY_STATE_NOT_CHARGING
                BatteryState.STATUS_FULL -> VibemisBridge.VB_BATTERY_STATE_FULL
                else -> return
            }

            val percentage: Byte = if (currentBatteryCapacity.isNaN()) {
                VibemisBridge.VB_BATTERY_PERCENTAGE_UNKNOWN
            } else {
                (currentBatteryCapacity * 100).toInt().toByte()
            }

            conn.sendControllerBatteryEvent(context.controllerNumber.toByte(), state, percentage)
            context.lastReportedBatteryStatus = currentBatteryStatus
            context.lastReportedBatteryCapacity = currentBatteryCapacity
        }
    }

    private fun sendControllerInputPacket(originalContext: GenericControllerContext) {
        assignControllerNumberIfNeeded(originalContext)

        val controllerNumber = originalContext.controllerNumber
        var inputMap = 0
        var leftTrigger: Byte = 0
        var rightTrigger: Byte = 0
        var leftStickX: Short = 0
        var leftStickY: Short = 0
        var rightStickX: Short = 0
        var rightStickY: Short = 0

        for (i in 0 until inputDeviceContexts.size()) {
            val context = inputDeviceContexts.valueAt(i)
            if (context.assignedControllerNumber &&
                context.controllerNumber == controllerNumber &&
                context.mouseEmulationActive == originalContext.mouseEmulationActive
            ) {
                inputMap = inputMap or context.inputMap
                leftTrigger = (leftTrigger.toInt() or maxByMagnitude(leftTrigger, context.leftTrigger).toInt()).toByte()
                rightTrigger = (rightTrigger.toInt() or maxByMagnitude(rightTrigger, context.rightTrigger).toInt()).toByte()
                leftStickX = (leftStickX.toInt() or maxByMagnitude(leftStickX, context.leftStickX).toInt()).toShort()
                leftStickY = (leftStickY.toInt() or maxByMagnitude(leftStickY, context.leftStickY).toInt()).toShort()
                rightStickX = (rightStickX.toInt() or maxByMagnitude(rightStickX, context.rightStickX).toInt()).toShort()
                rightStickY = (rightStickY.toInt() or maxByMagnitude(rightStickY, context.rightStickY).toInt()).toShort()
            }
        }
        for (i in 0 until usbDeviceContexts.size()) {
            val context = usbDeviceContexts.valueAt(i)
            if (context.assignedControllerNumber &&
                context.controllerNumber == controllerNumber &&
                context.mouseEmulationActive == originalContext.mouseEmulationActive
            ) {
                inputMap = inputMap or context.inputMap
                leftTrigger = (leftTrigger.toInt() or maxByMagnitude(leftTrigger, context.leftTrigger).toInt()).toByte()
                rightTrigger = (rightTrigger.toInt() or maxByMagnitude(rightTrigger, context.rightTrigger).toInt()).toByte()
                leftStickX = (leftStickX.toInt() or maxByMagnitude(leftStickX, context.leftStickX).toInt()).toShort()
                leftStickY = (leftStickY.toInt() or maxByMagnitude(leftStickY, context.leftStickY).toInt()).toShort()
                rightStickX = (rightStickX.toInt() or maxByMagnitude(rightStickX, context.rightStickX).toInt()).toShort()
                rightStickY = (rightStickY.toInt() or maxByMagnitude(rightStickY, context.rightStickY).toInt()).toShort()
            }
        }
        if (defaultContext.controllerNumber == controllerNumber) {
            inputMap = inputMap or defaultContext.inputMap
            leftTrigger = (leftTrigger.toInt() or maxByMagnitude(leftTrigger, defaultContext.leftTrigger).toInt()).toByte()
            rightTrigger = (rightTrigger.toInt() or maxByMagnitude(rightTrigger, defaultContext.rightTrigger).toInt()).toByte()
            leftStickX = (leftStickX.toInt() or maxByMagnitude(leftStickX, defaultContext.leftStickX).toInt()).toShort()
            leftStickY = (leftStickY.toInt() or maxByMagnitude(leftStickY, defaultContext.leftStickY).toInt()).toShort()
            rightStickX = (rightStickX.toInt() or maxByMagnitude(rightStickX, defaultContext.rightStickX).toInt()).toShort()
            rightStickY = (rightStickY.toInt() or maxByMagnitude(rightStickY, defaultContext.rightStickY).toInt()).toShort()
        }

        if (originalContext.mouseEmulationActive) {
            val changedMask = inputMap xor originalContext.mouseEmulationLastInputMap
            val aDown = (inputMap and ControllerPacket.A_FLAG) != 0
            val bDown = (inputMap and ControllerPacket.B_FLAG) != 0
            val xDown = (inputMap and ControllerPacket.X_FLAG) != 0
            val yDown = (inputMap and ControllerPacket.Y_FLAG) != 0

            originalContext.mouseEmulationLastInputMap = inputMap

            if ((changedMask and ControllerPacket.X_FLAG) != 0) {
                originalContext.mouseEmulationXDown = xDown
            }

            if ((changedMask and ControllerPacket.Y_FLAG) != 0 && yDown) {
                originalContext.mouseEmulationPixelMultiplier *= 2
                if (originalContext.mouseEmulationPixelMultiplier > 255) {
                    originalContext.mouseEmulationPixelMultiplier = 1
                }
            }

            if ((changedMask and ControllerPacket.A_FLAG) != 0) {
                if (aDown) {
                    inputSender?.post { conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT) }
                        ?: conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                } else {
                    inputSender?.post { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT) }
                        ?: conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                }
            }
            if ((changedMask and ControllerPacket.B_FLAG) != 0) {
                if (bDown) {
                    inputSender?.post { conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT) }
                        ?: conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                } else {
                    inputSender?.post { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT) }
                        ?: conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                }
            }

            val fastScroll = (leftTrigger.toInt() and 0xFF) > 0
            val scrollStep = (if (fastScroll) MOUSE_SCROLL_FAST_MULTIPLIER else 1).toByte()
            val negScrollStep = (-scrollStep).toByte()

            if ((changedMask and ControllerPacket.UP_FLAG) != 0 && (inputMap and ControllerPacket.UP_FLAG) != 0) {
                inputSender?.post { conn.sendMouseScroll(scrollStep) } ?: conn.sendMouseScroll(scrollStep)
            }
            if ((changedMask and ControllerPacket.DOWN_FLAG) != 0 && (inputMap and ControllerPacket.DOWN_FLAG) != 0) {
                inputSender?.post { conn.sendMouseScroll(negScrollStep) } ?: conn.sendMouseScroll(negScrollStep)
            }
            if ((changedMask and ControllerPacket.RIGHT_FLAG) != 0 && (inputMap and ControllerPacket.RIGHT_FLAG) != 0) {
                inputSender?.post { conn.sendMouseHScroll(scrollStep) } ?: conn.sendMouseHScroll(scrollStep)
            }
            if ((changedMask and ControllerPacket.LEFT_FLAG) != 0 && (inputMap and ControllerPacket.LEFT_FLAG) != 0) {
                inputSender?.post { conn.sendMouseHScroll(negScrollStep) } ?: conn.sendMouseHScroll(negScrollStep)
            }

            conn.sendControllerInput(
                controllerNumber,
                getActiveControllerMask(),
                0,
                0,
                0,
                0,
                0,
                0,
                0
            )
        } else {
            if (inputSender != null) {
                val ci = controllerNumber.toInt().coerceIn(0, MAX_CONTROLLERS - 1)
                val mapChanged = inputMap != lastSentInputMap[ci]
                val axesChanged =
                    abs(leftStickX - lastSentLeftStickX[ci]) >= AXIS_DELTA_MIN ||
                        abs(leftStickY - lastSentLeftStickY[ci]) >= AXIS_DELTA_MIN ||
                        abs(rightStickX - lastSentRightStickX[ci]) >= AXIS_DELTA_MIN ||
                        abs(rightStickY - lastSentRightStickY[ci]) >= AXIS_DELTA_MIN ||
                        abs((leftTrigger.toInt() and 0xFF) - (lastSentLeftTrigger[ci].toInt() and 0xFF)) >= TRIGGER_DELTA_MIN ||
                        abs((rightTrigger.toInt() and 0xFF) - (lastSentRightTrigger[ci].toInt() and 0xFF)) >= TRIGGER_DELTA_MIN

                if (!mapChanged && !axesChanged) return

                lastSentInputMap[ci] = inputMap
                lastSentLeftStickX[ci] = leftStickX
                lastSentLeftStickY[ci] = leftStickY
                lastSentRightStickX[ci] = rightStickX
                lastSentRightStickY[ci] = rightStickY
                lastSentLeftTrigger[ci] = leftTrigger
                lastSentRightTrigger[ci] = rightTrigger
            }

            conn.sendControllerInput(
                controllerNumber,
                getActiveControllerMask(),
                inputMap,
                leftTrigger,
                rightTrigger,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
        }
    }

    private fun handleRemapping(context: InputDeviceContext, event: KeyEvent): Int {
        if (context.ignoreBack && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return REMAP_IGNORE
        }

        if (context.hasShare && event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == 167) {
            return KeyEvent.KEYCODE_MEDIA_RECORD
        }

        if (context.vendorId == 0x054c &&
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT &&
            (event.scanCode == 317 || context.isDualShockStandaloneTouchpad)
        ) {
            return KeyEvent.KEYCODE_BUTTON_1
        }

        if (context.vendorId == 0x2dc8 && event.scanCode == 306) {
            return KeyEvent.KEYCODE_BUTTON_MODE
        }

        if ((context.vendorId == 0x057e && context.productId == 0x2009 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ||
            (context.vendorId == 0x0f0d && context.productId == 0x00c1)
        ) {
            when (event.scanCode) {
                0x130 -> return KeyEvent.KEYCODE_BUTTON_A
                0x131 -> return KeyEvent.KEYCODE_BUTTON_B
                0x132 -> return KeyEvent.KEYCODE_BUTTON_X
                0x133 -> return KeyEvent.KEYCODE_BUTTON_Y
                0x134 -> return KeyEvent.KEYCODE_BUTTON_L1
                0x135 -> return KeyEvent.KEYCODE_BUTTON_R1
                0x136 -> return KeyEvent.KEYCODE_BUTTON_L2
                0x137 -> return KeyEvent.KEYCODE_BUTTON_R2
                0x138 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                0x139 -> return KeyEvent.KEYCODE_BUTTON_START
                0x13A -> return KeyEvent.KEYCODE_BUTTON_THUMBL
                0x13B -> return KeyEvent.KEYCODE_BUTTON_THUMBR
                0x13D -> return KeyEvent.KEYCODE_BUTTON_MODE
            }
        }

        if (prefConfig.enableJoyConFix && context.vendorId == 0x057e && context.productId == 0x2006) {
            when (event.scanCode) {
                546 -> return KeyEvent.KEYCODE_DPAD_LEFT
                547 -> return KeyEvent.KEYCODE_DPAD_RIGHT
                544 -> return KeyEvent.KEYCODE_DPAD_UP
                545 -> return KeyEvent.KEYCODE_DPAD_DOWN
                309 -> return KeyEvent.KEYCODE_BUTTON_MODE
                310 -> return KeyEvent.KEYCODE_BUTTON_L1
                312 -> return KeyEvent.KEYCODE_BUTTON_L2
                314 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                317 -> return KeyEvent.KEYCODE_BUTTON_THUMBL
            }
        }

        if (prefConfig.enableJoyConFix && context.vendorId == 0x057e && context.productId == 0x2007) {
            when (event.scanCode) {
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_X
                304 -> return KeyEvent.KEYCODE_BUTTON_A
                305 -> return KeyEvent.KEYCODE_BUTTON_B
                311 -> return KeyEvent.KEYCODE_BUTTON_R1
                313 -> return KeyEvent.KEYCODE_BUTTON_R2
                315 -> return KeyEvent.KEYCODE_BUTTON_START
                316 -> return KeyEvent.KEYCODE_BUTTON_MODE
                318 -> return KeyEvent.KEYCODE_BUTTON_THUMBR
            }
        }

        if (context.usesLinuxGamepadStandardFaceButtons) {
            when (event.scanCode) {
                304 -> return KeyEvent.KEYCODE_BUTTON_A
                305 -> return KeyEvent.KEYCODE_BUTTON_B
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_X
            }
        }

        if (context.isNonStandardDualShock4) {
            return when (event.scanCode) {
                304 -> KeyEvent.KEYCODE_BUTTON_X
                305 -> KeyEvent.KEYCODE_BUTTON_A
                306 -> KeyEvent.KEYCODE_BUTTON_B
                307 -> KeyEvent.KEYCODE_BUTTON_Y
                308 -> KeyEvent.KEYCODE_BUTTON_L1
                309 -> KeyEvent.KEYCODE_BUTTON_R1
                312 -> KeyEvent.KEYCODE_BUTTON_SELECT
                313 -> KeyEvent.KEYCODE_BUTTON_START
                314 -> KeyEvent.KEYCODE_BUTTON_THUMBL
                315 -> KeyEvent.KEYCODE_BUTTON_THUMBR
                316 -> KeyEvent.KEYCODE_BUTTON_MODE
                else -> REMAP_CONSUME
            }
        } else if (context.isServal && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            when (event.scanCode) {
                314 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                315 -> return KeyEvent.KEYCODE_BUTTON_START
            }
        } else if (context.isNonStandardXboxBtController) {
            when (event.scanCode) {
                306 -> return KeyEvent.KEYCODE_BUTTON_X
                307 -> return KeyEvent.KEYCODE_BUTTON_Y
                308 -> return KeyEvent.KEYCODE_BUTTON_L1
                309 -> return KeyEvent.KEYCODE_BUTTON_R1
                310 -> return KeyEvent.KEYCODE_BUTTON_SELECT
                311 -> return KeyEvent.KEYCODE_BUTTON_START
                312 -> return KeyEvent.KEYCODE_BUTTON_THUMBL
                313 -> return KeyEvent.KEYCODE_BUTTON_THUMBR
                139 -> return KeyEvent.KEYCODE_BUTTON_MODE
            }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                return KeyEvent.KEYCODE_BUTTON_MODE
            }
        } else if (context.vendorId == 0x0b05 && (context.productId == 0x7900 || context.productId == 0x7902)) {
            when (event.scanCode) {
                264, 266 -> return KeyEvent.KEYCODE_BUTTON_START
                265, 267 -> return KeyEvent.KEYCODE_BUTTON_SELECT
            }
        }

        if (context.hatXAxis == -1 && context.hatYAxis == -1 && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            when (event.scanCode) {
                704 -> return KeyEvent.KEYCODE_DPAD_LEFT
                705 -> return KeyEvent.KEYCODE_DPAD_RIGHT
                706 -> return KeyEvent.KEYCODE_DPAD_UP
                707 -> return KeyEvent.KEYCODE_DPAD_DOWN
            }
        }

        var keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            !event.hasNoModifiers() &&
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0
        ) {
            keyCode = KeyEvent.KEYCODE_BUTTON_B
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU) {
            context.backIsStart = false
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            context.modeIsSelect = false
        } else if (context.backIsStart && keyCode == KeyEvent.KEYCODE_BACK) {
            return KeyEvent.KEYCODE_BUTTON_START
        } else if (context.modeIsSelect && keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            return KeyEvent.KEYCODE_BUTTON_SELECT
        } else if (context.searchIsMode && keyCode == KeyEvent.KEYCODE_SEARCH) {
            return KeyEvent.KEYCODE_BUTTON_MODE
        }

        return keyCode
    }

    private fun populateCachedVector(x: Float, y: Float): Vector2d {
        inputVector.initialize(x, y)
        return inputVector
    }

    private fun handleAxisSet(
        context: InputDeviceContext,
        lsX: Float,
        lsY: Float,
        rsX: Float,
        rsY: Float,
        ltIn: Float,
        rtIn: Float,
        hatX: Float,
        hatY: Float
    ) {
        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            val leftStickVector = populateCachedVector(lsX, lsY)
            handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius)
            context.leftStickX = (leftStickVector.x * 0x7FFE).toInt().toShort()
            context.leftStickY = (-leftStickVector.y * 0x7FFE).toInt().toShort()
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            val rightStickVector = populateCachedVector(rsX, rsY)
            handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius)
            context.rightStickX = (rightStickVector.x * 0x7FFE).toInt().toShort()
            context.rightStickY = (-rightStickVector.y * 0x7FFE).toInt().toShort()
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            var lt = ltIn
            var rt = rtIn
            if (lt != 0f) context.leftTriggerAxisUsed = true
            if (rt != 0f) context.rightTriggerAxisUsed = true

            if (context.triggersIdleNegative) {
                if (context.leftTriggerAxisUsed) lt = (lt + 1f) / 2f
                if (context.rightTriggerAxisUsed) rt = (rt + 1f) / 2f
            }

            if (lt <= context.triggerDeadzone) lt = 0f
            if (rt <= context.triggerDeadzone) rt = 0f

            context.leftTrigger = (lt * 0xFF).toInt().toByte()
            context.rightTrigger = (rt * 0xFF).toInt().toByte()
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            context.inputMap = context.inputMap and (ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            if (hatX < -0.5f) {
                context.inputMap = context.inputMap or ControllerPacket.LEFT_FLAG
                context.hatXAxisUsed = true
            } else if (hatX > 0.5f) {
                context.inputMap = context.inputMap or ControllerPacket.RIGHT_FLAG
                context.hatXAxisUsed = true
            }

            context.inputMap = context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG).inv()
            if (hatY < -0.5f) {
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG
                context.hatYAxisUsed = true
            } else if (hatY > 0.5f) {
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG
                context.hatYAxisUsed = true
            }
        }

        sendControllerInputPacket(context)
    }

    private fun sendTouchpadEventForPointer(
        context: InputDeviceContext,
        event: MotionEvent,
        touchType: Byte,
        pointerIndex: Int
    ): Boolean {
        val xRange = context.touchpadXRange ?: return false
        val yRange = context.touchpadYRange ?: return false
        val normalizedX = normalizeRawValueWithRange(event.getX(pointerIndex), xRange)
        val normalizedY = normalizeRawValueWithRange(event.getY(pointerIndex), yRange)
        val normalizedPressure = context.touchpadPressureRange?.let {
            normalizeRawValueWithRange(event.getPressure(pointerIndex), it)
        } ?: 0f

        return conn.sendControllerTouchEvent(
            context.controllerNumber.toByte(),
            touchType,
            event.getPointerId(pointerIndex),
            normalizedX,
            normalizedY,
            normalizedPressure
        ) != VibemisBridge.VB_ERR_UNSUPPORTED
    }

    fun tryHandleTouchpadEvent(event: MotionEvent): Boolean {
        if (event.source != InputDevice.SOURCE_TOUCHPAD && event.source != InputDevice.SOURCE_MOUSE) {
            return false
        }

        val context = inputDeviceContexts.get(event.deviceId) ?: return false

        if (event.source == InputDevice.SOURCE_MOUSE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                    sendControllerInputPacket(context)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                    sendControllerInputPacket(context)
                }
            }
            return !prefConfig.gamepadTouchpadAsMouse
        }

        val touchType: Byte = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> VibemisBridge.VB_TOUCH_EVENT_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    VibemisBridge.VB_TOUCH_EVENT_CANCEL
                } else {
                    VibemisBridge.VB_TOUCH_EVENT_UP
                }
            }
            MotionEvent.ACTION_MOVE -> VibemisBridge.VB_TOUCH_EVENT_MOVE
            MotionEvent.ACTION_CANCEL -> VibemisBridge.VB_TOUCH_EVENT_CANCEL_ALL
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                    sendControllerInputPacket(context)
                    return !prefConfig.gamepadTouchpadAsMouse
                }
                return false
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                    sendControllerInputPacket(context)
                    return !prefConfig.gamepadTouchpadAsMouse
                }
                return false
            }
            else -> return false
        }

        if (prefConfig.gamepadTouchpadAsMouse) return false
        if (context.touchpadXRange == null || context.touchpadYRange == null) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (!sendTouchpadEventForPointer(context, event, touchType, i)) {
                        return false
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                conn.sendControllerTouchEvent(
                    context.controllerNumber.toByte(),
                    VibemisBridge.VB_TOUCH_EVENT_CANCEL_ALL,
                    0,
                    0f,
                    0f,
                    0f
                ) != VibemisBridge.VB_ERR_UNSUPPORTED
            }
            else -> sendTouchpadEventForPointer(context, event, touchType, event.actionIndex)
        }
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val context = getContextForEvent(event) ?: return true

        var lsX = 0f
        var lsY = 0f
        var rsX = 0f
        var rsY = 0f
        var rt = 0f
        var lt = 0f
        var hatX = 0f
        var hatY = 0f

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            lsX = event.getAxisValue(context.leftStickXAxis)
            lsY = event.getAxisValue(context.leftStickYAxis)
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            rsX = event.getAxisValue(context.rightStickXAxis)
            rsY = event.getAxisValue(context.rightStickYAxis)
        }
        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            lt = event.getAxisValue(context.leftTriggerAxis)
            rt = event.getAxisValue(context.rightTriggerAxis)
        }
        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        }

        handleAxisSet(context, lsX, lsY, rsX, rsY, lt, rt, hatX, hatY)
        return true
    }

    private fun sendEmulatedMouseMove(
        x: Short,
        y: Short,
        mouseEmulationXDown: Boolean,
        mouseEmulationPixelMultiplier: Int
    ) {
        val vector = convertRawStickAxisToPixelMovement(x, y)
        if (vector.magnitude >= 1) {
            val dx: Short
            val dy: Short

            if (mouseEmulationXDown) {
                val rawDx = Integer.signum(vector.x.toInt()) * mouseEmulationPixelMultiplier
                val rawDy = Integer.signum((-vector.y).toInt()) * mouseEmulationPixelMultiplier
                dx = applyMouseSpeedScale(rawDx).toShort()
                dy = applyMouseSpeedScale(rawDy).toShort()
            } else {
                val rawDx = vector.x.toInt()
                val rawDy = (-vector.y).toInt()
                dx = applyMouseSpeedScale(rawDx).toShort()
                dy = applyMouseSpeedScale(rawDy).toShort()
            }

            inputSender?.post { conn.sendMouseMove(dx, dy) } ?: conn.sendMouseMove(dx, dy)
        }
    }

    private fun sendEmulatedMouseScroll(x: Short, y: Short, fastScroll: Boolean) {
        val vector = convertRawStickAxisToPixelMovement(x, y)
        if (vector.magnitude >= 1) {
            var rawVY = vector.y.toInt()
            var rawVX = vector.x.toInt()

            if (fastScroll) {
                rawVY *= MOUSE_SCROLL_FAST_MULTIPLIER
                rawVX *= MOUSE_SCROLL_FAST_MULTIPLIER
            }

            val vY = applyMouseSpeedScale(rawVY).toShort()
            val vX = applyMouseSpeedScale(rawVX).toShort()
            if (vY.toInt() == 0 && vX.toInt() == 0) return

            if (inputSender != null) {
                inputSender.post {
                    conn.sendMouseHighResScroll(vY)
                    conn.sendMouseHighResHScroll(vX)
                }
            } else {
                conn.sendMouseHighResScroll(vY)
                conn.sendMouseHighResHScroll(vX)
            }
        }
    }

    fun handleRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        if (stopped) return

        var foundMatchingDevice = false
        var vibrated = false

        for (i in 0 until inputDeviceContexts.size()) {
            val deviceContext = inputDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = true
                deviceContext.lowFreqMotor = lowFreqMotor
                deviceContext.highFreqMotor = highFreqMotor

                val vm = deviceContext.vibratorManager
                val vib = deviceContext.vibrator
                val inDev = deviceContext.inputDevice

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vm != null) {
                    vibrated = true
                    if (deviceContext.quadVibrators) {
                        rumbleQuadVibrators(
                            vm,
                            deviceContext.lowFreqMotor,
                            deviceContext.highFreqMotor,
                            deviceContext.leftTriggerMotor,
                            deviceContext.rightTriggerMotor
                        )
                    } else {
                        rumbleDualVibrators(vm, deviceContext.lowFreqMotor, deviceContext.highFreqMotor)
                    }
                } else if (vib != null) {
                    vibrated = true
                    rumbleSingleVibrator(vib, deviceContext.lowFreqMotor, deviceContext.highFreqMotor)
                }
            }
        }

        for (i in 0 until usbDeviceContexts.size()) {
            val deviceContext = usbDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = true
                vibrated = true
                deviceContext.device?.rumble(lowFreqMotor, highFreqMotor)
            }
        }

        if (controllerNumber.toInt() == 0) {
            if (!foundMatchingDevice && prefConfig.onscreenController && !prefConfig.onlyL3R3 && prefConfig.vibrateOsc) {
                rumbleSingleVibrator(deviceVibrator, lowFreqMotor, highFreqMotor)
            } else if (foundMatchingDevice && !vibrated && prefConfig.vibrateFallbackToDevice) {
                val lowFreqMotorAdjusted = min(
                    ((lowFreqMotor.toInt() and 0xffff) * prefConfig.vibrateFallbackToDeviceStrength) / 100,
                    Short.MAX_VALUE.toInt() * 2
                ).toShort()
                val highFreqMotorAdjusted = min(
                    ((highFreqMotor.toInt() and 0xffff) * prefConfig.vibrateFallbackToDeviceStrength) / 100,
                    Short.MAX_VALUE.toInt() * 2
                ).toShort()
                rumbleSingleVibrator(deviceVibrator, lowFreqMotorAdjusted, highFreqMotorAdjusted)
            }
        }
    }

    fun handleRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        if (stopped) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until inputDeviceContexts.size()) {
                val deviceContext = inputDeviceContexts.valueAt(i)
                if (deviceContext.controllerNumber == controllerNumber) {
                    deviceContext.leftTriggerMotor = leftTrigger
                    deviceContext.rightTriggerMotor = rightTrigger
                    val vm = deviceContext.vibratorManager
                    if (deviceContext.quadVibrators && vm != null) {
                        rumbleQuadVibrators(
                            vm,
                            deviceContext.lowFreqMotor,
                            deviceContext.highFreqMotor,
                            deviceContext.leftTriggerMotor,
                            deviceContext.rightTriggerMotor
                        )
                    }
                }
            }
        }

        for (i in 0 until usbDeviceContexts.size()) {
            val deviceContext = usbDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                deviceContext.device?.rumbleTriggers(leftTrigger, rightTrigger)
            }
        }
    }

    private fun createSensorListener(
        controllerNumber: Short,
        motionType: Byte,
        needsDeviceOrientationCorrection: Boolean
    ): SensorEventListener {
        return object : SensorEventListener {
            private val lastValues = FloatArray(3)

            override fun onSensorChanged(sensorEvent: SensorEvent) {
                if (sensorEvent.values[0] == lastValues[0] &&
                    sensorEvent.values[1] == lastValues[1] &&
                    sensorEvent.values[2] == lastValues[2]
                ) {
                    return
                }
                lastValues[0] = sensorEvent.values[0]
                lastValues[1] = sensorEvent.values[1]
                lastValues[2] = sensorEvent.values[2]

                var x = 0
                var y = 1
                var z = 2
                var xFactor = 1
                val yFactor = 1
                var zFactor = 1

                if (needsDeviceOrientationCorrection) {
                    @Suppress("DEPRECATION")
                    val deviceRotation = activityContext.windowManager.defaultDisplay.rotation
                    when (deviceRotation) {
                        Surface.ROTATION_0, Surface.ROTATION_180 -> {
                            x = 0; y = 2; z = 1
                        }
                        Surface.ROTATION_90, Surface.ROTATION_270 -> {
                            x = 1; y = 2; z = 0
                        }
                    }
                    when (deviceRotation) {
                        Surface.ROTATION_0 -> zFactor = -1
                        Surface.ROTATION_90 -> {
                            xFactor = -1; zFactor = -1
                        }
                        Surface.ROTATION_180 -> xFactor = -1
                    }
                }

                if (motionType == VibemisBridge.VB_MOTION_TYPE_GYRO) {
                    conn.sendControllerMotionEvent(
                        controllerNumber.toByte(),
                        motionType,
                        sensorEvent.values[x] * xFactor * 57.2957795f,
                        sensorEvent.values[y] * yFactor * 57.2957795f,
                        sensorEvent.values[z] * zFactor * 57.2957795f
                    )
                } else {
                    conn.sendControllerMotionEvent(
                        controllerNumber.toByte(),
                        motionType,
                        sensorEvent.values[x] * xFactor,
                        sensorEvent.values[y] * yFactor,
                        sensorEvent.values[z] * zFactor
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun handleSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHzIn: Short) {
        if (stopped) return
        val reportRateHz = min(200, reportRateHzIn.toInt()).toShort()

        val total = inputDeviceContexts.size() + usbDeviceContexts.size()
        for (i in 0 until total) {
            val deviceContext: InputDeviceContext = if (i < inputDeviceContexts.size()) {
                inputDeviceContexts.valueAt(i)
            } else {
                usbDeviceContexts.valueAt(i - inputDeviceContexts.size())
            }

            if (deviceContext.controllerNumber == controllerNumber) {
                when (motionType) {
                    VibemisBridge.VB_MOTION_TYPE_ACCEL -> deviceContext.accelReportRateHz = reportRateHz
                    VibemisBridge.VB_MOTION_TYPE_GYRO -> deviceContext.gyroReportRateHz = reportRateHz
                }

                backgroundThreadHandler.removeCallbacks(deviceContext.enableSensorRunnable)
                val sm = deviceContext.sensorManager ?: continue

                when (motionType) {
                    VibemisBridge.VB_MOTION_TYPE_ACCEL -> {
                        deviceContext.accelListener?.let {
                            sm.unregisterListener(it)
                            deviceContext.accelListener = null
                        }
                        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        if (reportRateHz.toInt() != 0 && accelSensor != null) {
                            deviceContext.accelListener = createSensorListener(controllerNumber, motionType, sm === deviceSensorManager)
                            sm.registerListener(deviceContext.accelListener, accelSensor, 1000000 / reportRateHz.toInt())
                        }
                    }
                    VibemisBridge.VB_MOTION_TYPE_GYRO -> {
                        deviceContext.gyroListener?.let {
                            sm.unregisterListener(it)
                            deviceContext.gyroListener = null
                        }
                        val gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                        if (reportRateHz.toInt() != 0 && gyroSensor != null) {
                            deviceContext.gyroListener = createSensorListener(controllerNumber, motionType, sm === deviceSensorManager)
                            sm.registerListener(deviceContext.gyroListener, gyroSensor, 1000000 / reportRateHz.toInt())
                        }
                    }
                }
                break
            }
        }
    }

    fun handleSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        if (stopped) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until inputDeviceContexts.size()) {
                val deviceContext = inputDeviceContexts.valueAt(i)
                val inDev = deviceContext.inputDevice ?: continue

                if (deviceContext.controllerNumber == controllerNumber && deviceContext.hasRgbLed) {
                    if (deviceContext.lightsSession == null) {
                        deviceContext.lightsSession = inDev.lightsManager.openSession()
                    }

                    val argbValue = -0x1000000 or ((r.toInt() shl 16) and 0xFF0000) or ((g.toInt() shl 8) and 0xFF00) or (b.toInt() and 0xFF)
                    val lightState = LightState.Builder().setColor(argbValue).build()

                    val lightsRequestBuilder = LightsRequest.Builder()
                    for (light: Light in inDev.lightsManager.lights) {
                        if (light.hasRgbControl()) {
                            lightsRequestBuilder.addLight(light, lightState)
                        }
                    }

                    deviceContext.lightsSession?.requestLights(lightsRequestBuilder.build())
                }
            }
        }
    }

    fun handleButtonUp(event: KeyEvent): Boolean {
        val context = getContextForEvent(event) ?: return true

        var keyCode = handleRemapping(context, event)
        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        val buttonDownTime = (event.eventTime - event.downTime).toInt()
        if (buttonDownTime < MINIMUM_BUTTON_DOWN_TIME_MS) {
            try {
                Thread.sleep((MINIMUM_BUTTON_DOWN_TIME_MS - buttonDownTime).toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                context.startUpTime = event.eventTime
                if ((context.inputMap and ControllerPacket.PLAY_FLAG) != 0 &&
                    context.startUpTime - context.startDownTime > START_DOWN_TIME_MOUSE_MODE_MS
                ) {
                    if (prefConfig.enableBackMenu && context.backMenuPending) {
                        context.backMenuPending = false
                        gestures.showGameMenu(context)
                    } else if (prefConfig.mouseEmulation) {
                        context.toggleMouseEmulation()
                    }
                }
                context.inputMap = context.inputMap and ControllerPacket.PLAY_FLAG.inv()
            }
            KeyEvent.KEYCODE_BACK -> {
                if (prefConfig.backAsGuide) {
                    context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
                } else {
                    context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
                }
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.LEFT_FLAG.inv()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.RIGHT_FLAG.inv()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.UP_FLAG.inv()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and ControllerPacket.DOWN_FLAG.inv()
            }
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG).inv()
            }
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and (ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            }
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and (ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG).inv()
            }
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap and (ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG).inv()
            }
            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap = context.inputMap and ControllerPacket.B_FLAG.inv()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> context.inputMap = context.inputMap and ControllerPacket.A_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap = context.inputMap and ControllerPacket.X_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap = context.inputMap and ControllerPacket.Y_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                context.inputMap = context.inputMap and ControllerPacket.LB_FLAG.inv()
                context.lastLbUpTime = event.eventTime
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                context.inputMap = context.inputMap and ControllerPacket.RB_FLAG.inv()
                context.lastRbUpTime = event.eventTime
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.inputMap = context.inputMap and ControllerPacket.LS_CLK_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.inputMap = context.inputMap and ControllerPacket.RS_CLK_FLAG.inv()
            KeyEvent.KEYCODE_MEDIA_RECORD -> context.inputMap = context.inputMap and ControllerPacket.MISC_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_1 -> context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.leftTriggerAxisUsed) return true
                context.leftTrigger = 0
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.rightTriggerAxisUsed) return true
                context.rightTrigger = 0
            }
            KeyEvent.KEYCODE_UNKNOWN -> {
                if (context.hasPaddles) {
                    when (event.scanCode) {
                        0x2c4 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE1_FLAG.inv()
                        0x2c5 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE2_FLAG.inv()
                        0x2c6 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE3_FLAG.inv()
                        0x2c7 -> context.inputMap = context.inputMap and ControllerPacket.PADDLE4_FLAG.inv()
                        else -> return false
                    }
                } else {
                    return false
                }
            }
            else -> return false
        }

        if ((context.emulatingButtonFlags and EMULATING_SELECT) != 0) {
            if ((context.inputMap and ControllerPacket.PLAY_FLAG) == 0 ||
                (context.inputMap and ControllerPacket.LB_FLAG) == 0
            ) {
                context.inputMap = context.inputMap and ControllerPacket.BACK_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_SELECT.inv()
            }
        }

        if ((context.emulatingButtonFlags and EMULATING_SPECIAL) != 0) {
            if ((context.inputMap and ControllerPacket.PLAY_FLAG) == 0 ||
                ((context.inputMap and ControllerPacket.BACK_FLAG) == 0 &&
                    (context.inputMap and ControllerPacket.RB_FLAG) == 0)
            ) {
                context.inputMap = context.inputMap and ControllerPacket.SPECIAL_BUTTON_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_SPECIAL.inv()
            }
        }

        if ((context.emulatingButtonFlags and EMULATING_TOUCHPAD) != 0) {
            if ((context.inputMap and ControllerPacket.BACK_FLAG) == 0 ||
                (context.inputMap and ControllerPacket.LB_FLAG) == 0
            ) {
                context.inputMap = context.inputMap and ControllerPacket.TOUCHPAD_FLAG.inv()
                context.emulatingButtonFlags = context.emulatingButtonFlags and EMULATING_TOUCHPAD.inv()
            }
        }

        sendControllerInputPacket(context)

        if (context.pendingExit && context.inputMap == 0) {
            activityContext.finish()
        }

        return true
    }

    fun handleButtonDown(event: KeyEvent): Boolean {
        val context = getContextForEvent(event) ?: return true

        var keyCode = handleRemapping(context, event)
        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                context.hasMode = true
                context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) {
                    context.startDownTime = event.eventTime
                    context.backMenuPending = (context.startDownTime - context.startUpTime) <= QUICK_MENU_FIRST_STAGE_MS
                }
                context.inputMap = context.inputMap or ControllerPacket.PLAY_FLAG
            }
            KeyEvent.KEYCODE_BACK -> {
                context.hasSelect = true
                if (prefConfig.backAsGuide) {
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                } else {
                    context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
                }
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                context.hasSelect = true
                context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.LEFT_FLAG
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.hatXAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.RIGHT_FLAG
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG
            }
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG
            }
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG
            }
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG
            }
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.hatXAxisUsed && context.hatYAxisUsed) return true
                context.inputMap = context.inputMap or ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG
            }
            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap = context.inputMap or ControllerPacket.B_FLAG
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> context.inputMap = context.inputMap or ControllerPacket.A_FLAG
            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap = context.inputMap or ControllerPacket.X_FLAG
            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap = context.inputMap or ControllerPacket.Y_FLAG
            KeyEvent.KEYCODE_BUTTON_L1 -> context.inputMap = context.inputMap or ControllerPacket.LB_FLAG
            KeyEvent.KEYCODE_BUTTON_R1 -> context.inputMap = context.inputMap or ControllerPacket.RB_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.inputMap = context.inputMap or ControllerPacket.LS_CLK_FLAG
            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.inputMap = context.inputMap or ControllerPacket.RS_CLK_FLAG
            KeyEvent.KEYCODE_MEDIA_RECORD -> context.inputMap = context.inputMap or ControllerPacket.MISC_FLAG
            KeyEvent.KEYCODE_BUTTON_1 -> context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.leftTriggerAxisUsed) return true
                context.leftTrigger = 0xFF.toByte()
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.rightTriggerAxisUsed) return true
                context.rightTrigger = 0xFF.toByte()
            }
            KeyEvent.KEYCODE_UNKNOWN -> {
                if (context.hasPaddles) {
                    when (event.scanCode) {
                        0x2c4 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE1_FLAG
                        0x2c5 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE2_FLAG
                        0x2c6 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE3_FLAG
                        0x2c7 -> context.inputMap = context.inputMap or ControllerPacket.PADDLE4_FLAG
                        else -> return false
                    }
                } else {
                    return false
                }
            }
            else -> return false
        }

        if (context.inputMap == (ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or
                ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG)
        ) {
            context.pendingExit = true
        }

        if (!context.hasSelect) {
            if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG) ||
                (context.inputMap == ControllerPacket.PLAY_FLAG &&
                    event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
            ) {
                context.inputMap = context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG).inv()
                context.inputMap = context.inputMap or ControllerPacket.BACK_FLAG
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SELECT
            }
        } else if (context.needsClickpadEmulation) {
            if (context.inputMap == (ControllerPacket.BACK_FLAG or ControllerPacket.LB_FLAG) ||
                (context.inputMap == ControllerPacket.BACK_FLAG &&
                    event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
            ) {
                context.inputMap = context.inputMap and (ControllerPacket.BACK_FLAG or ControllerPacket.LB_FLAG).inv()
                context.inputMap = context.inputMap or ControllerPacket.TOUCHPAD_FLAG
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_TOUCHPAD
            }
        }

        if (!context.hasMode) {
            if (context.hasSelect) {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG)) {
                    context.inputMap = context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG).inv()
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            } else {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG) ||
                    (context.inputMap == ControllerPacket.PLAY_FLAG &&
                        event.eventTime - context.lastRbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS)
                ) {
                    context.inputMap = context.inputMap and (ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG).inv()
                    context.inputMap = context.inputMap or ControllerPacket.SPECIAL_BUTTON_FLAG
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            }
        }

        sendControllerInputPacket(context)
        return true
    }

    fun reportOscState(
        buttonFlags: Int,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short,
        leftTrigger: Byte,
        rightTrigger: Byte
    ) {
        defaultContext.leftStickX = leftStickX
        defaultContext.leftStickY = leftStickY
        defaultContext.rightStickX = rightStickX
        defaultContext.rightStickY = rightStickY
        defaultContext.leftTrigger = leftTrigger
        defaultContext.rightTrigger = rightTrigger
        defaultContext.inputMap = buttonFlags
        sendControllerInputPacket(defaultContext)
    }

    override fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float
    ) {
        val context = usbDeviceContexts.get(controllerId) ?: return

        val leftStickVector = populateCachedVector(leftStickX, leftStickY)
        handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius)
        context.leftStickX = (leftStickVector.x * 0x7FFE).toInt().toShort()
        context.leftStickY = (-leftStickVector.y * 0x7FFE).toInt().toShort()

        val rightStickVector = populateCachedVector(rightStickX, rightStickY)
        handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius)
        context.rightStickX = (rightStickVector.x * 0x7FFE).toInt().toShort()
        context.rightStickY = (-rightStickVector.y * 0x7FFE).toInt().toShort()

        val lt = if (leftTrigger <= context.triggerDeadzone) 0f else leftTrigger
        val rt = if (rightTrigger <= context.triggerDeadzone) 0f else rightTrigger

        context.leftTrigger = (lt * 0xFF).toInt().toByte()
        context.rightTrigger = (rt * 0xFF).toInt().toByte()
        context.inputMap = buttonFlags

        sendControllerInputPacket(context)
    }

    override fun reportControllerMotion(controllerId: Int, motionType: Byte, motionX: Float, motionY: Float, motionZ: Float) {
        val context = usbDeviceContexts.get(controllerId) ?: return
        conn.sendControllerMotionEvent(context.controllerNumber.toByte(), motionType, motionX, motionY, motionZ)
    }

    override fun deviceRemoved(controller: AbstractController) {
        val context = usbDeviceContexts.get(controller.controllerId)
        if (context != null) {
            VibemisLog.info("Removed controller: ${controller.controllerId}")
            releaseControllerNumber(context)
            context.destroy()
            usbDeviceContexts.remove(controller.controllerId)
        }
    }

    override fun deviceAdded(controller: AbstractController) {
        if (stopped) return
        val context = createUsbDeviceContextForDevice(controller)
        usbDeviceContexts.put(controller.controllerId, context)
    }

    open inner class GenericControllerContext : GameInputDevice {
        @JvmField var id: Int = 0
        @JvmField var external: Boolean = false
        @JvmField var vendorId: Int = 0
        @JvmField var productId: Int = 0
        @JvmField var leftStickDeadzoneRadius: Float = 0f
        @JvmField var rightStickDeadzoneRadius: Float = 0f
        @JvmField var triggerDeadzone: Float = 0f
        @JvmField var assignedControllerNumber: Boolean = false
        @JvmField var reservedControllerNumber: Boolean = false
        @JvmField var controllerNumber: Short = 0

        @JvmField var inputMap: Int = 0
        @JvmField var leftTrigger: Byte = 0
        @JvmField var rightTrigger: Byte = 0
        @JvmField var rightStickX: Short = 0
        @JvmField var rightStickY: Short = 0
        @JvmField var leftStickX: Short = 0
        @JvmField var leftStickY: Short = 0

        @JvmField var mouseEmulationActive: Boolean = false
        @JvmField var mouseEmulationXDown: Boolean = false
        @JvmField var mouseEmulationPixelMultiplier: Int = 1
        @JvmField var mouseEmulationLastInputMap: Int = 0
        val mouseEmulationReportPeriod: Long = 10L

        val mouseEmulationRunnable: Runnable = object : Runnable {
            override fun run() {
                if (!mouseEmulationActive) return

                val fastScroll = (leftTrigger.toInt() and 0xFF) > 0
                when (prefConfig.analogStickForScrolling) {
                    PreferenceConfiguration.AnalogStickForScrolling.RIGHT -> {
                        sendEmulatedMouseMove(leftStickX, leftStickY, mouseEmulationXDown, mouseEmulationPixelMultiplier)
                        sendEmulatedMouseScroll(rightStickX, rightStickY, fastScroll)
                    }
                    PreferenceConfiguration.AnalogStickForScrolling.LEFT -> {
                        sendEmulatedMouseMove(rightStickX, rightStickY, mouseEmulationXDown, mouseEmulationPixelMultiplier)
                        sendEmulatedMouseScroll(leftStickX, leftStickY, fastScroll)
                    }
                    else -> {
                        sendEmulatedMouseMove(leftStickX, leftStickY, mouseEmulationXDown, mouseEmulationPixelMultiplier)
                        sendEmulatedMouseMove(rightStickX, rightStickY, mouseEmulationXDown, mouseEmulationPixelMultiplier)
                    }
                }
                mainThreadHandler.postDelayed(this, mouseEmulationReportPeriod)
            }
        }

        override fun getGameMenuOptions(): List<GameMenu.MenuOption> {
            return listOf(
                GameMenu.MenuOption(
                    activityContext.getString(
                        if (mouseEmulationActive) R.string.game_menu_toggle_mouse_off else R.string.game_menu_toggle_mouse_on
                    ),
                    true,
                    Runnable { toggleMouseEmulation() }
                )
            )
        }

        fun toggleMouseEmulation() {
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable)
            mouseEmulationActive = !mouseEmulationActive
            (activityContext as? Game)?.showTransientOverlayMessage("Mouse emulation is: ${if (mouseEmulationActive) "ON" else "OFF"}")
            if (mouseEmulationActive) {
                mainThreadHandler.postDelayed(mouseEmulationRunnable, mouseEmulationReportPeriod)
            }
        }

        open fun destroy() {
            mouseEmulationActive = false
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable)
        }

        open fun sendControllerArrival() {}
    }

    open inner class InputDeviceContext : GenericControllerContext() {
        @JvmField var name: String? = null
        @JvmField var vibratorManager: VibratorManager? = null
        @JvmField var vibrator: Vibrator? = null
        @JvmField var quadVibrators: Boolean = false
        @JvmField var lowFreqMotor: Short = 0
        @JvmField var highFreqMotor: Short = 0
        @JvmField var leftTriggerMotor: Short = 0
        @JvmField var rightTriggerMotor: Short = 0

        @JvmField var sensorManager: SensorManager? = null
        @JvmField var gyroListener: SensorEventListener? = null
        @JvmField var gyroReportRateHz: Short = 0
        @JvmField var accelListener: SensorEventListener? = null
        @JvmField var accelReportRateHz: Short = 0

        @JvmField var inputDevice: InputDevice? = null
        @JvmField var hasRgbLed: Boolean = false
        @JvmField var lightsSession: LightsManager.LightsSession? = null

        @JvmField var lastReportedBatteryStatus: Int = 0
        @JvmField var lastReportedBatteryCapacity: Float = 0f

        @JvmField var leftStickXAxis: Int = -1
        @JvmField var leftStickYAxis: Int = -1
        @JvmField var rightStickXAxis: Int = -1
        @JvmField var rightStickYAxis: Int = -1
        @JvmField var leftTriggerAxis: Int = -1
        @JvmField var rightTriggerAxis: Int = -1
        @JvmField var triggersIdleNegative: Boolean = false
        @JvmField var leftTriggerAxisUsed: Boolean = false
        @JvmField var rightTriggerAxisUsed: Boolean = false

        @JvmField var hatXAxis: Int = -1
        @JvmField var hatYAxis: Int = -1
        @JvmField var hatXAxisUsed: Boolean = false
        @JvmField var hatYAxisUsed: Boolean = false

        @JvmField var touchpadXRange: InputDevice.MotionRange? = null
        @JvmField var touchpadYRange: InputDevice.MotionRange? = null
        @JvmField var touchpadPressureRange: InputDevice.MotionRange? = null

        @JvmField var isNonStandardDualShock4: Boolean = false
        @JvmField var usesLinuxGamepadStandardFaceButtons: Boolean = false
        @JvmField var isNonStandardXboxBtController: Boolean = false
        @JvmField var isServal: Boolean = false
        @JvmField var backIsStart: Boolean = false
        @JvmField var modeIsSelect: Boolean = false
        @JvmField var searchIsMode: Boolean = false
        @JvmField var ignoreBack: Boolean = false
        @JvmField var hasJoystickAxes: Boolean = false
        @JvmField var pendingExit: Boolean = false
        @JvmField var isDualShockStandaloneTouchpad: Boolean = false

        @JvmField var emulatingButtonFlags: Int = 0
        @JvmField var hasSelect: Boolean = false
        @JvmField var hasMode: Boolean = false
        @JvmField var hasPaddles: Boolean = false
        @JvmField var hasShare: Boolean = false
        @JvmField var needsClickpadEmulation: Boolean = false

        @JvmField var lastLbUpTime: Long = 0
        @JvmField var lastRbUpTime: Long = 0
        @JvmField var startDownTime: Long = 0
        @JvmField var startUpTime: Long = 0
        @JvmField var backMenuPending: Boolean = false

        val batteryStateUpdateRunnable: Runnable = object : Runnable {
            override fun run() {
                sendControllerBatteryPacket(this@InputDeviceContext)
                backgroundThreadHandler.postDelayed(this, BATTERY_RECHECK_INTERVAL_MS.toLong())
            }
        }

        val enableSensorRunnable: Runnable = Runnable {
            if (accelReportRateHz.toInt() != 0 && accelListener == null) {
                handleSetMotionEventState(controllerNumber, VibemisBridge.VB_MOTION_TYPE_ACCEL, accelReportRateHz)
            }
            if (gyroReportRateHz.toInt() != 0 && gyroListener == null) {
                handleSetMotionEventState(controllerNumber, VibemisBridge.VB_MOTION_TYPE_GYRO, gyroReportRateHz)
            }
        }

        override fun destroy() {
            super.destroy()

            val vm = vibratorManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vm != null) {
                vm.cancel()
            } else {
                vibrator?.cancel()
            }

            backgroundThreadHandler.removeCallbacks(enableSensorRunnable)

            gyroListener?.let { sensorManager?.unregisterListener(it) }
            accelListener?.let { sensorManager?.unregisterListener(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lightsSession?.close()
            }

            backgroundThreadHandler.removeCallbacks(batteryStateUpdateRunnable)
        }

        override fun sendControllerArrival() {
            val inDev = inputDevice ?: return
            val type: Byte = when (inDev.vendorId) {
                0x045e -> VibemisBridge.VB_CTYPE_XBOX
                0x054c -> VibemisBridge.VB_CTYPE_PS
                0x057e -> VibemisBridge.VB_CTYPE_NINTENDO
                else -> VibemisBridge.guessControllerType(inDev.vendorId, inDev.productId)
            }

            var supportedButtonFlags = 0
            for ((keyCode, flag) in ANDROID_TO_LI_BUTTON_MAP) {
                if (inDev.hasKeys(keyCode)[0]) {
                    supportedButtonFlags = supportedButtonFlags or flag
                }
            }

            if (hasPaddles) {
                supportedButtonFlags = supportedButtonFlags or
                    ControllerPacket.PADDLE1_FLAG or
                    ControllerPacket.PADDLE2_FLAG or
                    ControllerPacket.PADDLE3_FLAG or
                    ControllerPacket.PADDLE4_FLAG
            }
            if (hasShare) {
                supportedButtonFlags = supportedButtonFlags or ControllerPacket.MISC_FLAG
            }
            if (getMotionRangeForJoystickAxis(inDev, MotionEvent.AXIS_HAT_X) != null) {
                supportedButtonFlags = supportedButtonFlags or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG
            }
            if (getMotionRangeForJoystickAxis(inDev, MotionEvent.AXIS_HAT_Y) != null) {
                supportedButtonFlags = supportedButtonFlags or ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG
            }

            var capabilities = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (quadVibrators) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_RUMBLE.toInt() or VibemisBridge.VB_CCAP_TRIGGER_RUMBLE.toInt()
                } else if (vibratorManager != null || vibrator != null) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_RUMBLE.toInt()
                }
                if (external) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_BATTERY_STATE.toInt()
                }
                if (hasRgbLed && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || type == VibemisBridge.VB_CTYPE_PS)) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_RGB_LED.toInt()
                }
            }

            if (leftTriggerAxis != -1 || rightTriggerAxis != -1) {
                capabilities = capabilities or VibemisBridge.VB_CCAP_ANALOG_TRIGGERS.toInt()
            }

            val sm = sensorManager
            if (sm != null && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                capabilities = capabilities or VibemisBridge.VB_CCAP_ACCEL.toInt()
            }
            if (sm != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                capabilities = capabilities or VibemisBridge.VB_CCAP_GYRO.toInt()
            }

            val reportedType: Byte = if (type != VibemisBridge.VB_CTYPE_PS && sm != null) {
                (activityContext as? Game)?.showTransientOverlayMessage(activityContext.resources.getString(R.string.toast_controller_type_changed))
                needsClickpadEmulation = true
                VibemisBridge.VB_CTYPE_UNKNOWN
            } else {
                type
            }

            if (vibrator != null) {
                capabilities = capabilities or VibemisBridge.VB_CCAP_RUMBLE.toInt()
            }

            if ((inDev.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                capabilities = capabilities or VibemisBridge.VB_CCAP_TOUCHPAD.toInt()
                if (hasButtonUnderTouchpad(inDev, type)) {
                    supportedButtonFlags = supportedButtonFlags or ControllerPacket.TOUCHPAD_FLAG
                }
            }

            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(),
                getActiveControllerMask(),
                reportedType,
                supportedButtonFlags,
                capabilities.toShort()
            )

            if (prefConfig.enableBatteryReport) {
                backgroundThreadHandler.post(batteryStateUpdateRunnable)
            }
        }

        fun migrateContext(oldContext: InputDeviceContext) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.lightsSession = oldContext.lightsSession
                oldContext.lightsSession = null
            }
            this.gyroReportRateHz = oldContext.gyroReportRateHz
            this.accelReportRateHz = oldContext.accelReportRateHz

            oldContext.destroy()

            this.assignedControllerNumber = oldContext.assignedControllerNumber
            this.reservedControllerNumber = oldContext.reservedControllerNumber
            this.controllerNumber = oldContext.controllerNumber

            if (oldContext.sensorManager === deviceSensorManager) {
                this.sensorManager = deviceSensorManager
            }

            this.needsClickpadEmulation = oldContext.needsClickpadEmulation
            enableSensors()
            backgroundThreadHandler.post(batteryStateUpdateRunnable)
        }

        fun disableSensors() {
            backgroundThreadHandler.removeCallbacks(enableSensorRunnable)
            gyroListener?.let {
                sensorManager?.unregisterListener(it)
                gyroListener = null
                conn.sendControllerMotionEvent(controllerNumber.toByte(), VibemisBridge.VB_MOTION_TYPE_GYRO, 0f, 0f, 0f)
            }
            accelListener?.let {
                sensorManager?.unregisterListener(it)
                accelListener = null
            }
        }

        fun enableSensors() {
            backgroundThreadHandler.postDelayed(enableSensorRunnable, 1000)
        }
    }

    inner class UsbDeviceContext : InputDeviceContext() {
        @JvmField var device: AbstractController? = null

        override fun sendControllerArrival() {
            val dev = device ?: return
            var type = dev.type
            var capabilities = dev.capabilities.toInt()

            val sm = sensorManager
            if (type != VibemisBridge.VB_CTYPE_PS && type != VibemisBridge.VB_CTYPE_NINTENDO && sm != null) {
                if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_GYRO.toInt()
                }
                if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                    capabilities = capabilities or VibemisBridge.VB_CCAP_ACCEL.toInt()
                }
                type = VibemisBridge.VB_CTYPE_UNKNOWN
            }

            if (type != VibemisBridge.VB_CTYPE_PS && (capabilities and (VibemisBridge.VB_CCAP_GYRO.toInt() or VibemisBridge.VB_CCAP_ACCEL.toInt())) != 0) {
                (activityContext as? Game)?.showTransientOverlayMessage(activityContext.resources.getString(R.string.toast_controller_type_changed))
            }

            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(),
                getActiveControllerMask(),
                type,
                dev.supportedButtonFlags,
                capabilities.toShort()
            )
        }
    }

    companion object {
        private const val MAXIMUM_BUMPER_UP_DELAY_MS = 100
        private const val START_DOWN_TIME_MOUSE_MODE_MS = 750
        private const val MINIMUM_BUTTON_DOWN_TIME_MS = 25
        private const val QUICK_MENU_FIRST_STAGE_MS = 200
        private const val EMULATING_SPECIAL = 0x1
        private const val EMULATING_SELECT = 0x2
        private const val EMULATING_TOUCHPAD = 0x4
        private const val MAX_GAMEPADS: Short = 16
        private const val BATTERY_RECHECK_INTERVAL_MS = 120 * 1000
        private const val MAX_CONTROLLERS = 4
        private const val AXIS_DELTA_MIN = 64
        private const val TRIGGER_DELTA_MIN = 2

        @JvmStatic
        fun isGameControllerDevice(device: InputDevice?): Boolean =
            ControllerRumbleMapper.isGameControllerDevice(device)

        @JvmStatic
        fun getAttachedControllerMask(context: Context): Short =
            ControllerRumbleMapper.getAttachedControllerMask(context)

        @JvmStatic
        fun getAttachedPlayStationControllerMask(context: Context): Short =
            ControllerRumbleMapper.getAttachedPlayStationControllerMask(context)
    }
}
