package com.ineedmypills.vibemis

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.Display
import android.view.Surface
import android.view.View
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.StreamContainer
import com.ineedmypills.vibemis.utils.ServerHelper
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal object GameDisplayHelper {

    fun isSamsungDevice(): Boolean {
        return try {
            "samsung".equals(Build.MANUFACTURER, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun setMetaKeyCaptureState(activity: Activity, enabled: Boolean) {
        if (!isSamsungDevice()) return
        try {
            val semWm = Class.forName("com.samsung.android.view.SemWindowManager")
            val getInstance = semWm.getMethod("getInstance")
            val mgr = getInstance.invoke(null) ?: return
            val requestMeta = try {
                semWm.getMethod("requestMetaKeyEvent", ComponentName::class.java, Boolean::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                semWm.getDeclaredMethod("requestMetaKeyEvent", ComponentName::class.java, Boolean::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
            }
            requestMeta.invoke(mgr, activity.componentName, enabled)
        } catch (_: Throwable) {
        }
    }

    private fun isRefreshRateEqualMatch(prefConfig: PreferenceConfiguration, refreshRate: Float): Boolean {
        return refreshRate >= prefConfig.fps && refreshRate <= prefConfig.fps + 3
    }

    private fun isRefreshRateGoodMatch(prefConfig: PreferenceConfiguration, refreshRate: Float): Boolean {
        return refreshRate >= prefConfig.fps && refreshRate.roundToInt() % prefConfig.fps.roundToInt().coerceAtLeast(1) <= 3
    }

    fun shouldIgnoreInsetsForResolution(
        activity: Activity,
        prefConfig: PreferenceConfiguration,
        width: Int,
        height: Int
    ): Boolean {
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false
        }
        val display = ServerHelper.getActiveDisplay(activity, prefConfig)
        for (candidate in display.supportedModes) {
            if ((width == candidate.physicalWidth && height == candidate.physicalHeight) ||
                (height == candidate.physicalWidth && width == candidate.physicalHeight)
            ) {
                return true
            }
        }
        return false
    }

    private fun mayReduceRefreshRate(prefConfig: PreferenceConfiguration): Boolean {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_WARP ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_WARP2 ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY ||
            prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED ||
            prefConfig.reduceRefreshRate
    }

    fun prepareDisplayForRendering(
        activity: Activity,
        prefConfig: PreferenceConfiguration,
        currentDisplay: Display,
        streamContainer: StreamContainer,
        displayWidth: Int,
        displayHeight: Int,
        isOnExternalDisplay: Boolean
    ): Float {
        val windowLayoutParams = activity.window.attributes
        var bestMode: Display.Mode = currentDisplay.mode
        val isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)
        var refreshRateIsGood = isRefreshRateGoodMatch(prefConfig, bestMode.refreshRate)
        var refreshRateIsEqual = isRefreshRateEqualMatch(prefConfig, bestMode.refreshRate)

        VibemisLog.info("Current display mode: ${bestMode.physicalWidth}x${bestMode.physicalHeight}x${bestMode.refreshRate}")

        for (candidate in currentDisplay.supportedModes) {
            val refreshRateReduced = candidate.refreshRate < bestMode.refreshRate
            val resolutionReduced = candidate.physicalWidth < bestMode.physicalWidth ||
                candidate.physicalHeight < bestMode.physicalHeight
            val resolutionFitsStream = candidate.physicalWidth >= prefConfig.width &&
                candidate.physicalHeight >= prefConfig.height

            VibemisLog.info("Examining display mode: ${candidate.physicalWidth}x${candidate.physicalHeight}x${candidate.refreshRate}")

            if (candidate.physicalWidth > 4096 && prefConfig.width <= 4096) {
                continue
            }

            if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                if (currentDisplay.mode.physicalWidth != candidate.physicalWidth ||
                    currentDisplay.mode.physicalHeight != candidate.physicalHeight
                ) {
                    continue
                }
            }

            if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                continue
            }

            if (mayReduceRefreshRate(prefConfig) && refreshRateIsEqual && !isRefreshRateEqualMatch(prefConfig, candidate.refreshRate)) {
                continue
            } else if (refreshRateIsGood) {
                if (!isRefreshRateGoodMatch(prefConfig, candidate.refreshRate)) {
                    continue
                }
                if (mayReduceRefreshRate(prefConfig)) {
                    if (candidate.refreshRate > bestMode.refreshRate) {
                        continue
                    }
                } else {
                    if (refreshRateReduced) {
                        continue
                    }
                }
            } else if (!isRefreshRateGoodMatch(prefConfig, candidate.refreshRate)) {
                if (refreshRateReduced) {
                    continue
                }
            }

            bestMode = candidate
            refreshRateIsGood = isRefreshRateGoodMatch(prefConfig, candidate.refreshRate)
            refreshRateIsEqual = isRefreshRateEqualMatch(prefConfig, candidate.refreshRate)
        }

        VibemisLog.info("Best display mode: ${bestMode.physicalWidth}x${bestMode.physicalHeight}x${bestMode.refreshRate}")

        if (currentDisplay.mode.modeId != bestMode.modeId) {
            if (prefConfig.enforceDisplayMode ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                currentDisplay.mode.physicalWidth != bestMode.physicalWidth ||
                currentDisplay.mode.physicalHeight != bestMode.physicalHeight
            ) {
                windowLayoutParams.preferredDisplayModeId = bestMode.modeId
                activity.window.attributes = windowLayoutParams
            } else {
                VibemisLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution")
            }
        } else {
            VibemisLog.info("Current display mode is already the best display mode")
        }

        val displayRefreshRate = bestMode.refreshRate

        if (prefConfig.videoScaleMode != PreferenceConfiguration.ScaleMode.STRETCH) {
            streamContainer.setDesiredAspectRatio(displayWidth.toDouble() / displayHeight.toDouble())
            streamContainer.setFillDisplay(prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.FILL)
            VibemisLog.info("surfaceChanged-->${displayWidth.toDouble() / displayHeight.toDouble()}")
            VibemisLog.info("scaleMode-->${prefConfig.videoScaleMode}")
        }

        return if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            isOnExternalDisplay
        ) {
            displayRefreshRate
        } else {
            min(currentDisplay.refreshRate, displayRefreshRate)
        }
    }

    fun applySurfaceFrameRateHintIfPossible(
        prefConfig: PreferenceConfiguration?,
        surface: Surface?,
        lastAppliedRate: Float
    ): Float {
        if (prefConfig == null || surface == null) return lastAppliedRate
        val desiredFrameRate = prefConfig.fps
        if (desiredFrameRate <= 0f) return lastAppliedRate
        if (lastAppliedRate > 0f && abs(lastAppliedRate - desiredFrameRate) < 0.01f) {
            return lastAppliedRate
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            } else {
                return lastAppliedRate
            }
            VibemisLog.info("Applied Surface frame-rate hint: $desiredFrameRate fps")
            desiredFrameRate
        } catch (t: Throwable) {
            VibemisLog.warning("Surface.setFrameRate() failed: $t")
            lastAppliedRate
        }
    }

    fun buildPictureInPictureParams(
        prefConfig: PreferenceConfiguration,
        streamContainer: StreamContainer,
        rootView: View,
        appName: String?,
        pcName: String?,
        autoEnter: Boolean
    ): PictureInPictureParams {
        val view: View = if (prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.FIT && streamContainer.scaleX == 1f) {
            streamContainer
        } else {
            rootView
        }

        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        val left = viewLocation[0]
        val top = viewLocation[1]
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val aspectRatio = Rational(width, height)
        val hint = Rect(left, top, left + width, top + height)

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setSourceRectHint(hint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName)
                if (pcName != null) {
                    builder.setSubtitle(pcName)
                }
            } else if (pcName != null) {
                builder.setTitle(pcName)
            }
        }

        return builder.build()
    }
}
