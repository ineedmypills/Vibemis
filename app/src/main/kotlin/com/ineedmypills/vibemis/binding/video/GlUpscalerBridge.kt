package com.ineedmypills.vibemis.binding.video

import android.content.Context
import android.view.Surface
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.render.GlUpscaleRenderer

/**
 * Direct bridge between MediaCodecDecoderRenderer and GlUpscaleRenderer without reflection.
 */
class GlUpscalerBridge {

    var decoderInputSurface: Surface? = null
        private set

    private var renderer: GlUpscaleRenderer? = null
    private var windowSurfaceRef: Surface? = null
    private var activePrefs: PreferenceConfiguration? = null
    private var activeSrcW = 0
    private var activeSrcH = 0
    private var isHdrActive = false
    private var isHdrDirectPresent = false
    private var debugEnabled = false

    val isReady: Boolean
        get() = renderer != null && decoderInputSurface != null && windowSurfaceRef != null

    val overlayLine: String
        get() = renderer?.getFsrOverlayLine() ?: ""

    val weightMs: Float
        get() = renderer?.getWeightMs() ?: 0f

    fun ensureCreated(
        windowSurface: Surface?,
        srcW: Int,
        srcH: Int,
        prefs: PreferenceConfiguration?,
        ctx: Context?
    ): Boolean {
        if (windowSurface == null || !windowSurface.isValid) {
            release()
            return false
        }

        if (renderer != null &&
            windowSurfaceRef === windowSurface &&
            activeSrcW == srcW &&
            activeSrcH == srcH &&
            decoderInputSurface?.isValid == true
        ) {
            activePrefs = prefs
            return true
        }

        release()

        return try {
            val newRenderer = GlUpscaleRenderer(ctx, windowSurface, srcW, srcH, prefs)
            newRenderer.setFsrDebugEnabled(debugEnabled)
            newRenderer.setHdrMode(isHdrActive, isHdrDirectPresent)

            val inputSurface = newRenderer.createDecoderInputSurface()
            if (inputSurface == null || !inputSurface.isValid) {
                try { newRenderer.release() } catch (_: Throwable) {}
                VibemisLog.warning("GlUpscalerBridge: failed to create OES decoder input surface")
                false
            } else {
                renderer = newRenderer
                decoderInputSurface = inputSurface
                windowSurfaceRef = windowSurface
                activePrefs = prefs
                activeSrcW = srcW
                activeSrcH = srcH
                true
            }
        } catch (t: Throwable) {
            VibemisLog.warning("GlUpscalerBridge: ensureCreated failed: $t")
            release()
            false
        }
    }

    fun setPresentationHint(ctx: Context?) {
        try {
            renderer?.setPresentationSizeHintFromContext(ctx)
        } catch (_: Throwable) {
        }
    }

    fun setPresentationSizeHint(width: Int, height: Int) {
        try {
            renderer?.setPresentationSizeHint(width, height)
        } catch (_: Throwable) {
        }
    }

    fun start() {
        try {
            renderer?.start()
        } catch (t: Throwable) {
            VibemisLog.warning("GlUpscalerBridge: start failed: $t")
        }
    }

    fun applyVsyncSetting() {
        try {
            renderer?.applyVsyncSetting()
        } catch (_: Throwable) {
        }
    }

    fun applyThreadPriorities() {
        try {
            renderer?.applyThreadPriorities()
        } catch (_: Throwable) {
        }
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        try {
            renderer?.setFsrDebugEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    fun setHdrMode(hdrActive: Boolean, directPresent: Boolean) {
        isHdrActive = hdrActive
        isHdrDirectPresent = directPresent
        try {
            renderer?.setHdrMode(hdrActive, directPresent)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        val currentRenderer = renderer
        renderer = null
        decoderInputSurface = null
        windowSurfaceRef = null
        activePrefs = null
        activeSrcW = 0
        activeSrcH = 0
        try {
            currentRenderer?.release()
        } catch (_: Throwable) {
        }
    }
}
