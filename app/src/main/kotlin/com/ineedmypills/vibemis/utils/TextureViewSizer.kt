package com.ineedmypills.vibemis.utils

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.TextureView
import android.view.View

class TextureViewSizer(
    ctx: Context,
    private val tv: TextureView?,
    private val onApplied: Runnable?
) : TextureView.SurfaceTextureListener, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private val ctx: Context = ctx.applicationContext
    private var started = false
    private var lastW = -1
    private var lastH = -1

    fun start() {
        if (started) return
        started = true

        tv?.surfaceTextureListener = this
        tv?.addOnLayoutChangeListener(this)

        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(this, null)

        applyIfNeeded()
    }

    fun stop() {
        if (!started) return
        started = false

        try {
            tv?.surfaceTextureListener = null
        } catch (_: Throwable) {
        }
        try {
            tv?.removeOnLayoutChangeListener(this)
        } catch (_: Throwable) {
        }

        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(this)

        lastW = -1
        lastH = -1
    }

    private fun applyIfNeeded() {
        val st = tv?.surfaceTexture ?: return

        val sz = DisplaySizer.getBestBufferSizePx(tv)
        val w = sz[0]
        val h = sz[1]
        if (w <= 0 || h <= 0) return

        if (w == lastW && h == lastH) return

        lastW = w
        lastH = h

        try {
            st.setDefaultBufferSize(w, h)
        } catch (_: Throwable) {
        }

        if (onApplied != null) {
            try {
                onApplied.run()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) = applyIfNeeded()
    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = applyIfNeeded()
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDisplayChanged(displayId: Int) = applyIfNeeded()
    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    override fun onLayoutChange(
        v: View,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        ol: Int,
        ot: Int,
        orr: Int,
        ob: Int
    ) = applyIfNeeded()
}
