package com.ineedmypills.vibemis.nvstream

import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge

class StreamConfiguration private constructor() {
    var app: NvApp = NvApp("Steam")
        private set
    var width: Int = 1280
        private set
    var height: Int = 720
        private set
    private var refreshRateFloat: Float = 60f
    private var launchRefreshRateFloat: Float = 60f
    var virtualDisplay: Boolean = false
        private set
    var resolutionScaleFactor: Int = 100
        private set
    var clientRefreshRateX100: Int = 0
        private set
    var bitrate: Int = 10000
        private set
    var sops: Boolean = true
        private set
    var adaptiveResolutionEnabled: Boolean = false
        private set
    var playLocalAudio: Boolean = false
        private set
    var maxPacketSize: Int = 1024
        private set
    var remote: Int = STREAM_CFG_AUTO
        private set
    var audioConfiguration: VibemisBridge.AudioConfiguration = VibemisBridge.AUDIO_CONFIGURATION_STEREO
        private set
    var supportedVideoFormats: Int = VibemisBridge.VIDEO_FORMAT_H264
        private set
    var attachedGamepadMask: Int = 0
        private set
    var encryptionFlags: Int = 0
        private set
    var colorRange: Int = 0
        private set
    var colorSpace: Int = 0
        private set
    var persistGamepadsAfterDisconnect: Boolean = false
        private set
    var enableUltraLowLatency: Boolean = false
        private set
    var clientName: String = ""
        private set
    var clientVrrRequested: Boolean = false
        private set
    var attachedPlayStationControllerMask: Int = 0
        private set

    val refreshRate: Int
        get() = if (refreshRateFloat == refreshRateFloat.toInt().toFloat()) {
            refreshRateFloat.toInt()
        } else {
            (refreshRateFloat * 1000).toInt()
        }

    val launchRefreshRate: Int
        get() = if (launchRefreshRateFloat == launchRefreshRateFloat.toInt().toFloat()) {
            launchRefreshRateFloat.toInt()
        } else {
            (launchRefreshRateFloat * 1000).toInt()
        }

    class Builder {
        private val config = StreamConfiguration()

        fun setApp(app: NvApp): Builder {
            config.app = app
            return this
        }

        fun setRemoteConfiguration(remote: Int): Builder {
            config.remote = remote
            return this
        }

        fun setResolution(width: Int, height: Int): Builder {
            config.width = width
            config.height = height
            return this
        }

        fun setRefreshRate(refreshRate: Float): Builder {
            config.refreshRateFloat = refreshRate
            return this
        }

        fun setLaunchRefreshRate(refreshRate: Float): Builder {
            config.launchRefreshRateFloat = refreshRate
            return this
        }

        fun setVirtualDisplay(enable: Boolean): Builder {
            config.virtualDisplay = enable
            return this
        }

        fun setResolutionScaleFactor(scaleFactor: Int): Builder {
            config.resolutionScaleFactor = scaleFactor
            return this
        }

        fun setBitrate(bitrate: Int): Builder {
            config.bitrate = bitrate
            return this
        }

        fun setEnableSops(enable: Boolean): Builder {
            config.sops = enable
            return this
        }

        fun enableAdaptiveResolution(enable: Boolean): Builder {
            config.adaptiveResolutionEnabled = enable
            return this
        }

        fun enableLocalAudioPlayback(enable: Boolean): Builder {
            config.playLocalAudio = enable
            return this
        }

        fun setMaxPacketSize(maxPacketSize: Int): Builder {
            config.maxPacketSize = maxPacketSize
            return this
        }

        fun setAttachedGamepadMask(attachedGamepadMask: Int): Builder {
            config.attachedGamepadMask = attachedGamepadMask
            return this
        }

        fun setAttachedGamepadMaskByCount(gamepadCount: Int): Builder {
            config.attachedGamepadMask = 0
            for (i in 0 until 4) {
                if (gamepadCount > i) {
                    config.attachedGamepadMask = config.attachedGamepadMask or (1 shl i)
                }
            }
            return this
        }

        fun setPersistGamepadsAfterDisconnect(value: Boolean): Builder {
            config.persistGamepadsAfterDisconnect = value
            return this
        }

        fun setClientRefreshRateX100(refreshRateX100: Int): Builder {
            config.clientRefreshRateX100 = refreshRateX100
            return this
        }

        fun setAudioConfiguration(audioConfig: VibemisBridge.AudioConfiguration): Builder {
            config.audioConfiguration = audioConfig
            return this
        }

        fun setSupportedVideoFormats(supportedVideoFormats: Int): Builder {
            config.supportedVideoFormats = supportedVideoFormats
            return this
        }

        fun setColorRange(colorRange: Int): Builder {
            config.colorRange = colorRange
            return this
        }

        fun setColorSpace(colorSpace: Int): Builder {
            config.colorSpace = colorSpace
            return this
        }

        fun setEnableUltraLowLatency(enable: Boolean): Builder {
            config.enableUltraLowLatency = enable
            return this
        }

        fun setClientName(clientName: String): Builder {
            config.clientName = clientName
            return this
        }

        fun setClientVrrRequested(enable: Boolean): Builder {
            config.clientVrrRequested = enable
            return this
        }

        fun setAttachedPlayStationControllerMask(mask: Int): Builder {
            config.attachedPlayStationControllerMask = mask
            return this
        }

        fun build(): StreamConfiguration = config
    }

    companion object {
        const val INVALID_APP_ID = 0
        const val STREAM_CFG_LOCAL = 0
        const val STREAM_CFG_REMOTE = 1
        const val STREAM_CFG_AUTO = 2
    }
}
