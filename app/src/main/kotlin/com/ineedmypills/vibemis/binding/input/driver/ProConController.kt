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
import java.util.Locale

class ProConController(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private var inEndpt: UsbEndpoint? = null
    private var outEndpt: UsbEndpoint? = null
    private var inputThread: Thread? = null
    private var stopped = false
    private var sendPacketCount: Byte = 0
    private val stickCalibration = Array(2) { Array(2) { IntArray(3) } } // [stick][axis][min, center, max]
    private val stickExtends = Array(2) { Array(2) { FloatArray(2) } } // Pre-calculated scale for each axis

    init {
        this.type = VibemisBridge.VB_CTYPE_NINTENDO
        this.capabilities = (
            VibemisBridge.VB_CCAP_GYRO.toInt() or
                VibemisBridge.VB_CCAP_ACCEL.toInt() or
                VibemisBridge.VB_CCAP_RUMBLE.toInt()
            ).toShort()
    }

    private fun createInputThread(): Thread {
        return Thread {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return@Thread
            }

            val handshakeSuccess = handshake()

            if (!handshakeSuccess) {
                VibemisLog.info("ProCon: Initial handshake failed!")
                this@ProConController.stop()
                return@Thread
            }

            VibemisLog.info("ProCon: handshake $handshakeSuccess")
            VibemisLog.info("ProCon: highspeed ${highSpeed()}")
            VibemisLog.info("ProCon: handshake ${handshake()}")
            VibemisLog.info("ProCon: loadstickcalibration ${loadStickCalibration()}")
            VibemisLog.info("ProCon: enablevibration ${enableVibration(true)}")
            VibemisLog.info("ProCon: setinutreportmode ${setInputReportMode(0x30.toByte())}")
            VibemisLog.info("ProCon: forceusb ${forceUSB()}")
            VibemisLog.info("ProCon: setplayerled ${setPlayerLED(controllerId + 1)}")
            VibemisLog.info("ProCon: enableimu ${enableIMU(true)}")

            VibemisLog.info("ProCon: initialized!")

            notifyDeviceAdded()

            while (!Thread.currentThread().isInterrupted && !stopped) {
                val buffer = ByteArray(64)
                var res: Int
                do {
                    val lastMillis = SystemClock.uptimeMillis()
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 1000)
                    if (res == 0) {
                        res = -1
                    }
                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        VibemisLog.warning("Detected device I/O error")
                        this@ProConController.stop()
                        break
                    }
                } while (res == -1 && !Thread.currentThread().isInterrupted && !stopped)

                if (res == -1 || stopped) {
                    break
                }

                if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput()
                    reportMotion()
                }
            }
        }
    }

    private fun sendData(data: ByteArray, size: Int): Boolean {
        return connection.bulkTransfer(outEndpt, data, size, 100) == size
    }

    private fun sendCommand(id: Byte, waitReply: Boolean): Boolean {
        val data = byteArrayOf(0x80.toByte(), id)
        for (i in 0 until COMMAND_RETRIES) {
            if (!sendData(data, data.size)) {
                continue
            }
            if (!waitReply) {
                return true
            }

            val buffer = ByteArray(PACKET_SIZE)
            var res: Int
            var retries = 0
            do {
                res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 100)
                if (res > 0 && (buffer[0].toInt() and 0xFF) == 0x81 && (buffer[1].toInt() and 0xFF) == (id.toInt() and 0xFF)) {
                    return true
                }
                retries += 1
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted && !stopped)
        }

        return false
    }

    private fun sendSubcommand(subcommand: Byte, payload: ByteArray, buffer: ByteArray): Boolean {
        val data = ByteArray(11 + payload.size)
        data[0] = 0x01 // Rumble and subcommand
        data[1] = sendPacketCount++ // Counter (increments per call)
        if (sendPacketCount > 0xF) {
            sendPacketCount = 0
        }

        data[10] = subcommand
        System.arraycopy(payload, 0, data, 11, payload.size)

        for (i in 0 until COMMAND_RETRIES) {
            if (!sendData(data, data.size)) {
                continue
            }

            // Wait for response
            var res: Int
            var retries = 0
            do {
                res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 100)
                if (res < 0 || buffer[0] != 0x21.toByte() || buffer[14] != subcommand) {
                    retries += 1
                } else {
                    return true
                }
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted && !stopped)
            VibemisLog.warning(
                "ProCon: Failed to get subcmd reply: $res bytes received, " +
                    String.format(null as Locale?, "0x%02x, 0x%02x", buffer[0], buffer[14])
            )
            return false
        }

        return false
    }

    private fun handshake(): Boolean = sendCommand(0x02.toByte(), true)
    private fun highSpeed(): Boolean = sendCommand(0x03.toByte(), true)
    private fun forceUSB(): Boolean = sendCommand(0x04.toByte(), true)

    private fun setInputReportMode(mode: Byte): Boolean {
        return sendSubcommand(0x03.toByte(), byteArrayOf(mode), ByteArray(PACKET_SIZE))
    }

    private fun setPlayerLED(id: Int): Boolean {
        return sendSubcommand(0x30.toByte(), byteArrayOf((id and 0b1111).toByte()), ByteArray(PACKET_SIZE))
    }

    private fun enableIMU(enable: Boolean): Boolean {
        return sendSubcommand(0x40.toByte(), byteArrayOf(if (enable) 0x01 else 0x00), ByteArray(PACKET_SIZE))
    }

    private fun enableVibration(enable: Boolean): Boolean {
        return sendSubcommand(0x48.toByte(), byteArrayOf(if (enable) 0x01 else 0x00), ByteArray(PACKET_SIZE))
    }

    override fun start(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                VibemisLog.warning("Failed to claim interfaces")
                return false
            }
        }

        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_IN) {
                inEndpt = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                outEndpt = endpt
            }
        }

        if (inEndpt == null || outEndpt == null) {
            VibemisLog.warning("Missing required endpoint")
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
        rumble(0, 0)
        inputThread?.interrupt()
        inputThread = null

        connection.close()
        notifyDeviceRemoved()
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val data = ByteArray(10)
        data[0] = 0x10 // Rumble command
        data[1] = sendPacketCount++ // Counter (increments per call)
        if (sendPacketCount > 0xF) {
            sendPacketCount = 0
        }

        if (lowFreqMotor.toInt() != 0) {
            val lf = lowFreqMotor.toInt() and 0xFFFF
            val v4 = (0x50 - (lf and (0xFFFF shr 12))).toByte()
            data[4] = v4
            data[8] = v4
            val v5 = (((lf shr 8) / 5) + 0x40).toByte()
            data[5] = v5
            data[9] = v5
        }
        if (highFreqMotor.toInt() != 0) {
            val hf = highFreqMotor.toInt() and 0xFFFF
            data[6] = ((0x70 - (hf shr 10)) and -0x04).toByte()
            data[7] = ((hf shr 8) * 0xC8 / 0xFF).toByte()
        }

        data[2] = (data[2].toInt() or 0x00).toByte()
        data[3] = (data[3].toInt() or 0x01).toByte()
        data[5] = (data[5].toInt() or 0x40).toByte()
        data[6] = (data[6].toInt() or 0x00).toByte()
        data[7] = (data[7].toInt() or 0x01).toByte()
        data[9] = (data[9].toInt() or 0x40).toByte()

        sendData(data, data.size)
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // ProCon does not support trigger-specific rumble
    }

    protected fun handleRead(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < PACKET_SIZE) {
            return false
        }

        if (buffer.get(0) != 0x30.toByte()) {
            return false
        }

        buttonFlags = 0
        val b3 = buffer.get(3).toInt()
        val b4 = buffer.get(4).toInt()
        val b5 = buffer.get(5).toInt()

        // Nintendo layout is swapped
        setButtonFlag(ControllerPacket.B_FLAG, b3 and 0x08)
        setButtonFlag(ControllerPacket.A_FLAG, b3 and 0x04)
        setButtonFlag(ControllerPacket.Y_FLAG, b3 and 0x02)
        setButtonFlag(ControllerPacket.X_FLAG, b3 and 0x01)
        setButtonFlag(ControllerPacket.UP_FLAG, b5 and 0x02)
        setButtonFlag(ControllerPacket.DOWN_FLAG, b5 and 0x01)
        setButtonFlag(ControllerPacket.LEFT_FLAG, b5 and 0x08)
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b5 and 0x04)
        setButtonFlag(ControllerPacket.BACK_FLAG, b4 and 0x01)
        setButtonFlag(ControllerPacket.PLAY_FLAG, b4 and 0x02)
        setButtonFlag(ControllerPacket.MISC_FLAG, b4 and 0x20) // Screenshot
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b4 and 0x10) // Home
        setButtonFlag(ControllerPacket.LB_FLAG, b5 and 0x40)
        setButtonFlag(ControllerPacket.RB_FLAG, b3 and 0x40)
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b4 and 0x08)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b4 and 0x04)

        leftTrigger = if ((b5 and 0x80) != 0) 1f else 0f
        rightTrigger = if ((b3 and 0x80) != 0) 1f else 0f

        val rawLeftStickX = (buffer.get(6).toInt() and 0xFF) or ((buffer.get(7).toInt() and 0x0F) shl 8)
        val rawLeftStickY = ((buffer.get(7).toInt() and 0xF0) shr 4) or (buffer.get(8).toInt() shl 4)
        val rawRightStickX = (buffer.get(9).toInt() and 0xFF) or ((buffer.get(10).toInt() and 0x0F) shl 8)
        val rawRightStickY = ((buffer.get(10).toInt() and 0xF0) shr 4) or (buffer.get(11).toInt() shl 4)

        leftStickX = applyStickCalibration(rawLeftStickX, 0, 0)
        leftStickY = applyStickCalibration(-rawLeftStickY - 1, 0, 1)
        rightStickX = applyStickCalibration(rawRightStickX, 1, 0)
        rightStickY = applyStickCalibration(-rawRightStickY - 1, 1, 1)

        accelX = buffer.getShort(37) / 4096.0f
        accelY = buffer.getShort(39) / 4096.0f
        accelZ = buffer.getShort(41) / 4096.0f
        gyroZ = -buffer.getShort(43) / 16.0f
        gyroX = -buffer.getShort(45) / 16.0f
        gyroY = buffer.getShort(47) / 16.0f

        return true
    }

    private fun spiFlashRead(offset: Int, length: Int, buffer: ByteArray): Boolean {
        val address = byteArrayOf(
            (offset and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            ((offset shr 16) and 0xFF).toByte(),
            ((offset shr 24) and 0xFF).toByte(),
            length.toByte()
        )

        if (!sendSubcommand(0x10.toByte(), address, buffer)) {
            VibemisLog.warning("ProCon: Failed to receive SPI Flash data.")
            return false
        }

        return true
    }

    private fun checkUserCalMagic(offset: Int): Boolean {
        val buffer = ByteArray(PACKET_SIZE)
        if (!spiFlashRead(offset, 2, buffer)) {
            return false
        }
        return ((buffer[20].toInt() and 0xFF) == 0xB2) && ((buffer[21].toInt() and 0xFF) == 0xA1)
    }

    private fun loadStickCalibration(): Boolean {
        val buffer = ByteArray(PACKET_SIZE)

        var lsAddr = FACTORY_LS_CALIBRATION_OFFSET
        var rsAddr = FACTORY_RS_CALIBRATION_OFFSET

        if (checkUserCalMagic(USER_LS_MAGIC_OFFSET)) {
            lsAddr = USER_LS_CALIBRATION_OFFSET
            VibemisLog.info("ProCon: LS has user calibration!")
        }
        if (checkUserCalMagic(USER_RS_MAGIC_OFFSET)) {
            rsAddr = USER_RS_CALIBRATION_OFFSET
            VibemisLog.info("ProCon: RS has user calibration!")
        }

        var lsCalibrated = false
        if (spiFlashRead(lsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            val xMax = (buffer[20].toInt() and 0xFF) or ((buffer[21].toInt() and 0x0F) shl 8)
            val yMax = ((buffer[21].toInt() and 0xF0) shr 4) or ((buffer[22].toInt() and 0xFF) shl 4)
            val xCenter = (buffer[23].toInt() and 0xFF) or ((buffer[24].toInt() and 0x0F) shl 8)
            val yCenter = ((buffer[24].toInt() and 0xF0) shr 4) or ((buffer[25].toInt() and 0xFF) shl 4)
            val xMin = (buffer[26].toInt() and 0xFF) or ((buffer[27].toInt() and 0x0F) shl 8)
            val yMin = ((buffer[27].toInt() and 0xF0) shr 4) or ((buffer[28].toInt() and 0xFF) shl 4)
            stickCalibration[0][0][0] = xCenter - xMin // Min
            stickCalibration[0][0][1] = xCenter // Center
            stickCalibration[0][0][2] = xCenter + xMax // Max
            stickCalibration[0][1][0] = 0x1000 - yCenter - yMax // Min
            stickCalibration[0][1][1] = 0x1000 - yCenter // Center
            stickCalibration[0][1][2] = 0x1000 - yCenter + yMin // Max
            stickExtends[0][0][0] = ((xCenter - stickCalibration[0][0][0]) * -0.7).toFloat()
            stickExtends[0][0][1] = ((stickCalibration[0][0][2] - xCenter) * 0.7).toFloat()
            stickExtends[0][1][0] = ((yCenter - stickCalibration[0][1][0]) * -0.7).toFloat()
            stickExtends[0][1][1] = ((stickCalibration[0][1][2] - yCenter) * 0.7).toFloat()

            lsCalibrated = true
        }

        if (!lsCalibrated) {
            applyDefaultCalibration(0)
        }

        var rsCalibrated = false
        if (spiFlashRead(rsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            val xCenter = (buffer[20].toInt() and 0xFF) or ((buffer[21].toInt() and 0x0F) shl 8)
            val yCenter = ((buffer[21].toInt() and 0xF0) shr 4) or ((buffer[22].toInt() and 0xFF) shl 4)
            val xMin = (buffer[23].toInt() and 0xFF) or ((buffer[24].toInt() and 0x0F) shl 8)
            val yMin = ((buffer[24].toInt() and 0xF0) shr 4) or ((buffer[25].toInt() and 0xFF) shl 4)
            val xMax = (buffer[26].toInt() and 0xFF) or ((buffer[27].toInt() and 0x0F) shl 8)
            val yMax = ((buffer[27].toInt() and 0xF0) shr 4) or ((buffer[28].toInt() and 0xFF) shl 4)
            stickCalibration[1][0][0] = xCenter - xMin // Min
            stickCalibration[1][0][1] = xCenter // Center
            stickCalibration[1][0][2] = xCenter + xMax // Max
            stickCalibration[1][1][0] = 0x1000 - yCenter - yMax // Min
            stickCalibration[1][1][1] = 0x1000 - yCenter // Center
            stickCalibration[1][1][2] = 0x1000 - yCenter + yMin // Max
            stickExtends[1][0][0] = ((xCenter - stickCalibration[1][0][0]) * -0.7).toFloat()
            stickExtends[1][0][1] = ((stickCalibration[1][0][2] - xCenter) * 0.7).toFloat()
            stickExtends[1][1][0] = ((yCenter - stickCalibration[1][1][0]) * -0.7).toFloat()
            stickExtends[1][1][1] = ((stickCalibration[1][1][2] - yCenter) * 0.7).toFloat()

            rsCalibrated = true
        }

        if (!rsCalibrated) {
            applyDefaultCalibration(1)
        }

        return true
    }

    private fun applyDefaultCalibration(stick: Int) {
        for (axis in 0 until 2) {
            stickCalibration[stick][axis][0] = 0x000 // Min
            stickCalibration[stick][axis][1] = 0x800 // Center
            stickCalibration[stick][axis][2] = 0xFFF // Max

            stickExtends[stick][axis][0] = (-0x700).toFloat()
            stickExtends[stick][axis][1] = 0x700.toFloat()
        }
    }

    private fun applyStickCalibration(valueArg: Int, stick: Int, axis: Int): Float {
        var value = valueArg
        val center = stickCalibration[stick][axis][1]

        if (value < 0) {
            value += 0x1000
        }

        value -= center

        if (value < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = value.toFloat()
            return -1f
        } else if (value > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = value.toFloat()
            return 1f
        }

        return if (value > 0) {
            value / stickExtends[stick][axis][1]
        } else {
            -value / stickExtends[stick][axis][0]
        }
    }

    companion object {
        private const val PACKET_SIZE = 64
        private const val FACTORY_LS_CALIBRATION_OFFSET = 0x603D
        private const val FACTORY_RS_CALIBRATION_OFFSET = 0x6046
        private const val USER_LS_MAGIC_OFFSET = 0x8010
        private const val USER_LS_CALIBRATION_OFFSET = 0x8012
        private const val USER_RS_MAGIC_OFFSET = 0x801B
        private const val USER_RS_CALIBRATION_OFFSET = 0x801D
        private const val STICK_CALIBRATION_LENGTH = 9
        private const val COMMAND_RETRIES = 10

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean {
            return device.vendorId == 0x057e && device.productId == 0x2009
        }
    }
}
