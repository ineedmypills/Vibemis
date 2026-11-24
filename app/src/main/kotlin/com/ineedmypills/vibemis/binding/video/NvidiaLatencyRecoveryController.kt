package com.ineedmypills.vibemis.binding.video

import com.ineedmypills.vibemis.VibemisLog
import java.util.Locale
import kotlin.math.max

/**
 * Controller for opt-in NVIDIA MediaCodec latency recovery.
 *
 * The normal no-action path is O(1), preallocated, and allocation-free.
 */
internal class NvidiaLatencyRecoveryController private constructor() {

    private val gate = NvidiaLatencyRecoveryGate()
    private val tracker = OutputDebtTracker()
    private val observation = OutputDebtTracker.Observation()

    @Volatile
    private var active = true

    @Volatile
    private var modeMask = Int.MIN_VALUE
    private var fpsBits = Int.MIN_VALUE

    @Volatile
    private var currentPtsUs = 0L

    @Volatile
    private var currentEpoch = 0L

    @Volatile
    private var currentPacked = OutputDebtTracker.UNMATCHED

    @Volatile
    private var currentResidenceUs = -1L

    @Volatile
    private var currentObservationNs = 0L

    private var loggedCatchupId = 0L
    private var safetyLockLogged = false

    private var structuralActions = 0
    private var phaseActions = 0
    private var maxDropsUsed = 0
    private var limitHits = 0
    private var safetyLocks = 0
    private var countedCatchupId = 0L
    private var currentCatchupDrops = 0

    fun isActive(): Boolean = active

    fun epoch(): Long = gate.epoch()

    fun updateMode(mask: Int, fps: Float) {
        if (!active) return
        val newFpsBits = java.lang.Float.floatToIntBits(fps)
        if (mask == modeMask && newFpsBits == fpsBits) return

        modeMask = mask
        fpsBits = newFpsBits
        gate.mode(mask, fps, System.nanoTime())
        VibemisLog.info("$TAG MODE mask=$mask fps=$fps")
    }

    fun queued(ptsUs: Long, queueEndNs: Long) {
        if (!active || ptsUs <= 0L || queueEndNs <= 0L) return
        tracker.onQueued(ptsUs, queueEndNs)
    }

    fun output(epoch: Long, outputReadyNs: Long, ptsUs: Long) {
        if (!active || outputReadyNs <= 0L || ptsUs <= 0L) return
        gate.output(outputReadyNs)
        currentEpoch = epoch
        currentPtsUs = ptsUs
        currentObservationNs = outputReadyNs
        tracker.observe(ptsUs, outputReadyNs, observation)
        currentPacked = observation.packed
        currentResidenceUs = observation.residenceUs
    }

    fun decide(epoch: Long, ptsUs: Long, flags: Int): NvidiaLatencyRecoveryGate.Decision? {
        if (!active) return null
        val sameOutput = currentEpoch == epoch && currentPtsUs == ptsUs
        val packed = if (sameOutput) currentPacked else OutputDebtTracker.UNMATCHED
        val matched = OutputDebtTracker.matched(packed)
        val lagFrames = if (matched) OutputDebtTracker.lagFrames(packed) else -1
        val lagPtsUs = if (matched) OutputDebtTracker.lagPtsUs(packed) else -1L
        val residenceUs = if (sameOutput) currentResidenceUs else -1L
        val decisionNs = if (sameOutput && currentObservationNs > 0L) currentObservationNs else System.nanoTime()

        return gate.decide(decisionNs, modeMask == 0, (flags and 6) != 0, matched, lagFrames, lagPtsUs, residenceUs)
    }

    fun released(decision: NvidiaLatencyRecoveryGate.Decision?, ok: Boolean) {
        if (decision == null) return
        gate.done(decision, ok)

        if (!ok) {
            VibemisLog.warning(
                "$TAG ACTION_FAILED id=${decision.request.id} type=${decision.request.type} " +
                    "lagF=${decision.lagFrames} lagPtsUs=${decision.lagPtsUs} residenceUs=${decision.residenceUs}"
            )
            return
        }

        when (decision.eventKind) {
            NvidiaLatencyRecoveryGate.EVENT_CATCHUP_DROP -> {
                if (countedCatchupId != decision.request.id) {
                    countedCatchupId = decision.request.id
                    currentCatchupDrops = 0
                    structuralActions++
                }
                currentCatchupDrops++
                if (loggedCatchupId != decision.request.id) {
                    loggedCatchupId = decision.request.id
                    VibemisLog.info(
                        "$TAG STRUCTURAL_BEGIN id=${decision.request.id} " +
                            "lagF=${decision.lagFrames} lagPtsUs=${decision.lagPtsUs} residenceUs=${decision.residenceUs}"
                    )
                }
            }
            NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END -> {
                maxDropsUsed = max(maxDropsUsed, currentCatchupDrops)
                val gateResult = gate.result()
                if (gateResult.startsWith("CATCHUP_LIMIT_")) {
                    limitHits++
                    VibemisLog.warning(
                        "$TAG STRUCTURAL_LIMIT id=${decision.request.id} maxDrops=${NvidiaLatencyRecoveryGate.MAX_DROPS} " +
                            "lagF=${decision.lagFrames} lagPtsUs=${decision.lagPtsUs} residenceUs=${decision.residenceUs}"
                    )
                } else {
                    VibemisLog.info(
                        "$TAG STRUCTURAL_END id=${decision.request.id} drops=$currentCatchupDrops " +
                            "lagF=${decision.lagFrames} lagPtsUs=${decision.lagPtsUs} residenceUs=${decision.residenceUs} result=$gateResult"
                    )
                }
                loggedCatchupId = 0L
                countedCatchupId = 0L
                currentCatchupDrops = 0
            }
            NvidiaLatencyRecoveryGate.EVENT_PHASE_DROP -> {
                phaseActions++
                VibemisLog.info("$TAG PHASE_DROP id=${decision.request.id} residenceUs=${decision.residenceUs}")
            }
        }

        if (gate.isSafetyLocked()) {
            if (!safetyLockLogged) {
                safetyLockLogged = true
                safetyLocks++
                VibemisLog.warning("$TAG SAFETY_LOCK")
            }
        } else {
            safetyLockLogged = false
        }
    }

    fun recoveryBegin(requestType: Int) {
        if (!active) return
        tracker.newEpoch()
        gate.newEpoch(System.nanoTime())
        modeMask = Int.MIN_VALUE
        fpsBits = Int.MIN_VALUE
        currentPacked = OutputDebtTracker.UNMATCHED
        currentResidenceUs = -1L
        currentObservationNs = 0L
        currentPtsUs = 0L
        currentEpoch = gate.epoch()
        loggedCatchupId = 0L
        countedCatchupId = 0L
        currentCatchupDrops = 0
        safetyLockLogged = false
        VibemisLog.info("$TAG CODEC_EPOCH epoch=$currentEpoch recoveryType=$requestType")
    }

    fun stop() {
        if (!active) return
        active = false
        maxDropsUsed = max(maxDropsUsed, currentCatchupDrops)
        gate.stop()
        VibemisLog.info(
            "$TAG SUMMARY structuralActions=$structuralActions phaseActions=$phaseActions " +
                "maxDropsUsed=$maxDropsUsed limitHits=$limitHits safetyLocks=$safetyLocks"
        )
    }

    companion object {
        private const val TAG = "[NvidiaLatencyRecovery]"
        const val MODE_CODEC_RECOVERY = 1 shl 7
        const val MODE_STOPPING = 1 shl 8
        const val MODE_NO_CODEC = 1 shl 9
        const val MODE_ASYNC_NOT_READY = 1 shl 10

        @JvmStatic
        fun isNvidia(name: String?): Boolean {
            if (name == null) return false
            val n = name.lowercase(Locale.US)
            return n.startsWith("omx.nvidia.") || n.startsWith("c2.nvidia.")
        }

        @JvmStatic
        fun open(codecName: String?): NvidiaLatencyRecoveryController? {
            if (!isNvidia(codecName)) return null
            val controller = NvidiaLatencyRecoveryController()
            VibemisLog.info(
                "$TAG CONFIG codec=$codecName maxDrops=${NvidiaLatencyRecoveryGate.MAX_DROPS} " +
                    "structural=window1p5s_timeOcc75_severity0p75_hold0p25 phase=frame_period_persistence"
            )
            return controller
        }
    }
}
