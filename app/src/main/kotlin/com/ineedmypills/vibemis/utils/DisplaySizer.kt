package com.ineedmypills.vibemis.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Computes a good buffer size for video presentation and applies it to TextureView/SurfaceView.
 */
object DisplaySizer {
    private const val MATCH_DISPLAY_TOLERANCE_PX = 12

    @Suppress("DEPRECATION")
    private fun getDisplayRealSizePx(display: Display?): IntArray {
        if (display == null) return intArrayOf(0, 0)
        try {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return intArrayOf(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (_: Throwable) {
        }
        return intArrayOf(0, 0)
    }

    @JvmStatic
    fun getPresentationSizePx(ctx: Context?): IntArray {
        if (ctx == null) return intArrayOf(1, 1)
        var w = 0
        var h = 0

        try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    w = b.width()
                    h = b.height()
                }
                if (w <= 0 || h <= 0) {
                    @Suppress("DEPRECATION")
                    val displaySize = getDisplayRealSizePx(wm.defaultDisplay)
                    w = displaySize[0]
                    h = displaySize[1]
                }
            }
        } catch (_: Throwable) {
        }

        if (w <= 0 || h <= 0) {
            try {
                val dmets = ctx.resources.displayMetrics
                w = max(w, dmets.widthPixels)
                h = max(h, dmets.heightPixels)
            } catch (_: Throwable) {
            }
        }

        if (w <= 0 || h <= 0) {
            w = 1
            h = 1
        }
        return intArrayOf(w, h)
    }

    private fun getPresentationSizePx(view: View?): IntArray {
        if (view != null) {
            try {
                val displaySize = getDisplayRealSizePx(view.display)
                if (displaySize[0] > 0 && displaySize[1] > 0) {
                    return displaySize
                }
            } catch (_: Throwable) {
            }
        }
        return getPresentationSizePx(view?.context)
    }

    @JvmStatic
    fun getViewSizePx(v: View?): IntArray {
        if (v == null) return intArrayOf(0, 0)
        var w = 0
        var h = 0

        try {
            w = v.width
            h = v.height
        } catch (_: Throwable) {
        }

        if (w <= 0 || h <= 0) {
            try {
                w = v.measuredWidth
                h = v.measuredHeight
            } catch (_: Throwable) {
            }
        }

        if (w <= 0 || h <= 0) return intArrayOf(0, 0)
        return intArrayOf(w, h)
    }

    @JvmStatic
    fun getBestBufferSizePx(v: View?): IntArray {
        if (v == null) return intArrayOf(1, 1)

        val pres = getPresentationSizePx(v)
        val pw = pres[0]
        val ph = pres[1]

        val vsz = getViewSizePx(v)
        val vw = vsz[0]
        val vh = vsz[1]

        if (vw <= 0 || vh <= 0) return pres

        if (abs(vw - pw) <= MATCH_DISPLAY_TOLERANCE_PX &&
            abs(vh - ph) <= MATCH_DISPLAY_TOLERANCE_PX
        ) {
            return pres
        }

        return intArrayOf(vw, vh)
    }

    @JvmStatic
    fun applyTo(tv: TextureView?) {
        val st = tv?.surfaceTexture ?: return
        val sz = getBestBufferSizePx(tv)
        val w = sz[0]
        val h = sz[1]
        if (w <= 0 || h <= 0) return
        try {
            st.setDefaultBufferSize(w, h)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun applyTo(sv: SurfaceView?) {
        val h = sv?.holder ?: return
        val sz = getBestBufferSizePx(sv)
        val w = sz[0]
        val hh = sz[1]
        if (w <= 0 || hh <= 0) return
        try {
            h.setFixedSize(w, hh)
        } catch (_: Throwable) {
        }
    }
}
