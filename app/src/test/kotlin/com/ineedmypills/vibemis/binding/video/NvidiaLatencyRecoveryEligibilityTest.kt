package com.ineedmypills.vibemis.binding.video

import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NvidiaLatencyRecoveryEligibilityTest {
    @Test
    fun supportsValidatedPacingModes() {
        assertEquals(
            0,
            NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                asyncCodec = false,
                syncLfrEnabled = false,
                fastVsync = false
            )
        )
        assertEquals(
            0,
            NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_GPU_RAW,
                asyncCodec = true,
                syncLfrEnabled = true,
                fastVsync = false
            )
        )
        assertEquals(
            0,
            NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS,
                asyncCodec = true,
                syncLfrEnabled = false,
                fastVsync = false
            )
        )
        assertNull(NvidiaLatencyRecoveryEligibility.primaryInactiveReason(0))
    }

    @Test
    fun blocksKnownConflictsWithoutChangingUserPreferences() {
        val balancedMask = NvidiaLatencyRecoveryEligibility.staticModeMask(
            PreferenceConfiguration.FRAME_PACING_BALANCED,
            asyncCodec = true,
            syncLfrEnabled = false,
            fastVsync = false
        )
        assertNotEquals(0, balancedMask)
        assertNotNull(NvidiaLatencyRecoveryEligibility.primaryInactiveReason(balancedMask))

        val syncLfrMask = NvidiaLatencyRecoveryEligibility.staticModeMask(
            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
            asyncCodec = false,
            syncLfrEnabled = true,
            fastVsync = false
        )
        assertNotEquals(0, syncLfrMask)
        assertEquals("Sync LFR", NvidiaLatencyRecoveryEligibility.primaryInactiveReason(syncLfrMask))

        val fastVsyncMask = NvidiaLatencyRecoveryEligibility.staticModeMask(
            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
            asyncCodec = true,
            syncLfrEnabled = false,
            fastVsync = true
        )
        assertNotEquals(0, fastVsyncMask)
        assertEquals("FastVSync", NvidiaLatencyRecoveryEligibility.primaryInactiveReason(fastVsyncMask))
    }
}
