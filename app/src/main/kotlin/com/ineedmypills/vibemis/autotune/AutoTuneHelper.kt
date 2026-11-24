package com.ineedmypills.vibemis.autotune

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.WindowManager
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.profiles.ProfilesManager
import kotlin.math.roundToInt

data class AutoTuneResult(
    val deviceType: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val maxRefreshRate: Int,
    val recommendedResolution: String,
    val recommendedFps: String,
    val recommendedBitrateKbps: Int,
    val recommendedVideoFormat: String,
    val hdrSupported: Boolean,
    val ultraLowLatency: Boolean,
    val snappyInput: Boolean
)

object AutoTuneHelper {

    fun analyze(context: Context): AutoTuneResult {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                wm?.defaultDisplay
            }
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        }

        var maxRefreshRate = 60
        var nativeWidth = 1920
        var nativeHeight = 1080
        var hdrSupported = false

        if (display != null) {
            val currentMode = display.mode
            var highestHz = currentMode.refreshRate
            for (mode in display.supportedModes) {
                if (mode.refreshRate > highestHz) {
                    highestHz = mode.refreshRate
                }
            }
            maxRefreshRate = highestHz.roundToInt()
            val p1 = currentMode.physicalWidth
            val p2 = currentMode.physicalHeight
            nativeWidth = p1.coerceAtLeast(p2)
            nativeHeight = p1.coerceAtMost(p2)

            hdrSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentMode.supportedHdrTypes.isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                val hdrTypes = display.hdrCapabilities?.supportedHdrTypes
                hdrTypes != null && hdrTypes.isNotEmpty()
            }
        }

        val pm = context.packageManager
        val isTv = pm.hasSystemFeature("android.software.leanback") ||
                pm.hasSystemFeature("amazon.hardware.fire_tv")
        val isTablet = (context.resources.configuration.smallestScreenWidthDp >= 600)

        val deviceType = when {
            isTv -> "Android TV"
            isTablet -> "Tablet"
            else -> "Phone"
        }

        val recommendedFps = when {
            maxRefreshRate >= 115 -> "120"
            maxRefreshRate >= 85 -> "90"
            else -> "60"
        }

        val recommendedResolution = when {
            nativeHeight >= 2000 -> "3840x2160"
            nativeHeight >= 1350 -> "2560x1440"
            nativeHeight >= 960 -> "1920x1080"
            else -> "1280x720"
        }

        // Assess network state if available
        var isFastNetwork = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null) {
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (isCellular) {
                    isFastNetwork = false
                }
            }
        }

        val baseBitrate = when (recommendedResolution) {
            "3840x2160" -> if (recommendedFps == "120") 80000 else 55000
            "2560x1440" -> if (recommendedFps == "120") 60000 else 40000
            "1920x1080" -> if (recommendedFps == "120") 45000 else 25000
            else -> if (recommendedFps == "120") 25000 else 12000
        }

        val recommendedBitrateKbps = if (isFastNetwork) baseBitrate else (baseBitrate / 2).coerceAtLeast(8000)

        // Modern hardware handles HEVC smoothly with ~30% bandwidth savings
        val recommendedVideoFormat = "FORCE_HEVC"

        return AutoTuneResult(
            deviceType = deviceType,
            displayWidth = nativeWidth,
            displayHeight = nativeHeight,
            maxRefreshRate = maxRefreshRate,
            recommendedResolution = recommendedResolution,
            recommendedFps = recommendedFps,
            recommendedBitrateKbps = recommendedBitrateKbps,
            recommendedVideoFormat = recommendedVideoFormat,
            hdrSupported = hdrSupported,
            ultraLowLatency = true,
            snappyInput = true
        )
    }

    fun applyAutoTune(context: Context): AutoTuneResult {
        val res = analyze(context)
        apply(context, res)
        return res
    }

    fun apply(context: Context, result: AutoTuneResult) {
        val prefs = PreferenceConfiguration.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("list_resolution", result.recommendedResolution)
            .putString("list_fps", result.recommendedFps)
            .putInt("seekbar_bitrate_kbps", result.recommendedBitrateKbps)
            .putString("video_format", result.recommendedVideoFormat)
            .putBoolean("checkbox_ultra_low_latency", result.ultraLowLatency)
            .putBoolean("checkbox_snappy_input", result.snappyInput)
            .putBoolean("checkbox_async_decode", true)
            .apply()

        // Sync with active profile if loaded
        try {
            val pm = ProfilesManager.getInstance()
            pm.load(context)
            val active = pm.active
            if (active != null) {
                val opts = active.options?.toMutableMap() ?: HashMap<String, Any?>()
                opts["list_resolution"] = result.recommendedResolution
                opts["list_fps"] = result.recommendedFps
                opts["seekbar_bitrate_kbps"] = result.recommendedBitrateKbps
                opts["video_format"] = result.recommendedVideoFormat
                opts["checkbox_ultra_low_latency"] = result.ultraLowLatency
                opts["checkbox_snappy_input"] = result.snappyInput
                opts["checkbox_async_decode"] = true
                active.options = opts
                active.modifiedUtc = System.currentTimeMillis()
                pm.update(active)
            }
        } catch (_: Exception) {}
    }
}
