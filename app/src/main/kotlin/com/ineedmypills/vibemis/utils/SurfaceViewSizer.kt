package com.ineedmypills.vibemis.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

class SurfaceViewSizer(
    ctx: Context,
    private val sv: SurfaceView?,
    private val onApplied: Runnable?
) : SurfaceHolder.Callback, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private val ctx: Context = ctx.applicationContext
    private var started = false
    private var lastW = -1
    private var lastH = -1

    fun start() {
        if (started) return
        started = true

        sv?.holder?.addCallback(this)
        sv?.addOnLayoutChangeListener(this)

        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(this, null)

        applyIfNeeded()
    }

    fun stop() {
        if (!started) return
        started = false

        try {
            sv?.holder?.removeCallback(this)
        } catch (_: Throwable) {
        }
        try {
            sv?.removeOnLayoutChangeListener(this)
        } catch (_: Throwable) {
        }

        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(this)

        lastW = -1
        lastH = -1
    }

    private fun applyIfNeeded() {
        if (sv == null) return

        val sz = DisplaySizer.getBestBufferSizePx(sv)
        val w = sz[0]
        val h = sz[1]
        if (w <= 0 || h <= 0) return

        if (w == lastW && h == lastH) return

        lastW = w
        lastH = h

        try {
            sv.holder?.setFixedSize(w, h)
        } catch (_: Throwable) {
        }

        if (onApplied != null) {
            try {
                onApplied.run()
            } catch (_: Throwable) {
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = applyIfNeeded()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = applyIfNeeded()
    override fun surfaceDestroyed(holder: SurfaceHolder) {}

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
