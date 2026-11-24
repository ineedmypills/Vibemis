package com.ineedmypills.vibemis.nvstream

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.av.video.VideoDecoderRenderer
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.HostHttpResponseException
import com.ineedmypills.vibemis.nvstream.http.VibemisCryptoProvider
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.nvstream.input.MouseButtonPacket
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Objects
import java.util.concurrent.Semaphore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class NvConnection(
    private val appContext: Context,
    host: ComputerDetails.AddressTuple,
    httpsPort: Int,
    private val uniqueId: String,
    config: StreamConfiguration,
    private val cryptoProvider: VibemisCryptoProvider,
    serverCert: X509Certificate?
) {
    private val context = ConnectionContext()
    private val isMonkey: Boolean = ActivityManager.isUserAMonkey()

    @Volatile
    var lastLaunchClientWarning: String? = null
        private set

    init {
        this.context.serverAddress = host
        this.context.httpsPort = httpsPort
        this.context.streamConfig = config
        this.context.serverCert = serverCert
        this.context.riKey = generateRiAesKey()
        this.context.riKeyId = generateRiKeyId()
    }

    fun stop() {
        VibemisBridge.interruptConnection()
        synchronized(VibemisBridge::class.java) {
            VibemisBridge.stopConnection()
            VibemisBridge.cleanupBridge()
        }
        connectionAllowed.release()
    }

    @Throws(IOException::class)
    private fun resolveServerAddress(): InetAddress {
        val serverAddr = context.serverAddress!!
        val addrs = InetAddress.getAllByName(serverAddr.address)
        for (addr in addrs) {
            try {
                Socket().use { s ->
                    s.setSoLinger(true, 0)
                    s.connect(InetSocketAddress(addr, serverAddr.port), 1000)
                    return addr
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        if (addrs.isNotEmpty()) {
            return addrs[0]
        } else {
            throw IOException("No addresses found for ${context.serverAddress}")
        }
    }

    private fun detectServerConnectionType(): Int {
        val connMgr = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connMgr.activeNetwork
        if (activeNetwork != null) {
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork)
            if (netCaps != null) {
                if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    return StreamConfiguration.STREAM_CFG_REMOTE
                } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return StreamConfiguration.STREAM_CFG_REMOTE
                }
            }

            val linkProperties = connMgr.getLinkProperties(activeNetwork)
            if (linkProperties != null) {
                val serverAddress: InetAddress = try {
                    resolveServerAddress()
                } catch (e: IOException) {
                    e.printStackTrace()
                    return StreamConfiguration.STREAM_CFG_AUTO
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val nat64Prefix = linkProperties.nat64Prefix
                    if (nat64Prefix != null && nat64Prefix.contains(serverAddress)) {
                        return StreamConfiguration.STREAM_CFG_REMOTE
                    }
                }

                for (route in linkProperties.routes) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && route.type != RouteInfo.RTN_UNICAST) {
                        continue
                    }

                    if (route.matches(serverAddress)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (!route.hasGateway()) {
                                return StreamConfiguration.STREAM_CFG_LOCAL
                            }
                        } else {
                            val gateway = route.gateway
                            if (gateway == null || gateway.isAnyLocalAddress) {
                                return StreamConfiguration.STREAM_CFG_LOCAL
                            }
                        }
                    }
                }
            }
        }
        return StreamConfiguration.STREAM_CFG_AUTO
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun startApp(): Boolean {
        val h = NvHTTP(context.serverAddress!!, context.httpsPort, uniqueId, context.serverCert, cryptoProvider)
        val serverInfo = h.getServerInfo(true)
        val listener = context.connListener!!
        val streamConfig = context.streamConfig!!

        context.serverAppVersion = h.getServerVersion(serverInfo)
        if (context.serverAppVersion == null) {
            listener.displayMessage("Server version malformed")
            return false
        }

        val details = h.getComputerDetails(serverInfo)
        context.isNvidiaServerSoftware = details.nvidiaServer
        context.serverGfeVersion = h.getGfeVersion(serverInfo)

        if (h.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            listener.displayMessage("Device not paired with computer")
            return false
        }

        context.serverCodecModeSupport = h.getServerCodecModeSupport(serverInfo).toInt()

        context.negotiatedHdr = (streamConfig.supportedVideoFormats and VibemisBridge.VIDEO_FORMAT_MASK_10BIT) != 0
        if ((context.serverCodecModeSupport and 0x20200) == 0 && context.negotiatedHdr) {
            listener.displayTransientMessage("Your PC GPU does not support streaming HDR. The stream will be SDR.")
            context.negotiatedHdr = false
        }

        if ((streamConfig.width > 4096 || streamConfig.height > 4096) &&
            (h.getServerCodecModeSupport(serverInfo) and 0x200L) == 0L && context.isNvidiaServerSoftware
        ) {
            listener.displayMessage("Your host PC does not support streaming at resolutions above 4K.")
            return false
        } else if ((streamConfig.width > 4096 || streamConfig.height > 4096) &&
            (streamConfig.supportedVideoFormats and VibemisBridge.VIDEO_FORMAT_MASK_H264.inv()) == 0
        ) {
            listener.displayMessage("Your streaming device must support HEVC or AV1 to stream at resolutions above 4K.")
            return false
        } else if (streamConfig.height >= 2160 && !h.supports4K(serverInfo)) {
            listener.displayTransientMessage("You must update GeForce Experience to stream in 4K. The stream will be 1080p.")
            context.negotiatedWidth = 1920
            context.negotiatedHeight = 1080
        } else {
            context.negotiatedWidth = streamConfig.width
            context.negotiatedHeight = streamConfig.height
        }

        if (streamConfig.remote == StreamConfiguration.STREAM_CFG_AUTO) {
            context.negotiatedRemoteStreaming = detectServerConnectionType()
            context.negotiatedPacketSize =
                if (context.negotiatedRemoteStreaming == StreamConfiguration.STREAM_CFG_REMOTE) 1024
                else streamConfig.maxPacketSize
        } else {
            context.negotiatedRemoteStreaming = streamConfig.remote
            context.negotiatedPacketSize = streamConfig.maxPacketSize
        }

        var app: NvApp? = streamConfig.app
        if (!streamConfig.app.isInitialized) {
            VibemisLog.info("Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead")
            app = h.getAppByName(streamConfig.app.appName)
            if (app == null) {
                listener.displayMessage("The app ${streamConfig.app.appName} is not in GFE app list")
                return false
            }
        }
        val resolvedApp = app!!

        val currentGameUuid = h.getCurrentGameUUID(serverInfo)
        if (h.getCurrentGame(serverInfo) != 0 || !currentGameUuid.isNullOrEmpty()) {
            try {
                if (h.getCurrentGame(serverInfo) == resolvedApp.appId || Objects.equals(currentGameUuid, resolvedApp.appUUID)) {
                    if (!h.launchApp(context, "resume", resolvedApp.appUUID, resolvedApp.appId, context.negotiatedHdr)) {
                        listener.displayMessage("Failed to resume existing session")
                        return false
                    }
                    lastLaunchClientWarning = h.lastLaunchClientWarning
                } else if (Objects.equals(NvApp.REMOTE_INPUT_UUID, resolvedApp.appUUID) || resolvedApp.isVibepolloSyntheticApp()) {
                    return launchNotRunningApp(h, context)
                } else {
                    return quitAndLaunch(h, context)
                }
            } catch (e: HostHttpResponseException) {
                when (e.errorCode) {
                    470 -> {
                        listener.displayMessage(
                            "This session wasn't started by this device, so it cannot be resumed. End streaming on the original device or the PC itself and try again. (Error code: ${e.errorCode})"
                        )
                        return false
                    }
                    525 -> {
                        listener.displayMessage(
                            "The application is minimized. Resume it on the PC manually or quit the session and start streaming again."
                        )
                        return false
                    }
                    else -> throw e
                }
            }

            VibemisLog.info("Resumed existing game session")
            return true
        } else {
            return launchNotRunningApp(h, context)
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    protected fun quitAndLaunch(h: NvHTTP, context: ConnectionContext): Boolean {
        try {
            if (!h.quitApp()) {
                context.connListener?.displayMessage("Failed to quit previous session! You must quit it manually")
                return false
            }
        } catch (e: HostHttpResponseException) {
            if (e.errorCode == 599) {
                context.connListener?.displayMessage(
                    "This session wasn't started by this device, so it cannot be quit. End streaming on the original device or the PC itself. (Error code: ${e.errorCode})"
                )
                return false
            } else {
                throw e
            }
        }

        return launchNotRunningApp(h, context)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun launchNotRunningApp(h: NvHTTP, context: ConnectionContext): Boolean {
        val app = context.streamConfig!!.app
        if (!h.launchApp(context, "launch", app.appUUID, app.appId, context.negotiatedHdr)) {
            context.connListener?.displayMessage("Failed to launch application")
            return false
        }
        lastLaunchClientWarning = h.lastLaunchClientWarning
        VibemisLog.info("Launched new game session")
        return true
    }

    fun start(
        videoDecoderRenderer: VideoDecoderRenderer,
        connectionListener: NvConnectionListener
    ) {
        Thread {
            context.connListener = connectionListener
            context.videoCapabilities = videoDecoderRenderer.getCapabilities()
            val streamConfig = context.streamConfig!!
            val appName = streamConfig.app.appName

            connectionListener.stageStarting(appName)

            var tryCount = 0
            do {
                var retry = false
                try {
                    if (!startApp()) {
                        retry = connectionListener.stageFailed(appName, 0, 0)
                        if (!retry) return@Thread
                    }
                    connectionListener.stageComplete(appName)
                } catch (e: HostHttpResponseException) {
                    e.printStackTrace()
                    val rawServerMsg = e.errorMessage?.takeIf { it.isNotBlank() } ?: e.message
                    connectionListener.displayMessage(rawServerMsg)
                    retry = connectionListener.stageFailed(appName, 0, e.errorCode)
                    if (!retry) return@Thread
                } catch (e: Exception) {
                    if (e is XmlPullParserException || e is IOException) {
                        e.printStackTrace()
                        connectionListener.displayMessage("${e.javaClass.simpleName}: ${e.message}")
                        retry = connectionListener.stageFailed(
                            appName,
                            VibemisBridge.VB_PORT_FLAG_TCP_47984 or VibemisBridge.VB_PORT_FLAG_TCP_47989,
                            if (tryCount < 2) 0 else -408
                        )
                        if (!retry) return@Thread
                    } else {
                        throw e
                    }
                }

                if (!retry) break
                tryCount += 1

                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            } while (tryCount < 5)

            if (tryCount >= 5) {
                connectionListener.stageFailed(appName, 0, -408)
                return@Thread
            }

            val ib = ByteBuffer.allocate(16)
            ib.putInt(context.riKeyId)

            try {
                connectionAllowed.acquire()
            } catch (e: InterruptedException) {
                connectionListener.displayMessage(e.message)
                connectionListener.stageFailed(appName, 0, 0)
                return@Thread
            }

            synchronized(VibemisBridge::class.java) {
                VibemisBridge.setupBridge(videoDecoderRenderer, connectionListener)
                val ret = VibemisBridge.startConnection(
                    context.serverAddress!!.address,
                    context.serverAppVersion!!,
                    context.serverGfeVersion,
                    context.rtspSessionUrl,
                    context.serverCodecModeSupport,
                    context.negotiatedWidth,
                    context.negotiatedHeight,
                    streamConfig.refreshRate,
                    streamConfig.bitrate,
                    context.negotiatedPacketSize,
                    context.negotiatedRemoteStreaming,
                    streamConfig.audioConfiguration.toInt(),
                    streamConfig.supportedVideoFormats,
                    streamConfig.clientRefreshRateX100,
                    context.riKey!!.encoded,
                    ib.array(),
                    context.videoCapabilities,
                    streamConfig.colorSpace,
                    streamConfig.colorRange
                )
                if (ret != 0) {
                    connectionAllowed.release()
                    return@Thread
                }
            }
        }.start()
    }

    fun sendExecServerCmd(cmdId: Int) {
        if (!isMonkey) {
            VibemisBridge.sendExecServerCmd(cmdId)
        }
    }

    fun sendMouseMove(deltaX: Short, deltaY: Short) {
        if (!isMonkey) {
            VibemisBridge.sendMouseMove(deltaX, deltaY)
        }
    }

    fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            VibemisBridge.sendMousePosition(x, y, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseMoveAsMousePosition(deltaX: Short, deltaY: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            VibemisBridge.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseButtonDown(mouseButton: Byte) {
        if (!isMonkey) {
            VibemisBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, mouseButton)
        }
    }

    fun sendMouseButtonUp(mouseButton: Byte) {
        if (!isMonkey) {
            VibemisBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, mouseButton)
        }
    }

    fun sendControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    ) {
        if (!isMonkey) {
            VibemisBridge.sendMultiControllerInput(
                controllerNumber,
                activeGamepadMask,
                buttonFlags,
                leftTrigger,
                rightTrigger,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
        }
    }

    fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (!isMonkey) {
            VibemisBridge.sendKeyboardInput(keyMap, keyDirection, modifier, flags)
        }
    }

    fun sendMouseScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            VibemisBridge.sendMouseHighResScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            VibemisBridge.sendMouseHighResHScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHighResScroll(scrollAmount: Short) {
        if (!isMonkey) {
            VibemisBridge.sendMouseHighResScroll(scrollAmount)
        }
    }

    fun sendMouseHighResHScroll(scrollAmount: Short) {
        if (!isMonkey) {
            VibemisBridge.sendMouseHighResHScroll(scrollAmount)
        }
    }

    fun sendTouchEvent(
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short
    ): Int {
        return if (!isMonkey) {
            VibemisBridge.sendTouchEvent(
                eventType,
                pointerId,
                x,
                y,
                pressureOrDistance,
                contactAreaMajor,
                contactAreaMinor,
                rotation
            )
        } else {
            VibemisBridge.VB_ERR_UNSUPPORTED
        }
    }

    fun sendPenEvent(
        eventType: Byte,
        toolType: Byte,
        penButtons: Byte,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short,
        tilt: Byte
    ): Int {
        return if (!isMonkey) {
            VibemisBridge.sendPenEvent(
                eventType,
                toolType,
                penButtons,
                x,
                y,
                pressureOrDistance,
                contactAreaMajor,
                contactAreaMinor,
                rotation,
                tilt
            )
        } else {
            VibemisBridge.VB_ERR_UNSUPPORTED
        }
    }

    fun sendControllerArrivalEvent(
        controllerNumber: Byte,
        activeGamepadMask: Short,
        type: Byte,
        supportedButtonFlags: Int,
        capabilities: Short
    ): Int {
        return VibemisBridge.sendControllerArrivalEvent(
            controllerNumber,
            activeGamepadMask,
            type,
            supportedButtonFlags,
            capabilities
        )
    }

    fun sendControllerTouchEvent(
        controllerNumber: Byte,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float
    ): Int {
        return if (!isMonkey) {
            VibemisBridge.sendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure)
        } else {
            VibemisBridge.VB_ERR_UNSUPPORTED
        }
    }

    fun sendControllerMotionEvent(
        controllerNumber: Byte,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float
    ): Int {
        return if (!isMonkey) {
            VibemisBridge.sendControllerMotionEvent(controllerNumber, motionType, x, y, z)
        } else {
            VibemisBridge.VB_ERR_UNSUPPORTED
        }
    }

    fun sendControllerBatteryEvent(controllerNumber: Byte, batteryState: Byte, batteryPercentage: Byte) {
        VibemisBridge.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage)
    }

    fun sendUtf8Text(text: String) {
        if (!isMonkey) {
            VibemisBridge.sendUtf8Text(text)
        }
    }

    companion object {
        private val connectionAllowed = Semaphore(1)

        private fun generateRiAesKey(): SecretKey {
            try {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(128)
                return keyGen.generateKey()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun generateRiKeyId(): Int = SecureRandom().nextInt()

        @JvmStatic
        fun findExternalAddressForMdns(stunHostname: String, stunPort: Int): String? {
            return VibemisBridge.findExternalAddressIP4(stunHostname, stunPort)
        }
    }
}
