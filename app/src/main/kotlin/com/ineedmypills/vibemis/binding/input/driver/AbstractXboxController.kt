package com.ineedmypills.vibemis.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.input.ControllerPacket
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class AbstractXboxController(
    @JvmField protected val device: UsbDevice,
    @JvmField protected val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private var inputThread: Thread? = null
    private var stopped = false

    @JvmField
    protected var inEndpt: UsbEndpoint? = null
    @JvmField
    protected var outEndpt: UsbEndpoint? = null

    init {
        this.type = VibemisBridge.VB_CTYPE_XBOX
        this.capabilities = (VibemisBridge.VB_CCAP_ANALOG_TRIGGERS.toInt() or VibemisBridge.VB_CCAP_RUMBLE.toInt()).toShort()
        this.buttonFlags =
            ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or
                ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or
                ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or
                ControllerPacket.LS_CLK_FLAG or ControllerPacket.RS_CLK_FLAG or
                ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG
    }

    private fun createInputThread(): Thread {
        return object : Thread() {
            override fun run() {
                try {
                    sleep(1000)
                } catch (_: InterruptedException) {
                    return
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded()

                while (!isInterrupted && !stopped) {
                    val buffer = ByteArray(64)
                    var res: Int

                    do {
                        val lastMillis = SystemClock.uptimeMillis()
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 3000)

                        if (res == 0) {
                            res = -1
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            VibemisLog.warning("Detected device I/O error")
                            this@AbstractXboxController.stop()
                            break
                        }
                    } while (res == -1 && !isInterrupted && !stopped)

                    if (res == -1 || stopped) {
                        break
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        reportInput()
                    }
                }
            }
        }
    }

    override fun start(): Boolean {
        // Force claim all interfaces
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                VibemisLog.warning("Failed to claim interfaces")
                return false
            }
        }

        // Find the endpoints
        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    VibemisLog.warning("Found duplicate IN endpoint")
                    return false
                }
                inEndpt = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    VibemisLog.warning("Found duplicate OUT endpoint")
                    return false
                }
                outEndpt = endpt
            }
        }

        if (inEndpt == null || outEndpt == null) {
            VibemisLog.warning("Missing required endpoint")
            return false
        }

        if (!doInit()) {
            return false
        }

        inputThread = createInputThread().apply { start() }
        return true
    }

    override fun stop() {
        if (stopped) {
            return
        }

        stopped = true

        // Cancel any rumble effects
        rumble(0, 0)

        inputThread?.interrupt()
        inputThread = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.releaseInterface(iface)) {
                VibemisLog.warning("Failed to release interfaces")
            }
        }

        connection.close()
        notifyDeviceRemoved()
    }

    protected abstract fun handleRead(buffer: ByteBuffer): Boolean
    protected abstract fun doInit(): Boolean
}
