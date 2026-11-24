package com.ineedmypills.vibemis.binding.input

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance input task scheduler with dedicated thread.
 * Zero GC overhead on hot path, thread-safe with minimal synchronization.
 */
class InputSender(nameArg: String?, priority: Int) : Closeable {

    // Optimized: volatile handler for low-latency access
    @Volatile
    private var handler: Handler? = null
    val thread: HandlerThread
    private val stopped = AtomicBoolean(false)

    // Backwards compatibility
    constructor(unused: Any?) : this("InputSender", PRIORITY_HIGH_INPUT)

    init {
        val name = if (nameArg.isNullOrBlank()) "InputSender" else nameArg

        // Create high-priority worker thread
        val ht = HandlerThread(name, priority)
        ht.start()

        // Get looper (blocks until ready)
        val looper: Looper = ht.looper

        // Set thread priority using thread ID
        try {
            Process.setThreadPriority(ht.threadId, priority)
        } catch (t: Throwable) {
            Log.w(TAG, "setThreadPriority failed", t)
        }

        // Use async handler on API 28+ for better throughput
        val h = if (Build.VERSION.SDK_INT >= 28) {
            Handler.createAsync(looper)
        } else {
            Handler(looper)
        }

        // Enforce priority from within thread (fallback)
        h.postAtFrontOfQueue {
            try {
                Process.setThreadPriority(priority)
            } catch (_: Throwable) {}
        }

        this.thread = ht
        this.handler = h
    }

    /** Check if worker is alive and accepting work */
    val isRunning: Boolean
        get() {
            if (stopped.get()) return false
            val h = handler ?: return false
            return h.looper.thread.isAlive
        }

    /** Post task; returns false if stopped or unavailable */
    fun post(r: Runnable?): Boolean {
        return postInternal(r, false, 0)
    }

    /** Post task at front of queue for minimum latency */
    fun postAtFront(r: Runnable?): Boolean {
        if (r == null || stopped.get()) return false
        val h = handler ?: return false

        return try {
            h.postAtFrontOfQueue(r)
        } catch (t: Throwable) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "postAtFront failed", t)
            }
            false
        }
    }

    /** Post delayed task */
    fun postDelayed(r: Runnable?, delayMs: Long): Boolean {
        return postInternal(r, true, max(0L, delayMs))
    }

    private fun postInternal(r: Runnable?, delayed: Boolean, delayMs: Long): Boolean {
        if (r == null || stopped.get()) return false
        val h = handler ?: return false

        return try {
            if (delayed) h.postDelayed(r, delayMs) else h.post(r)
        } catch (t: Throwable) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "post operation failed", t)
            }
            false
        }
    }

    /** Remove specific pending callbacks */
    fun removeCallbacks(r: Runnable?) {
        if (r == null) return
        val h = handler ?: return
        try {
            h.removeCallbacks(r)
        } catch (_: Throwable) {}
    }

    /** Remove all queued callbacks */
    fun cancelAll() {
        val h = handler ?: return
        try {
            h.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {}
    }

    /** Gracefully stop worker thread */
    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        performShutdown()
    }

    private fun performShutdown() {
        val currentHandler = handler
        val looper = currentHandler?.looper

        // Prevent new callbacks
        cancelAll()
        handler = null

        if (looper != null) {
            try {
                looper.quitSafely()
            } catch (t: Throwable) {
                Log.w(TAG, "quitSafely failed, trying quit", t)
                try {
                    looper.quit()
                } catch (t2: Throwable) {
                    Log.e(TAG, "quit also failed", t2)
                }
            }
        }

        waitForThreadTermination()
    }

    private fun waitForThreadTermination() {
        // Avoid deadlock if called from worker thread
        if (Thread.currentThread() === thread) {
            return
        }

        if (!thread.isAlive) return

        val deadline = SystemClock.uptimeMillis() + SHUTDOWN_TIMEOUT_MS

        try {
            while (thread.isAlive) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) break

                thread.join(min(remaining, SHUTDOWN_CHECK_INTERVAL_MS))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        // Force interrupt if timeout
        if (thread.isAlive) {
            thread.interrupt()
        }
    }

    /** Get handler for advanced operations */
    fun getHandler(): Handler? {
        return if (isRunning) handler else null
    }

    /** Closeable compatibility */
    override fun close() {
        shutdown()
    }

    companion object {
        private const val TAG = "InputSender"

        // Thread priorities for latency-sensitive work
        const val PRIORITY_URGENT_INPUT = Process.THREAD_PRIORITY_URGENT_DISPLAY
        const val PRIORITY_HIGH_INPUT = Process.THREAD_PRIORITY_DISPLAY

        // Shutdown configuration
        private const val SHUTDOWN_TIMEOUT_MS = 500L
        private const val SHUTDOWN_CHECK_INTERVAL_MS = 50L
    }
}
