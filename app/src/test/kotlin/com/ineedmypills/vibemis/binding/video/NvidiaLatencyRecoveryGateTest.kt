package com.ineedmypills.vibemis.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaLatencyRecoveryGateTest {
    private fun configured(now: Long): NvidiaLatencyRecoveryGate {
        return NvidiaLatencyRecoveryGate().apply {
            mode(0, 60f, now)
        }
    }

    @Test
    fun structuralRecoveryStopsAtExactZero() {
        val start = 10_000_000_000L
        val gate = configured(start)
        var decision: NvidiaLatencyRecoveryGate.Decision? = null
        var i = 0
        while (i < 40 && decision == null) {
            decision = gate.decide(start + i * FRAME_NS, true, false, true, 3, 50_000, 35_000)
            i++
        }
        assertNotNull(decision)
        assertTrue(decision!!.drop)
        gate.done(decision, true)

        for (lag in 2 downTo 1) {
            val drop = gate.decide(
                start + (50 - lag) * FRAME_NS,
                true,
                false,
                true,
                lag,
                lag * 17_000L,
                30_000
            )
            assertNotNull(drop)
            assertTrue(drop!!.drop)
            gate.done(drop, true)
        }

        val end = gate.decide(start + 51 * FRAME_NS, true, false, true, 0, 0, 2_000)
        assertNotNull(end)
        assertFalse(end!!.drop)
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END, end.eventKind)
    }

    @Test
    fun timeWeightedOccupancyRejectsSampleCountFalsePositive() {
        var now = 20_000_000_000L
        val gate = configured(now)
        var hit: NvidiaLatencyRecoveryGate.Decision? = null
        var cycle = 0
        while (cycle < 100 && hit == null) {
            var j = 0
            while (j < 4 && hit == null) {
                val positive = j < 3 // 75% samples, but only 25% elapsed time positive
                val dt = if (positive) 5_000_000L else 45_000_000L
                if (!(cycle == 0 && j == 0)) now += dt
                hit = gate.decide(
                    now,
                    true,
                    false,
                    true,
                    if (positive) 4 else 0,
                    if (positive) 66_000 else 0,
                    if (positive) 70_000 else 2_000
                )
                j++
            }
            cycle++
        }
        assertNull(hit)
    }

    @Test
    fun intermittentZeroDoesNotErasePersistentStructuralDebt() {
        val start = 30_000_000_000L
        val gate = configured(start)
        var hit: NvidiaLatencyRecoveryGate.Decision? = null
        var i = 0
        while (i < 160 && hit == null) {
            val zero = (i % 5) == 4
            hit = gate.decide(
                start + i * FRAME_NS,
                true,
                false,
                true,
                if (zero) 0 else 1,
                if (zero) 0 else 17_000,
                if (zero) 20_000 else 30_000
            )
            i++
        }
        assertNotNull(hit)
        assertTrue(hit!!.drop)
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_DROP, hit.eventKind)
        assertTrue(hit.lagFrames > 0)
    }

    @Test
    fun maxDropLimitRemainsBoundedAtTwelve() {
        val start = 40_000_000_000L
        val gate = configured(start)
        var first: NvidiaLatencyRecoveryGate.Decision? = null
        var i = 0
        while (i < 40 && first == null) {
            first = gate.decide(start + i * FRAME_NS, true, false, true, 13, 220_000, 230_000)
            i++
        }
        assertNotNull(first)
        gate.done(first, true)
        for (n in 0 until 11) {
            val lag = 12 - n
            val drop = gate.decide(
                start + (50 + n) * FRAME_NS,
                true,
                false,
                true,
                lag,
                lag * 17_000L,
                lag * 17_000L + 1_000
            )
            assertNotNull(drop)
            assertTrue(drop!!.drop)
            gate.done(drop, true)
        }
        val end = gate.decide(start + 62 * FRAME_NS, true, false, true, 1, 17_000, 18_000)
        assertNotNull(end)
        assertFalse(end!!.drop)
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END, end.eventKind)
        assertTrue(gate.result().startsWith("CATCHUP_LIMIT_"))
    }

    @Test
    fun losingEligibilityFencesOldEpisode() {
        val start = 50_000_000_000L
        val gate = configured(start)
        var hit: NvidiaLatencyRecoveryGate.Decision? = null
        var i = 0
        while (i < 40 && hit == null) {
            hit = gate.decide(start + i * FRAME_NS, true, false, true, 3, 50_000, 35_000)
            i++
        }
        assertNotNull(hit)
        gate.done(hit, true)

        gate.mode(1, 60f, start + 45 * FRAME_NS)
        gate.mode(0, 60f, start + 60 * FRAME_NS)
        assertNull(gate.decide(start + 61 * FRAME_NS, true, false, true, 2, 34_000, 35_000))
    }

    companion object {
        private const val FRAME_NS = 16_666_667L
    }
}
