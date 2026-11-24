package com.ineedmypills.vibemis.binding.video

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max

/**
 * Allocation-free O(1) PTS to queue-sequence and residence-time tracker.
 */
internal class OutputDebtTracker {

    class Observation {
        @JvmField var packed: Long = UNMATCHED
        @JvmField var residenceUs: Long = -1L
        @JvmField var matchedSeq: Long = 0L
        @JvmField var latestSeq: Long = 0L

        fun clear() {
            packed = UNMATCHED
            residenceUs = -1L
            matchedSeq = 0L
            latestSeq = 0L
        }
    }

    private val sequence = AtomicLong()
    private val publishedSeq = AtomicLong()

    private val hashPts = AtomicLongArray(CAPACITY)
    private val hashQueueNs = AtomicLongArray(CAPACITY)
    private val hashSeq = AtomicLongArray(CAPACITY)

    private val seqPts = AtomicLongArray(CAPACITY)
    private val seqTag = AtomicLongArray(CAPACITY)

    @Volatile
    private var minEpochSeq = 1L

    fun onQueued(ptsUs: Long, queueEndNs: Long) {
        if (ptsUs <= 0L || queueEndNs <= 0L) return
        val seq = sequence.incrementAndGet()
        val h = hash(ptsUs)
        hashPts.set(h, ptsUs)
        hashQueueNs.set(h, queueEndNs)
        hashSeq.set(h, seq)

        val s = (seq and MASK.toLong()).toInt()
        seqPts.set(s, ptsUs)
        seqTag.set(s, seq)
        publishedSeq.set(seq)
    }

    fun newEpoch() {
        minEpochSeq = publishedSeq.get() + 1L
    }

    fun observe(ptsUs: Long, outputNs: Long, out: Observation) {
        out.clear()
        if (ptsUs <= 0L || outputNs <= 0L) return

        val latest = publishedSeq.get()
        if (latest < minEpochSeq) return

        val ls = (latest and MASK.toLong()).toInt()
        val latestTag1 = seqTag.get(ls)
        val latestPts = seqPts.get(ls)
        val latestTag2 = seqTag.get(ls)
        if (latestTag1 != latest || latestTag2 != latest) return

        val h = hash(ptsUs)
        val matchedSeq1 = hashSeq.get(h)
        val slotPts = hashPts.get(h)
        val queueNs = hashQueueNs.get(h)
        val matchedSeq2 = hashSeq.get(h)
        if (matchedSeq1 != matchedSeq2 ||
            matchedSeq1 < minEpochSeq ||
            matchedSeq1 > latest ||
            slotPts != ptsUs ||
            queueNs <= 0L
        ) {
            return
        }

        val lagFramesLong = latest - matchedSeq1
        val lagFrames = if (lagFramesLong > Int.MAX_VALUE) Int.MAX_VALUE else lagFramesLong.toInt()
        val lagPtsUs = max(0L, latestPts - ptsUs)
        val residenceUs = if (outputNs >= queueNs) (outputNs - queueNs) / 1000L else -1L
        if (residenceUs < 0L) return

        out.packed = (lagFrames.toLong() shl 32) or (lagPtsUs and 0xffffffffL)
        out.residenceUs = residenceUs
        out.matchedSeq = matchedSeq1
        out.latestSeq = latest
    }

    companion object {
        const val CAPACITY = 8192
        const val MASK = CAPACITY - 1
        const val UNMATCHED = Long.MIN_VALUE

        private fun hash(vIn: Long): Int {
            var v = vIn
            v = v xor (v ushr 33)
            v *= 0xff51afd7ed558ccdUL.toLong()
            v = v xor (v ushr 33)
            v *= 0xc4ceb9fe1a85ec53UL.toLong()
            v = v xor (v ushr 33)
            return v.toInt() and MASK
        }

        fun matched(packed: Long): Boolean = packed != UNMATCHED
        fun lagFrames(packed: Long): Int = (packed ushr 32).toInt()
        fun lagPtsUs(packed: Long): Long = packed and 0xffffffffL
    }
}
