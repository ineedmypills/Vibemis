package com.ineedmypills.vibemis.binding.video

import com.ineedmypills.vibemis.preferences.PreferenceConfiguration

/**
 * Static compatibility policy determining when NVIDIA latency recovery can engage.
 */
object NvidiaLatencyRecoveryEligibility {
    const val MASK_UNSUPPORTED_PACING = 1
    const val MASK_SYNC_LFR = 4
    const val MASK_FAST_VSYNC = 8

    @JvmStatic
    fun isPacingSupported(pacing: Int): Boolean = when (pacing) {
        PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
        PreferenceConfiguration.FRAME_PACING_GPU_RAW,
        PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS -> true
        else -> false
    }

    @JvmStatic
    fun staticModeMask(
        pacing: Int,
        asyncCodec: Boolean,
        syncLfrEnabled: Boolean,
        fastVsync: Boolean
    ): Int {
        var mask = if (isPacingSupported(pacing)) 0 else MASK_UNSUPPORTED_PACING
        if (!asyncCodec && syncLfrEnabled) mask = mask or MASK_SYNC_LFR
        if (fastVsync) mask = mask or MASK_FAST_VSYNC
        return mask
    }

    @JvmStatic
    fun primaryInactiveReason(mask: Int): String? = when {
        (mask and MASK_UNSUPPORTED_PACING) != 0 -> "selected frame pacing mode"
        (mask and MASK_SYNC_LFR) != 0 -> "Sync LFR"
        (mask and MASK_FAST_VSYNC) != 0 -> "FastVSync"
        else -> null
    }
}
