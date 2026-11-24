package com.ineedmypills.vibemis.binding.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.SparseArray
import android.util.SparseLongArray
import com.ineedmypills.vibemis.VibemisLog
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * AsyncCodecAdapter: isolates MediaCodec async callback plumbing + queues + BufferInfo pooling.
 * Keeps binder calls out of callback thread (best-effort).
 */
class AsyncCodecAdapter {

    fun interface OutputRelease {
        fun releaseNoRender(codec: MediaCodec, index: Int)
    }

    interface Callbacks {
        fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat)
        fun onCodecError(codec: MediaCodec, e: MediaCodec.CodecException)
    }

    private val inputQueue = ArrayBlockingQueue<Int>(INPUT_Q_CAP)
    private val outputQueue = ArrayBlockingQueue<Int>(OUTPUT_Q_CAP)

    // Indices queued for NO-RENDER release on the renderer thread (avoid binder calls in callback).
    private val releaseQueue = ArrayBlockingQueue<Int>(RELEASE_Q_CAP)

    private val outInfo = SparseArray<MediaCodec.BufferInfo>(16)

    // Output-ready timestamp (ns) per output index, to make decode latency comparable vs sync.
    // Guarded by synchronized(outInfo) together with outInfo (same index lifecycle).
    private val outReadyNs = SparseLongArray(16)

    // Ready timestamp (ns) for the last index returned by dequeueOutputIndex().
    @Volatile
    var lastOutputReadyNs: Long = 0L
        private set

    // Callback thread drop-policy knob (volatile for cross-thread visibility).
    @Volatile
    private var preferLowerDelays = false

    var callbackThread: HandlerThread? = null
        private set

    @Volatile
    private var callbackInstalled = false
    private var attachedCodec: MediaCodec? = null

    // Pool BufferInfo instances to avoid per-frame allocations.
    private val infoPool = ArrayDeque<MediaCodec.BufferInfo>(32)

    private fun obtainInfo(): MediaCodec.BufferInfo {
        synchronized(infoPool) {
            return infoPool.pollFirst() ?: MediaCodec.BufferInfo()
        }
    }

    private fun recycleInfo(bi: MediaCodec.BufferInfo?) {
        if (bi == null) return
        bi.set(0, 0, 0, 0)
        synchronized(infoPool) {
            if (infoPool.size < 64) {
                infoPool.addFirst(bi)
            }
        }
    }

    fun setPreferLowerDelays(enabled: Boolean) {
        preferLowerDelays = enabled
    }

    fun resetLastOutputReadyNs() {
        lastOutputReadyNs = 0L
    }

    val inputQueueSize: Int
        get() = inputQueue.size

    val outputQueueSize: Int
        get() = outputQueue.size

    val isActive: Boolean
        get() = callbackThread?.isAlive == true

    // Return next input index from async queue (INFO_TRY_AGAIN_LATER == -1).
    fun dequeueInputIndex(timeoutUs: Int): Int {
        return try {
            inputQueue.poll(timeoutUs.toLong(), TimeUnit.MICROSECONDS) ?: -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
    }

    // Return next output index and fill outInfoArg (INFO_TRY_AGAIN_LATER == -1).
    // If info is missing, releases the output buffer (no-render) to avoid leaks.
    fun dequeueOutputIndex(
        codec: MediaCodec?,
        outInfoArg: MediaCodec.BufferInfo?,
        timeoutUs: Int,
        release: OutputRelease?
    ): Int {
        if (codec == null) {
            lastOutputReadyNs = 0L
            return -1
        }

        return try {
            val idx = outputQueue.poll(timeoutUs.toLong(), TimeUnit.MICROSECONDS)
            if (idx == null) {
                lastOutputReadyNs = 0L
                return -1
            }

            val bi: MediaCodec.BufferInfo?
            val readyNs: Long

            synchronized(outInfo) {
                bi = outInfo.get(idx)
                readyNs = outReadyNs.get(idx, 0L)
                outReadyNs.delete(idx)

                if (bi != null && outInfoArg != null) {
                    outInfoArg.set(bi.offset, bi.size, bi.presentationTimeUs, bi.flags)
                }

                if (bi != null) {
                    outInfo.remove(idx)
                }
            }

            if (bi == null) {
                lastOutputReadyNs = 0L
                try {
                    if (release != null) {
                        release.releaseNoRender(codec, idx)
                    } else {
                        codec.releaseOutputBuffer(idx, false)
                    }
                } catch (_: Throwable) {}
                return -1
            }

            lastOutputReadyNs = readyNs
            recycleInfo(bi)
            idx
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            lastOutputReadyNs = 0L
            -1
        }
    }

    // Drain queued no-render releases on the renderer thread. Bounded to avoid bursts.
    fun drainNoRenderReleaseQueue(codec: MediaCodec?, release: OutputRelease?) {
        if (codec == null) {
            while (releaseQueue.poll() != null) {}
            return
        }

        val startNs = System.nanoTime()

        // Dynamic cap: drain more when backlog is large, but never exceed MAX.
        var cap = RELEASE_DRAIN_CAP_BASE
        try {
            val q = releaseQueue.size
            if (q > cap) cap = min(RELEASE_DRAIN_CAP_MAX, q)
        } catch (_: Throwable) {}

        var n = 0
        while (n < cap) {
            val idx = releaseQueue.poll() ?: break
            try {
                if (release != null) {
                    release.releaseNoRender(codec, idx)
                } else {
                    codec.releaseOutputBuffer(idx, false)
                }
            } catch (_: Throwable) {}
            n++

            // Hard budget to avoid spending too long in binder calls in a single tick.
            if ((System.nanoTime() - startNs) >= RELEASE_DRAIN_BUDGET_NS) {
                break
            }
        }
    }

    // Quiescence cleanup used by codec recovery: release outstanding async-owned buffers and clear state.
    // Does NOT detach callbacks or stop the callback thread.
    fun quiesceAndRelease(codec: MediaCodec?, release: OutputRelease?) {
        try {
            inputQueue.clear()
        } catch (_: Throwable) {}

        try {
            if (codec != null) {
                while (true) {
                    val idx = outputQueue.poll() ?: break
                    releaseOutputNoRenderAndRecycleInfo(codec, idx, release)
                }
                while (true) {
                    val idx = releaseQueue.poll() ?: break
                    try {
                        if (release != null) {
                            release.releaseNoRender(codec, idx)
                        } else {
                            codec.releaseOutputBuffer(idx, false)
                        }
                    } catch (_: Throwable) {}
                }
            } else {
                try {
                    outputQueue.clear()
                } catch (_: Throwable) {}
                try {
                    releaseQueue.clear()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            try {
                outputQueue.clear()
            } catch (_: Throwable) {}
            try {
                releaseQueue.clear()
            } catch (_: Throwable) {}
        }

        synchronized(outInfo) {
            try {
                outInfo.clear()
            } catch (_: Throwable) {}
            try {
                outReadyNs.clear()
            } catch (_: Throwable) {}
        }

        lastOutputReadyNs = 0L
    }

    fun attachIfNeeded(
        codec: MediaCodec?,
        callbackOsPriority: Int,
        callbacks: Callbacks?,
        release: OutputRelease?
    ) {
        if (codec == null) return

        // Already attached to this codec instance and callback thread is alive
        if (callbackInstalled && attachedCodec === codec && callbackThread?.isAlive == true) {
            return
        }

        // Codec instance changed: hard detach previous one (defensive)
        if (callbackInstalled && attachedCodec != null && attachedCodec !== codec) {
            try {
                detach(attachedCodec, release)
            } catch (_: Throwable) {}
        }

        // Reset state before attaching
        try {
            inputQueue.clear()
        } catch (_: Throwable) {}
        try {
            outputQueue.clear()
        } catch (_: Throwable) {}
        try {
            releaseQueue.clear()
        } catch (_: Throwable) {}
        synchronized(outInfo) {
            try {
                outInfo.clear()
            } catch (_: Throwable) {}
            try {
                outReadyNs.clear()
            } catch (_: Throwable) {}
        }
        lastOutputReadyNs = 0L

        // Ensure callback thread exists and is alive
        if (callbackThread == null || callbackThread?.isAlive != true) {
            callbackThread = HandlerThread("CodecAsync", callbackOsPriority).apply { start() }
        }

        val cb = Handler(callbackThread!!.looper)

        try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    try {
                        // Never block the MediaCodec callback thread.
                        if (!inputQueue.offer(index)) {
                            inputQueue.poll()
                            inputQueue.offer(index)
                        }
                    } catch (_: Throwable) {}
                }

                override fun onOutputBufferAvailable(
                    mc: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val copy = obtainInfo()
                    copy.set(info.offset, info.size, info.presentationTimeUs, info.flags)

                    var stored = false
                    try {
                        val readyNs = System.nanoTime()
                        synchronized(outInfo) {
                            // Defensive: recycle previous BufferInfo if the same index is reused.
                            val old = outInfo.get(index)
                            if (old != null && old !== copy) {
                                recycleInfo(old)
                            }

                            outInfo.put(index, copy)
                            outReadyNs.put(index, readyNs)
                            stored = true
                        }

                        // Prefer-lower-delays: keep only latest buffer for minimal latency.
                        if (preferLowerDelays) {
                            while (true) {
                                val oldIdx = outputQueue.poll() ?: break
                                enqueueNoRenderReleaseOrReleaseNow(mc, oldIdx, release)
                            }
                            if (!outputQueue.offer(index)) {
                                enqueueNoRenderReleaseOrReleaseNow(mc, index, release)
                            }
                            return
                        }

                        // Managed profiles: bounded queue with drop-oldest policy.
                        if (!outputQueue.offer(index)) {
                            val oldIdx = outputQueue.poll()
                            if (oldIdx != null) {
                                enqueueNoRenderReleaseOrReleaseNow(mc, oldIdx, release)
                            }
                            if (!outputQueue.offer(index)) {
                                enqueueNoRenderReleaseOrReleaseNow(mc, index, release)
                            }
                        }
                    } catch (_: Throwable) {
                        // Make sure we don't leak output buffers or BufferInfo objects.
                        try {
                            if (stored) {
                                enqueueNoRenderReleaseOrReleaseNow(mc, index, release)
                            } else {
                                recycleInfo(copy)
                                try {
                                    if (release != null) {
                                        release.releaseNoRender(mc, index)
                                    } else {
                                        mc.releaseOutputBuffer(index, false)
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    try {
                        callbacks?.onOutputFormatChanged(mc, format)
                    } catch (_: Throwable) {}
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    try {
                        callbacks?.onCodecError(mc, e)
                    } catch (_: Throwable) {}
                }
            }, cb)
        } catch (_: Throwable) {
            // Failed to install callback: shut down thread to avoid leaks.
            try {
                callbackThread?.quitSafely()
            } catch (_: Throwable) {}
            callbackThread = null
            attachedCodec = null
            callbackInstalled = false
            return
        }

        attachedCodec = codec
        callbackInstalled = true
    }

    fun detach(codec: MediaCodec?, release: OutputRelease?) {
        // Avoid join deadlock if detach is accidentally called on the callback thread.
        val cbThread = callbackThread
        if (cbThread != null && Thread.currentThread() === cbThread) {
            try {
                if (codec != null) {
                    try {
                        codec.setCallback(null, null)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            try {
                cbThread.quitSafely()
            } catch (_: Throwable) {}

            attachedCodec = null
            callbackInstalled = false
            callbackThread = null

            try {
                inputQueue.clear()
            } catch (_: Throwable) {}
            try {
                outputQueue.clear()
            } catch (_: Throwable) {}
            try {
                releaseQueue.clear()
            } catch (_: Throwable) {}
            synchronized(outInfo) {
                try {
                    outInfo.clear()
                } catch (_: Throwable) {}
                try {
                    outReadyNs.clear()
                } catch (_: Throwable) {}
            }
            lastOutputReadyNs = 0L
            return
        }

        try {
            if (codec != null) {
                try {
                    codec.setCallback(null, null)
                } catch (_: Throwable) {}
            }
        } finally {
            // Clear input queue first (safe: indices are not owned by us).
            try {
                inputQueue.clear()
            } catch (_: Throwable) {}

            // Release outstanding output buffers before clearing queues (avoid BufferQueue stalls).
            try {
                if (codec != null) {
                    while (true) {
                        val idx = outputQueue.poll() ?: break
                        releaseOutputNoRenderAndRecycleInfo(codec, idx, release)
                    }
                    while (true) {
                        val idx = releaseQueue.poll() ?: break
                        try {
                            if (release != null) {
                                release.releaseNoRender(codec, idx)
                            } else {
                                codec.releaseOutputBuffer(idx, false)
                            }
                        } catch (_: Throwable) {}
                    }
                } else {
                    try {
                        outputQueue.clear()
                    } catch (_: Throwable) {}
                    try {
                        releaseQueue.clear()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try {
                    outputQueue.clear()
                } catch (_: Throwable) {}
                try {
                    releaseQueue.clear()
                } catch (_: Throwable) {}
            }

            synchronized(outInfo) {
                try {
                    outInfo.clear()
                } catch (_: Throwable) {}
                try {
                    outReadyNs.clear()
                } catch (_: Throwable) {}
            }

            lastOutputReadyNs = 0L

            attachedCodec = null
            callbackInstalled = false

            // Stop and wait for callback thread.
            val thread = callbackThread
            if (thread != null) {
                try {
                    thread.quitSafely()
                    thread.join(1000)
                    if (thread.isAlive) {
                        VibemisLog.warning("Codec callback thread did not terminate in time")
                    }
                } catch (t: Throwable) {
                    VibemisLog.warning("Error stopping callback thread: $t")
                } finally {
                    callbackThread = null
                }
            }
        }
    }

    // Remove BufferInfo for index, recycle it immediately, then queue index for renderer-thread release.
    // Returns true if queued, false if queue is full.
    private fun enqueueNoRenderRelease(index: Int): Boolean {
        if (index < 0) return true

        var bi: MediaCodec.BufferInfo? = null
        try {
            synchronized(outInfo) {
                bi = outInfo.get(index)
                outInfo.remove(index)
                outReadyNs.delete(index)
            }
        } catch (_: Throwable) {}

        recycleInfo(bi)

        // Best-effort: never block the callback thread.
        return releaseQueue.offer(index)
    }

    // Safety wrapper for callback thread: if queue is full, release immediately to prevent leaks.
    private fun enqueueNoRenderReleaseOrReleaseNow(
        codec: MediaCodec?,
        index: Int,
        release: OutputRelease?
    ) {
        if (codec == null || index < 0) return
        if (!enqueueNoRenderRelease(index)) {
            try {
                if (release != null) {
                    release.releaseNoRender(codec, index)
                } else {
                    codec.releaseOutputBuffer(index, false)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun releaseOutputNoRenderAndRecycleInfo(
        codec: MediaCodec?,
        index: Int,
        release: OutputRelease?
    ) {
        if (codec == null || index < 0) return

        try {
            if (release != null) {
                release.releaseNoRender(codec, index)
            } else {
                codec.releaseOutputBuffer(index, false)
            }
        } catch (_: Throwable) {}

        var bi: MediaCodec.BufferInfo? = null
        try {
            synchronized(outInfo) {
                bi = outInfo.get(index)
                outInfo.remove(index)
                outReadyNs.delete(index)
            }
        } catch (_: Throwable) {}

        recycleInfo(bi)
    }

    companion object {
        private const val INPUT_Q_CAP = 64
        private const val OUTPUT_Q_CAP = 8
        private const val RELEASE_Q_CAP = 32

        // Cap how many releaseOutputBuffer(false) we do per render-loop tick to avoid bursts.
        // Use a dynamic cap + time budget to reduce callback-thread binder calls on 120Hz/backlog.
        private const val RELEASE_DRAIN_CAP_BASE = 8
        private const val RELEASE_DRAIN_CAP_MAX = 24
        private const val RELEASE_DRAIN_BUDGET_NS = 700_000L // 0.7ms
    }
}
