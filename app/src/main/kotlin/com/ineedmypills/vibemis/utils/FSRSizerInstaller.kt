package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.io.Closeable

/**
 * One-call installer that keeps the target view's buffer size matched to the best strategy
 * (presentation size when fullscreen, view size when letterboxed) and notifies GlUpscaleRenderer
 * with current buffer-size hints.
 */
object FSRSizerInstaller {
    /** Receives the exact buffer size selected for the presentation Surface. */
    fun interface SizeHintSink {
        fun setPresentationSizeHint(width: Int, height: Int)
    }

    interface AutoCloser : Closeable {
        fun start()
        fun stop()
        override fun close() {
            stop()
        }
    }

    @JvmStatic
    fun installForTextureView(
        activity: Activity,
        tv: TextureView,
        sink: SizeHintSink?
    ): AutoCloser {
        val sizer = TextureViewSizer(activity, tv) { applyHint(tv, sink) }
        return object : AutoCloser {
            override fun start() = sizer.start()
            override fun stop() = sizer.stop()
        }
    }

    @JvmStatic
    fun installForSurfaceView(
        activity: Activity,
        sv: SurfaceView,
        sink: SizeHintSink?
    ): AutoCloser {
        val sizer = SurfaceViewSizer(activity, sv) { applyHint(sv, sink) }
        return object : AutoCloser {
            override fun start() = sizer.start()
            override fun stop() = sizer.stop()
        }
    }

    private fun applyHint(targetView: View?, sink: SizeHintSink?) {
        if (sink == null || targetView == null) return
        val sz = DisplaySizer.getBestBufferSizePx(targetView)
        try {
            sink.setPresentationSizeHint(sz[0], sz[1])
        } catch (_: Throwable) {
        }
    }
}
