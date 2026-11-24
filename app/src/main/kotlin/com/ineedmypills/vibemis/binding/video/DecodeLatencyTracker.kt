package com.ineedmypills.vibemis.binding.video

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max

/**
 * DecodeLatencyTracker: allocation-free PTS->enqueueNs tracking + stats update helper.
 * Fixed-size ring backed by atomic arrays (no boxing, no Sparse*).
 */
class DecodeLatencyTracker {

    private var lastCleanupNs = 0L
    private val enqueueNsByPtsUs = PtsEnqueueTracker(MAX_SIZE)

    fun clear() {
        enqueueNsByPtsUs.clear()
        lastCleanupNs = 0L
    }

    @JvmOverloads
    fun onEnqueue(ptsUs: Long, enqueueNs: Long = System.nanoTime()) {
        enqueueNsByPtsUs.put(ptsUs, enqueueNs)
    }

    fun onDequeue(stats: VideoStats?, presentationTimeUs: Long, useFrameRenderTime: Boolean) {
        onDequeue(stats, presentationTimeUs, System.nanoTime(), useFrameRenderTime)
    }

    fun onDequeue(
        stats: VideoStats?,
        presentationTimeUs: Long,
        endNs: Long,
        useFrameRenderTime: Boolean
    ) {
        if (stats == null) {
            return
        }

        val enqNs = enqueueNsByPtsUs.take(presentationTimeUs)
        if (enqNs == Long.MIN_VALUE) {
            stats.decoderMisses++
            return
        }

        val decNs = endNs - enqNs
        val decUs = decNs / 1_000L

        // Store pure decoder latency in microseconds to preserve sub-ms precision.
        // The field name is historical; display code converts it back to milliseconds.
        if (decUs in 0..<1_000_000L) {
            stats.decoderTimeMs += decUs
            stats.decoderSamples++
        }

        // Keep end-to-end latency behavior when render-time is not used.
        // presentationTimeUs is derived from enqueueTimeMs (uptime-based) * 1000.
        // Use endNs (monotonic) to avoid an extra uptimeMillis() call and keep one clock domain.
        if (!useFrameRenderTime) {
            val startNs = presentationTimeUs * 1000L // us -> ns
            val e2eNs = endNs - startNs
            val e2eMs = e2eNs / 1_000_000L
            if (e2eMs in 0..<1000) {
                stats.endToEndLatencyMs += e2eMs
                stats.totalTimeMs += e2eMs
            }
        }
    }

    fun maybeCleanup() {
        val nowNs = System.nanoTime()
        if (nowNs - lastCleanupNs < CLEANUP_THRESHOLD_NS) {
            return
        }
        enqueueNsByPtsUs.cleanupOld(nowNs, CLEANUP_THRESHOLD_NS)
        lastCleanupNs = nowNs
    }

    // PTS->enqueueNs tracker: fixed-size ring backed by atomic arrays (no per-frame allocations).
    // put() publishes a slot by writing enqNs first, then the pts key last.
    private class PtsEnqueueTracker(capacity: Int) {
        private val cap: Int = max(8, capacity)
        private val ptsUs: AtomicLongArray = AtomicLongArray(cap)
        private val enqNs: AtomicLongArray = AtomicLongArray(cap)
        private val head = AtomicInteger(0)

        init {
            clear()
        }

        fun clear() {
            for (i in 0 until cap) {
                ptsUs.set(i, EMPTY_KEY)
                enqNs.set(i, 0L)
            }
            head.set(0)
        }

        fun put(pts: Long, ns: Long) {
            var idx = head.getAndIncrement()
            if (idx >= cap) {
                idx %= cap
                head.set(idx + 1)
            }

            // Publish: write value first, then key last.
            enqNs.set(idx, ns)
            ptsUs.set(idx, pts)
        }

        fun take(pts: Long): Long {
            var h = head.get()
            if (h >= cap) h %= cap

            var i = h
            for (n in 0 until cap) {
                i--
                if (i < 0) i = cap - 1

                val k = ptsUs.get(i)
                if (k == pts) {
                    val v = enqNs.get(i)
                    if (ptsUs.compareAndSet(i, pts, EMPTY_KEY)) {
                        return v
                    }
                }
            }
            return Long.MIN_VALUE
        }

        fun cleanupOld(nowNs: Long, thresholdNs: Long) {
            for (i in 0 until cap) {
                val k = ptsUs.get(i)
                if (k != EMPTY_KEY) {
                    val v = enqNs.get(i)
                    if ((nowNs - v) > thresholdNs) {
                        ptsUs.compareAndSet(i, k, EMPTY_KEY)
                    }
                }
            }
        }

        companion object {
            private const val EMPTY_KEY = Long.MIN_VALUE
        }
    }

    companion object {
        private const val CLEANUP_THRESHOLD_NS = 30_000_000_000L // 30 seconds
        private const val MAX_SIZE = 384 // ring capacity (bounded scan cost)
    }
}
