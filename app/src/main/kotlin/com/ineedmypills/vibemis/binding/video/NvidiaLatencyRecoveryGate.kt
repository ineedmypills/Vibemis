package com.ineedmypills.vibemis.binding.video

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded structural and phase latency recovery gate for NVIDIA MediaCodec queues.
 */
internal class NvidiaLatencyRecoveryGate {

    class Request(
        @JvmField val id: Long,
        @JvmField val epoch: Long,
        @JvmField val type: Int
    )

    class Decision(
        @JvmField val request: Request,
        @JvmField val drop: Boolean,
        @JvmField val eventKind: Int,
        @JvmField val lagFrames: Int,
        @JvmField val lagPtsUs: Long,
        @JvmField val residenceUs: Long
    )

    private var activeCatchup: Request? = null

    @Volatile
    private var active = true

    @Volatile
    private var epoch = 1L

    @Volatile
    private var lastOutputNs = 0L

    @Volatile
    private var modeMask = 1

    private var periodNs = 16_666_667L
    private var periodUs = 16_667L
    private var minLagPtsUs = 8_333L
    private var phaseEnterUs = 4_167L
    private var phaseExitUs = 2_500L
    private var phaseShallowLagPtsMaxUs = 25_000L
    private var phaseMinSamples = 30
    private var continuityGapNs = 50_000_000L

    private var nextActionId = 1L
    private var actionsWithoutLongHealth = 0
    private var safetyLocked = false

    private val structTimesNs = LongArray(STRUCT_RING_CAPACITY)
    private val structEvidenceNs = LongArray(STRUCT_RING_CAPACITY)
    private val structDurationNs = LongArray(STRUCT_RING_CAPACITY)
    private val structPositive = BooleanArray(STRUCT_RING_CAPACITY)
    private var structHead = 0
    private var structSize = 0
    private var structLastNs = 0L
    private var structEvidenceFrameNs = 0L
    private var structWindowDurationNs = 0L
    private var structPositiveDurationNs = 0L
    private var dropsDone = 0
    private var structLimitLock = false

    private var phaseArmed = true
    private var phaseVerifying = false
    private var phaseFailedLock = false
    private var phaseEligibleAfterNs = 0L
    private var phaseStartNs = 0L
    private var phaseLastNs = 0L
    private var phaseTotalSamples = 0
    private var phaseZeroSamples = 0
    private var phaseHighZeroSamples = 0
    private var phaseVerifyDeadlineNs = 0L
    private var phaseVerifyStartNs = 0L
    private var phaseVerifyLastNs = 0L
    private var phaseVerifySamples = 0
    private var phaseVerifyHealthySamples = 0
    private var phaseRearmStartNs = 0L
    private var phaseRearmLastNs = 0L
    private var phaseRearmSamples = 0
    private var phaseRearmHealthySamples = 0
    private var longHealthStartNs = 0L
    private var longHealthLastNs = 0L
    private var longHealthSamples = 0
    private var longHealthHealthySamples = 0

    @Volatile
    private var result = "IDLE"

    fun epoch(): Long = epoch

    @Synchronized
    fun mode(mask: Int, fps: Float, now: Long) {
        val previousMask = modeMask
        modeMask = mask
        if (fps > 1f) {
            periodNs = max(1_000_000L, (1_000_000_000.0 / fps).toLong())
            periodUs = max(1_000L, periodNs / 1000L)
            minLagPtsUs = max(1_000L, periodUs / 2L)
            phaseEnterUs = clamp(periodUs / 4L, 4_000L, 8_000L)
            phaseExitUs = clamp((periodUs * 3L) / 20L, 1_500L, 2_500L)
            phaseShallowLagPtsMaxUs = periodUs + minLagPtsUs
            phaseMinSamples = max(12, ceil(fps * 0.50).toInt())
            continuityGapNs = max(50_000_000L, (periodNs * 5L) / 2L)
        }
        if (mask != 0) {
            if (previousMask == 0) {
                abortTransientForEligibilityLoss(now)
            } else {
                resetEvidence()
            }
        }
        if (phaseEligibleAfterNs == 0L) {
            phaseEligibleAfterNs = now + STARTUP_PHASE_GUARD_NS
        }
    }

    fun output(now: Long) {
        lastOutputNs = now
    }

    @Synchronized
    fun decide(
        now: Long,
        eligible: Boolean,
        eosOrConfig: Boolean,
        matched: Boolean,
        lagFrames: Int,
        lagPtsUs: Long,
        residenceUs: Long
    ): Decision? {
        if (!active) return null
        if (modeMask != 0 || !eligible) {
            abortTransientForEligibilityLoss(now)
            return null
        }
        if (eosOrConfig || !matched || residenceUs < 0L) {
            resetEvidence()
            return null
        }

        val currentCatchup = activeCatchup
        if (currentCatchup != null) {
            if (currentCatchup.epoch != epoch) {
                activeCatchup = null
                return null
            }
            if (lagFrames <= 0 || lagPtsUs < minLagPtsUs) {
                activeCatchup = null
                resetStructural()
                phaseEligibleAfterNs = now + POST_CATCHUP_PHASE_GUARD_NS
                result = "CATCHUP_DONE_${currentCatchup.id}_D${dropsDone}_L${lagFrames}_P$lagPtsUs"
                return Decision(currentCatchup, false, EVENT_CATCHUP_END, lagFrames, lagPtsUs, residenceUs)
            }
            if (dropsDone >= MAX_DROPS) {
                activeCatchup = null
                structLimitLock = true
                resetStructural()
                phaseEligibleAfterNs = now + POST_CATCHUP_PHASE_GUARD_NS
                result = "CATCHUP_LIMIT_${currentCatchup.id}_D${dropsDone}_L${lagFrames}_P$lagPtsUs"
                return Decision(currentCatchup, false, EVENT_CATCHUP_END, lagFrames, lagPtsUs, residenceUs)
            }
            return Decision(currentCatchup, true, EVENT_CATCHUP_DROP, lagFrames, lagPtsUs, residenceUs)
        }

        val exactZero = lagFrames == 0 && lagPtsUs == 0L
        val structuralDebt = lagFrames > 0 && lagPtsUs >= minLagPtsUs
        val shallowOne = lagFrames == 1 && lagPtsUs >= minLagPtsUs && lagPtsUs <= phaseShallowLagPtsMaxUs
        val deepStructural = structuralDebt && !shallowOne

        if (structuralDebt) {
            if (deepStructural) {
                resetPhaseEvidence()
                resetPhaseVerifyEvidence()
                resetPhaseRearmEvidence()
            }
            if (structLimitLock) {
                updateLongHealth(false, now)
                return null
            }
            val structuralReady = observeStructuralSample(now, true, lagFrames)
            if (!safetyLocked && structuralReady) {
                val r = Request(nextActionId++, epoch, TYPE_AUTO_CATCHUP)
                activeCatchup = r
                dropsDone = 0
                resetPhaseEvidence()
                resetPhaseVerifyEvidence()
                resetPhaseRearmEvidence()
                noteAction()
                result = "CATCHUP_AUTO_${r.id}_L${lagFrames}_P$lagPtsUs"
                return Decision(r, true, EVENT_CATCHUP_DROP, lagFrames, lagPtsUs, residenceUs)
            }
            if (deepStructural) {
                updateLongHealth(false, now)
                return null
            }
        } else {
            if (exactZero) {
                if (structLimitLock) {
                    structLimitLock = false
                    resetStructural()
                } else {
                    observeStructuralSample(now, false, 0)
                }
            } else {
                resetStructural()
                resetPhaseEvidence()
                updateLongHealth(false, now)
                return null
            }
        }

        val strictHealthy = exactZero && residenceUs <= phaseExitUs
        val nonPathological = exactZero && residenceUs < phaseEnterUs
        updateLongHealth(strictHealthy, now)

        if (phaseVerifying) {
            if (phaseVerifyLastNs != 0L && now - phaseVerifyLastNs > continuityGapNs) {
                resetPhaseVerifyEvidence()
            }
            if (phaseVerifyStartNs == 0L) phaseVerifyStartNs = now
            phaseVerifyLastNs = now
            phaseVerifySamples++
            if (nonPathological) phaseVerifyHealthySamples++

            val verifyHeld = now - phaseVerifyStartNs
            val enoughVerifySamples = phaseVerifySamples >= phaseMinSamples
            val verifyHealthyRatio = ratioAtLeast(phaseVerifyHealthySamples, phaseVerifySamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN)
            if (verifyHeld >= PHASE_VERIFY_MIN_NS && enoughVerifySamples && verifyHealthyRatio) {
                phaseVerifying = false
                phaseFailedLock = false
                resetPhaseVerifyEvidence()
                resetPhaseRearmEvidence()
                result = "PHASE_VERIFY_OK"
            } else if (now >= phaseVerifyDeadlineNs) {
                phaseVerifying = false
                phaseFailedLock = true
                resetPhaseVerifyEvidence()
                resetPhaseRearmEvidence()
                result = "PHASE_VERIFY_FAILED_LOCK"
            }
            resetPhaseEvidence()
            return null
        }

        if (!phaseArmed || phaseFailedLock) {
            if (phaseRearmLastNs != 0L && now - phaseRearmLastNs > continuityGapNs) {
                resetPhaseRearmEvidence()
            }
            if (phaseRearmStartNs == 0L) phaseRearmStartNs = now
            phaseRearmLastNs = now
            phaseRearmSamples++
            if (nonPathological) phaseRearmHealthySamples++

            if (now - phaseRearmStartNs >= PHASE_REARM_WINDOW_NS) {
                val enoughRearmSamples = phaseRearmSamples >= phaseMinSamples * 2
                val rearmHealthyRatio = ratioAtLeast(phaseRearmHealthySamples, phaseRearmSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN)
                if (enoughRearmSamples && rearmHealthyRatio) {
                    phaseArmed = true
                    phaseFailedLock = false
                    result = "PHASE_REARM"
                }
                resetPhaseRearmEvidence()
            }
            resetPhaseEvidence()
            return null
        }

        if (now < phaseEligibleAfterNs || safetyLocked) {
            resetPhaseEvidence()
            return null
        }

        if (phaseLastNs != 0L && now - phaseLastNs > continuityGapNs) resetPhaseEvidence()
        phaseLastNs = now

        if (strictHealthy) {
            resetPhaseEvidence()
            return null
        }

        if (phaseStartNs == 0L) phaseStartNs = now
        phaseTotalSamples++
        if (exactZero) {
            phaseZeroSamples++
            if (residenceUs >= phaseEnterUs) phaseHighZeroSamples++
        }

        val held = now - phaseStartNs
        val enoughZeroRatio = ratioAtLeast(phaseZeroSamples, phaseTotalSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN)
        val highZeroRatio = ratioAtLeast(phaseHighZeroSamples, phaseZeroSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN)
        if (exactZero &&
            residenceUs >= phaseEnterUs &&
            held >= PHASE_HOLD_NS &&
            phaseTotalSamples >= phaseMinSamples &&
            enoughZeroRatio &&
            highZeroRatio
        ) {
            val r = Request(nextActionId++, epoch, TYPE_AUTO_PHASE)
            phaseArmed = false
            phaseVerifying = true
            phaseVerifyDeadlineNs = now + PHASE_VERIFY_NS
            resetPhaseVerifyEvidence()
            resetPhaseRearmEvidence()
            resetPhaseEvidence()
            noteAction()
            result = "PHASE_AUTO_${r.id}_R$residenceUs"
            return Decision(r, true, EVENT_PHASE_DROP, 0, 0, residenceUs)
        }

        if (held >= PHASE_EVIDENCE_MAX_NS) {
            val seedZero = exactZero
            val seedHigh = exactZero && residenceUs >= phaseEnterUs
            phaseStartNs = now
            phaseLastNs = now
            phaseTotalSamples = 1
            phaseZeroSamples = if (seedZero) 1 else 0
            phaseHighZeroSamples = if (seedHigh) 1 else 0
        }
        return null
    }

    @Synchronized
    fun done(d: Decision?, ok: Boolean) {
        if (d == null) return
        if (!ok) {
            if (activeCatchup === d.request) {
                activeCatchup = null
                structLimitLock = true
            }
            if (d.eventKind == EVENT_PHASE_DROP) {
                phaseVerifying = false
                phaseFailedLock = true
            }
            result = "FAILED_${d.request.id}"
            return
        }
        if (d.eventKind == EVENT_CATCHUP_DROP) {
            dropsDone++
            result = "CATCHUP_DROP_${d.request.id}_D${dropsDone}_L${d.lagFrames}_P${d.lagPtsUs}"
        } else if (d.eventKind == EVENT_PHASE_DROP) {
            result = "PHASE_DROP_DONE_${d.request.id}_R${d.residenceUs}"
        }
    }

    @Synchronized
    fun newEpoch(now: Long) {
        activeCatchup = null
        dropsDone = 0
        epoch++
        modeMask = 1
        lastOutputNs = 0L
        phaseArmed = true
        phaseVerifying = false
        phaseFailedLock = false
        phaseEligibleAfterNs = now + STARTUP_PHASE_GUARD_NS
        actionsWithoutLongHealth = 0
        safetyLocked = false
        structLimitLock = false
        resetPhaseVerifyEvidence()
        resetPhaseRearmEvidence()
        resetLongHealthEvidence()
        resetEvidence()
        result = "EPOCH_$epoch"
    }

    @Synchronized
    fun stop() {
        activeCatchup = null
        active = false
        modeMask = 1
        result = "STOPPED"
    }

    fun result(): String = result

    @Synchronized
    fun isSafetyLocked(): Boolean = safetyLocked

    @Synchronized
    fun status(now: Long): String = buildString {
        append("active=").append(active)
        append(" epoch=").append(epoch)
        append(" modeMask=").append(modeMask)
        append(" maxDrops=").append(MAX_DROPS)
        append(" minLagPtsUs=").append(minLagPtsUs)
        append(" phaseEnterUs=").append(phaseEnterUs)
        append(" phaseExitUs=").append(phaseExitUs)
        append(" phaseArmed=").append(phaseArmed)
        append(" phaseVerifying=").append(phaseVerifying)
        append(" phaseFailedLock=").append(phaseFailedLock)
        append(" safetyLocked=").append(safetyLocked)
        append(" structLimitLock=").append(structLimitLock)
        append(" recentOutput=").append(lastOutputNs != 0L && now >= lastOutputNs && now - lastOutputNs <= LIVE_OUTPUT_NS)
        append(" result=").append(result)
    }

    private fun noteAction() {
        actionsWithoutLongHealth++
        if (actionsWithoutLongHealth >= MAX_ACTIONS_WITHOUT_LONG_HEALTH) {
            safetyLocked = true
            result = "SAFETY_LOCK"
        }
    }

    private fun updateLongHealth(healthy: Boolean, now: Long) {
        if (longHealthLastNs != 0L && now - longHealthLastNs > continuityGapNs) resetLongHealthEvidence()
        if (longHealthStartNs == 0L) longHealthStartNs = now
        longHealthLastNs = now
        longHealthSamples++
        if (healthy) longHealthHealthySamples++
        if (now - longHealthStartNs >= LONG_HEALTH_RESET_NS) {
            val enoughSamples = longHealthSamples >= phaseMinSamples * 20
            val healthyRatio = ratioAtLeast(longHealthHealthySamples, longHealthSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN)
            if (enoughSamples && healthyRatio) {
                actionsWithoutLongHealth = 0
                safetyLocked = false
            }
            resetLongHealthEvidence()
        }
    }

    private fun resetEvidence() {
        resetStructural()
        resetPhaseEvidence()
    }

    private fun abortTransientForEligibilityLoss(now: Long) {
        activeCatchup = null
        dropsDone = 0
        phaseArmed = true
        phaseVerifying = false
        phaseFailedLock = false
        phaseEligibleAfterNs = now
        resetPhaseVerifyEvidence()
        resetPhaseRearmEvidence()
        resetLongHealthEvidence()
        resetEvidence()
        result = "ELIGIBILITY_FENCE"
    }

    private fun observeStructuralSample(now: Long, positive: Boolean, lagFrames: Int): Boolean {
        if (structLastNs != 0L && now - structLastNs > continuityGapNs) resetStructural()
        var dt = if (structLastNs == 0L) periodNs else min(max(0L, now - structLastNs), continuityGapNs)
        structLastNs = now

        if (structSize == STRUCT_RING_CAPACITY) {
            resetStructural()
            structLastNs = now
            dt = periodNs
        }

        val evidence = if (positive) saturatingMultiply(lagFrames, dt) else 0L
        val tail = (structHead + structSize) % STRUCT_RING_CAPACITY
        structTimesNs[tail] = now
        structEvidenceNs[tail] = evidence
        structDurationNs[tail] = dt
        structPositive[tail] = positive
        structSize++
        structWindowDurationNs = saturatingAdd(structWindowDurationNs, dt)
        if (positive) structPositiveDurationNs = saturatingAdd(structPositiveDurationNs, dt)
        structEvidenceFrameNs = saturatingAdd(structEvidenceFrameNs, evidence)

        val cutoff = now - STRUCT_WINDOW_NS
        while (structSize > 0 && structTimesNs[structHead] < cutoff) {
            val oldDuration = structDurationNs[structHead]
            structWindowDurationNs = max(0L, structWindowDurationNs - oldDuration)
            if (structPositive[structHead]) {
                structPositiveDurationNs = max(0L, structPositiveDurationNs - oldDuration)
            }
            structEvidenceFrameNs = max(0L, structEvidenceFrameNs - structEvidenceNs[structHead])
            structHead = (structHead + 1) % STRUCT_RING_CAPACITY
            structSize--
        }

        if (!positive || structSize <= 0) return false
        val held = now - structTimesNs[structHead]
        val occupancy = ratioAtLeastLong(structPositiveDurationNs, structWindowDurationNs, STRUCT_OCCUPANCY_NUM, STRUCT_OCCUPANCY_DEN)
        return held >= STRUCT_MIN_HOLD_NS && occupancy && structEvidenceFrameNs >= STRUCT_EVIDENCE_NS
    }

    private fun resetStructural() {
        structHead = 0
        structSize = 0
        structLastNs = 0L
        structEvidenceFrameNs = 0L
        structWindowDurationNs = 0L
        structPositiveDurationNs = 0L
    }

    private fun resetPhaseEvidence() {
        phaseStartNs = 0L
        phaseLastNs = 0L
        phaseTotalSamples = 0
        phaseZeroSamples = 0
        phaseHighZeroSamples = 0
    }

    private fun resetPhaseVerifyEvidence() {
        phaseVerifyStartNs = 0L
        phaseVerifyLastNs = 0L
        phaseVerifySamples = 0
        phaseVerifyHealthySamples = 0
    }

    private fun resetPhaseRearmEvidence() {
        phaseRearmStartNs = 0L
        phaseRearmLastNs = 0L
        phaseRearmSamples = 0
        phaseRearmHealthySamples = 0
    }

    private fun resetLongHealthEvidence() {
        longHealthStartNs = 0L
        longHealthLastNs = 0L
        longHealthSamples = 0
        longHealthHealthySamples = 0
    }

    companion object {
        const val MAX_DROPS = 12
        const val MAX_ACTIONS_WITHOUT_LONG_HEALTH = 6

        const val LIVE_OUTPUT_NS = 500_000_000L
        const val STRUCT_MIN_HOLD_NS = 250_000_000L
        const val STRUCT_EVIDENCE_NS = 750_000_000L
        const val STRUCT_WINDOW_NS = 1_500_000_000L
        const val STRUCT_OCCUPANCY_NUM = 3
        const val STRUCT_OCCUPANCY_DEN = 4
        const val STRUCT_RING_CAPACITY = 512
        const val POST_CATCHUP_PHASE_GUARD_NS = 500_000_000L
        const val PHASE_HOLD_NS = 750_000_000L
        const val PHASE_EVIDENCE_MAX_NS = 1_500_000_000L
        const val PHASE_VERIFY_NS = 1_500_000_000L
        const val PHASE_VERIFY_MIN_NS = 500_000_000L
        const val PHASE_REARM_WINDOW_NS = 2_000_000_000L
        const val HEALTH_RATIO_NUM = 3
        const val HEALTH_RATIO_DEN = 4
        const val LONG_HEALTH_RESET_NS = 30_000_000_000L
        const val STARTUP_PHASE_GUARD_NS = 1_000_000_000L

        const val EVENT_FAILED = 13
        const val EVENT_CATCHUP_DROP = 14
        const val EVENT_CATCHUP_END = 15
        const val EVENT_PHASE_DROP = 16
        const val EVENT_PHASE_END = 17

        const val TYPE_AUTO_CATCHUP = 2
        const val TYPE_AUTO_PHASE = 3

        private fun ratioAtLeast(good: Int, total: Int, num: Int, den: Int): Boolean =
            total > 0 && good.toLong() * den >= total.toLong() * num

        private fun ratioAtLeastLong(good: Long, total: Long, num: Int, den: Int): Boolean =
            total > 0L && good >= 0L && good * den >= total * num

        private fun clamp(v: Long, lo: Long, hi: Long): Long = max(lo, min(hi, v))

        private fun saturatingMultiply(a: Int, b: Long): Long {
            if (a <= 0 || b <= 0L) return 0L
            return if (b > Long.MAX_VALUE / a) Long.MAX_VALUE else b * a
        }

        private fun saturatingAdd(a: Long, b: Long): Long =
            if (b > 0L && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}
