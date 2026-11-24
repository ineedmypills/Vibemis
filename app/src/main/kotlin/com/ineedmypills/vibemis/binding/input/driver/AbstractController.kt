package com.ineedmypills.vibemis.binding.input.driver

import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge

abstract class AbstractController(
    val controllerId: Int,
    private val listener: UsbDriverListener,
    val vendorId: Int,
    val productId: Int
) {
    @JvmField
    protected var buttonFlags: Int = 0
    var supportedButtonFlags: Int = 0
        protected set
    @JvmField
    protected var leftTrigger: Float = 0f
    @JvmField
    protected var rightTrigger: Float = 0f
    @JvmField
    protected var rightStickX: Float = 0f
    @JvmField
    protected var rightStickY: Float = 0f
    @JvmField
    protected var leftStickX: Float = 0f
    @JvmField
    protected var leftStickY: Float = 0f
    @JvmField
    protected var gyroX: Float = 0f
    @JvmField
    protected var gyroY: Float = 0f
    @JvmField
    protected var gyroZ: Float = 0f
    @JvmField
    protected var accelX: Float = 0f
    @JvmField
    protected var accelY: Float = 0f
    @JvmField
    protected var accelZ: Float = 0f
    var capabilities: Short = 0
        protected set
    var type: Byte = 0
        protected set

    protected fun setButtonFlag(buttonFlag: Int, data: Int) {
        if (data != 0) {
            buttonFlags = buttonFlags or buttonFlag
        } else {
            buttonFlags = buttonFlags and buttonFlag.inv()
        }
    }

    protected fun reportInput() {
        listener.reportControllerState(
            controllerId,
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger
        )
    }

    protected fun reportMotion() {
        listener.reportControllerMotion(controllerId, VibemisBridge.VB_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ)
        listener.reportControllerMotion(controllerId, VibemisBridge.VB_MOTION_TYPE_ACCEL, accelX, accelY, accelZ)
    }

    abstract fun start(): Boolean
    abstract fun stop()
    abstract fun rumble(lowFreqMotor: Short, highFreqMotor: Short)
    abstract fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short)

    protected fun notifyDeviceRemoved() {
        listener.deviceRemoved(this)
    }

    protected fun notifyDeviceAdded() {
        listener.deviceAdded(this)
    }
}
