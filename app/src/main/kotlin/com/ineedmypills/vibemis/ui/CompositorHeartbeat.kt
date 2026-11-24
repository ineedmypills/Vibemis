package com.ineedmypills.vibemis.ui

import android.os.Handler
import android.os.Looper
import android.view.View

class CompositorHeartbeat(heartbeatView: View?) {
    private val heartbeatView: View = requireNotNull(heartbeatView) { "heartbeatView must not be null" }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var alternatePhase = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            this@CompositorHeartbeat.heartbeatView.setBackgroundColor(
                if (alternatePhase) COLOR_PHASE_TWO else COLOR_PHASE_ONE
            )
            alternatePhase = !alternatePhase
            this@CompositorHeartbeat.heartbeatView.invalidate()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { start() }
            return
        }
        if (running) return
        running = true
        heartbeatView.visibility = View.VISIBLE
        heartbeat.run()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop() }
            return
        }
        running = false
        handler.removeCallbacks(heartbeat)
        heartbeatView.visibility = View.GONE
    }

    companion object {
        internal const val INTERVAL_MS = 100L
        internal const val COLOR_PHASE_ONE = 0xFF000000.toInt()
        internal const val COLOR_PHASE_TWO = 0xFF010101.toInt()
    }
}
