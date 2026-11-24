package com.ineedmypills.vibemis.binding.video

import android.media.MediaCodec
import com.ineedmypills.vibemis.VibemisLog
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.min

/**
 * NanoPacer: 1:1 pacing detector + cooperative wait/drain helper.
 * Allocation-free on the hot path.
 */
class NanoPacer {

    private val lock = Any()

    @Volatile
    private var oneToOne = false
    @Volatile
    private var intervalNs = 0L
    @Volatile
    private var nextDeadlineNs = 0L

    private var lastFrameTimeNs = 0L
    private var emaIntervalNs = 0L
    private var stableCounter = 0
    private var unstableCounter = 0
    private var lastEvaluationNs = 0L

    // PTS -> monotonic mapping
    private var ptsBaseUs = Long.MIN_VALUE
    private var monoBaseNs = 0L
    private var lastPtsUs = Long.MIN_VALUE

    // Smoothed clock slip (ns)
    private var slipNs = 0L

    interface OutputCallbacks {
        fun nextOutputIndex(info: MediaCodec.BufferInfo, timeoutUs: Int): Int
        fun releaseOutputBuffer(index: Int, render: Boolean)
        fun onDequeued(presentationTimeUs: Long, dequeueNs: Long)
    }

    // Reusable holder to avoid per-frame allocations
    class LatestOutput {
        @JvmField
        var index: Int = -1
        @JvmField
        var ptsUs: Long = 0L
        @JvmField
        var flags: Int = 0
        @JvmField
        var dequeueNs: Long = 0L
    }

    fun reset() {
        synchronized(lock) {
            oneToOne = false
            intervalNs = 0L
            nextDeadlineNs = 0L

            lastFrameTimeNs = 0L
            emaIntervalNs = 0L
            stableCounter = 0
            unstableCounter = 0
            lastEvaluationNs = 0L

            ptsBaseUs = Long.MIN_VALUE
            monoBaseNs = 0L
            lastPtsUs = Long.MIN_VALUE
            slipNs = 0L
        }
    }

    // Evaluate pacing state (call every decoded frame)
    fun updatePacingMode(fastVsyncEnabled: Boolean, streamTargetFps: Int, refreshRate: Int) {
        var rr = refreshRate.toFloat()

        // Compensate common truncation cases (e.g., 119 from 119.88, 59 from 59.94).
        if (refreshRate == 119) rr = 120f
        else if (refreshRate == 59) rr = 60f

        updatePacingMode(fastVsyncEnabled, streamTargetFps, rr)
    }

    fun updatePacingMode(fastVsyncEnabled: Boolean, streamTargetFps: Int, refreshRateHz: Float) {
        synchronized(lock) {
            if (!fastVsyncEnabled) {
                reset()
                return
            }

            var rr = refreshRateHz
            if (!(rr > 1f && rr < 1000f)) {
                reset()
                return
            }

            rr = snapRefreshRateHz(rr)

            val streamHz = if (streamTargetFps <= 0 || streamTargetFps > 1000) {
                rr
            } else {
                streamTargetFps.toFloat()
            }

            // If stream target is extremely close to display rate, prefer stream target to avoid deterministic drift/drop.
            val epsHz = 0.25f
            val targetHz = if (abs(rr - streamHz) <= epsHz) streamHz else min(streamHz, rr)

            if (!(targetHz > 1f)) {
                reset()
                return
            }

            val rateForLog = (targetHz + 0.5f).toInt()

            val nowNs = System.nanoTime()
            // Floor to preserve legacy behavior for integer rates (e.g. 60 -> 16,666,666ns).
            val targetIntervalNs = (1_000_000_000.0 / targetHz.toDouble()).toLong()

            // 60 Hz behavior unchanged; tighter gating at >= 90 Hz to avoid false 1:1 enters.
            val highHz = targetHz >= 90f
            val stabilityThreshold = if (highHz) 8 else PACE_STABILITY_THRESHOLD
            val disableThreshold = if (highHz) (stabilityThreshold + 4) else (PACE_STABILITY_THRESHOLD + 2)
            val tolerancePercent = if (highHz) 0.10f else TOLERANCE_PERCENT

            // If rate changes while active, resync immediately
            if (oneToOne && intervalNs != targetIntervalNs) {
                intervalNs = targetIntervalNs
                nextDeadlineNs = nowNs + targetIntervalNs

                VibemisLog.info("NanoPacer: pacing retarget ($rateForLog fps, rr=$rr, stream=$streamTargetFps)")
            }

            // Update every ~half frame
            if (lastEvaluationNs != 0L && (nowNs - lastEvaluationNs) < (targetIntervalNs shr 1)) {
                return
            }
            lastEvaluationNs = nowNs

            if (lastFrameTimeNs == 0L) {
                lastFrameTimeNs = nowNs
                emaIntervalNs = targetIntervalNs
                return
            }

            val intervalSampleNs = nowNs - lastFrameTimeNs
            lastFrameTimeNs = nowNs

            // Skip dropped/paused frames
            if (intervalSampleNs > (targetIntervalNs * 2L)) {
                return
            }

            // EMA: ema += alpha * (x - ema)
            emaIntervalNs += (EMA_ALPHA * (intervalSampleNs - emaIntervalNs)).toLong()

            val toleranceNs = (targetIntervalNs * tolerancePercent).toLong()
            val matching = abs(emaIntervalNs - targetIntervalNs) <= toleranceNs

            if (matching) {
                if (stableCounter < stabilityThreshold) stableCounter++
                unstableCounter = 0
            } else {
                if (unstableCounter < disableThreshold) unstableCounter++
                stableCounter = 0
            }

            // State transitions
            if (!oneToOne && stableCounter >= stabilityThreshold) {
                oneToOne = true
                stableCounter = 0
                intervalNs = targetIntervalNs
                nextDeadlineNs = nowNs + targetIntervalNs

                // Rebase PTS mapping on entry (prevents stale mapping)
                ptsBaseUs = Long.MIN_VALUE
                monoBaseNs = 0L
                lastPtsUs = Long.MIN_VALUE
                slipNs = 0L

                VibemisLog.info("NanoPacer: 1:1 pacing enabled ($rateForLog fps, rr=$rr, stream=$streamTargetFps)")
            } else if (oneToOne && unstableCounter >= disableThreshold) {
                oneToOne = false
                unstableCounter = 0
                intervalNs = 0L
                nextDeadlineNs = 0L

                ptsBaseUs = Long.MIN_VALUE
                monoBaseNs = 0L
                lastPtsUs = Long.MIN_VALUE
                slipNs = 0L

                VibemisLog.info("NanoPacer: 1:1 pacing disabled")
            }
        }
    }

    // Cooperative nano-pacing: keep draining while waiting to avoid decoder output backpressure.
    // Returns how many extra outputs were drained (and dropped) while waiting.
    fun waitAndDrainLatest(
        info: MediaCodec.BufferInfo?,
        latest: LatestOutput?,
        cb: OutputCallbacks?
    ): Int {
        if (info == null || latest == null || cb == null) return 0
        if (!oneToOne) return 0

        val framePeriodNs = intervalNs
        if (framePeriodNs <= 0L) return 0

        var drainedExtra = 0
        var targetNs = computeTargetNs(latest.ptsUs, framePeriodNs)

        while (true) {
            if (!oneToOne) break

            val nowNs = System.nanoTime()
            val remainingNs = targetNs - nowNs
            if (remainingNs <= 0L) break

            // Drain any ready outputs without blocking, keep only the newest.
            var gotNew = false
            while (true) {
                val outIndex = cb.nextOutputIndex(info, 0)
                if (outIndex < 0) break

                val dqNs = System.nanoTime()

                // Drop previous latest buffer
                val oldIndex = latest.index
                if (oldIndex >= 0) {
                    try {
                        cb.releaseOutputBuffer(oldIndex, false)
                    } catch (_: Throwable) {}
                }

                drainedExtra++
                latest.index = outIndex
                latest.ptsUs = info.presentationTimeUs
                latest.flags = info.flags
                latest.dequeueNs = dqNs

                try {
                    cb.onDequeued(latest.ptsUs, dqNs)
                } catch (_: Throwable) {}

                gotNew = true
            }

            if (gotNew) {
                // Re-target based on the newest frame (avoid presenting stale)
                targetNs = computeTargetNs(latest.ptsUs, framePeriodNs)
                continue
            }

            // Wait in small slices so we can keep draining (avoid long blocking)
            if (remainingNs > PARK_MIN_THRESHOLD_NS) {
                val sliceNs = min(remainingNs - SPIN_MAX_NS, 2_000_000L) // max 2ms slice
                if (sliceNs > 0L) LockSupport.parkNanos(sliceNs)
            } else {
                // Final fine spin
                while (System.nanoTime() < targetNs) { /* fine spin */ }
                break
            }
        }

        // Advance phase for next frame
        synchronized(lock) {
            nextDeadlineNs = targetNs + framePeriodNs
        }

        return drainedExtra
    }

    // Compute target timestamp (ns, monotonic) for a given PTS without blocking.
    private fun computeTargetNs(presentationTimeUs: Long, framePeriodNs: Long): Long {
        val nowNs = System.nanoTime()

        synchronized(lock) {
            // Rebase on first frame or PTS discontinuity.
            if (ptsBaseUs == Long.MIN_VALUE ||
                lastPtsUs == Long.MIN_VALUE ||
                presentationTimeUs < lastPtsUs ||
                (presentationTimeUs - lastPtsUs) > PTS_JUMP_US
            ) {
                ptsBaseUs = presentationTimeUs
                monoBaseNs = nowNs
                lastPtsUs = presentationTimeUs
                slipNs = 0L

                if (nextDeadlineNs == 0L) {
                    nextDeadlineNs = nowNs + framePeriodNs
                }
                return nextDeadlineNs
            }

            lastPtsUs = presentationTimeUs

            val idealNs = monoBaseNs + (presentationTimeUs - ptsBaseUs) * 1000L

            // Slip correction (low-pass)
            val errorNs = idealNs - nowNs
            slipNs += (SLIP_CORRECTION_ALPHA * (errorNs - slipNs)).toLong()

            // Clamp slip
            if (slipNs > framePeriodNs) slipNs = framePeriodNs
            else if (slipNs < -framePeriodNs) slipNs = -framePeriodNs

            var targetNs = idealNs - slipNs

            // Phase stabilization
            if (nextDeadlineNs != 0L && nextDeadlineNs > targetNs) {
                targetNs = nextDeadlineNs
            }

            // Late re-phase to avoid backlog accumulation
            if ((targetNs - nowNs) <= -framePeriodNs) {
                targetNs = nowNs + framePeriodNs
            }

            return targetNs
        }
    }

    companion object {
        private const val PARK_MIN_THRESHOLD_NS = 2_000_000L // 2ms min reliable park
        private const val SPIN_MAX_NS = 100_000L // 100µs max spin
        private const val PACE_STABILITY_THRESHOLD = 6
        private const val TOLERANCE_PERCENT = 0.15f
        private const val EMA_ALPHA = 0.2f
        private const val SLIP_CORRECTION_ALPHA = 0.05f // 5% smoothing

        // PTS jump guard
        private const val PTS_JUMP_US = 500_000L // 500ms

        private val COMMON_RATES = floatArrayOf(24f, 30f, 48f, 50f, 60f, 72f, 90f, 100f, 120f, 144f, 165f, 240f)

        private fun snapRefreshRateHz(rr: Float): Float {
            // Snap common fractional rates (NTSC-like) and near-integers to reduce long-term mismatch.
            // Keep conservative to avoid mis-snapping VRR-like values.
            val snapEps = 0.20f

            for (c in COMMON_RATES) {
                if (abs(rr - c) <= snapEps) return c

                val ntsc = c * (1000f / 1001f) // 59.94, 119.88, ...
                if (abs(rr - ntsc) <= snapEps) return c
            }
            return rr
        }
    }
}
