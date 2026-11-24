package com.ineedmypills.vibemis.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionScaleFactorTest {
    @Test
    fun halfResolutionDoublesHostResolution() {
        assertEquals(
            200,
            PreferenceConfiguration.calculateAutoResolutionScaleFactor(960, 540, 1920, 1080)
        )
    }

    @Test
    fun lowerResolutionCanExceedManualSliderRange() {
        assertEquals(
            300,
            PreferenceConfiguration.calculateAutoResolutionScaleFactor(1280, 720, 3840, 2160)
        )
    }

    @Test
    fun portraitStreamUsesPortraitTarget() {
        assertEquals(
            200,
            PreferenceConfiguration.calculateAutoResolutionScaleFactor(540, 960, 1920, 1080)
        )
    }

    @Test
    fun fullOrHigherStreamResolutionDoesNotDownscaleHost() {
        assertEquals(
            100,
            PreferenceConfiguration.calculateAutoResolutionScaleFactor(3840, 2160, 1920, 1080)
        )
    }

    @Test
    fun invalidDimensionsFallBackToOneHundredPercent() {
        assertEquals(
            100,
            PreferenceConfiguration.calculateAutoResolutionScaleFactor(0, 540, 1920, 1080)
        )
        assertEquals(
            100,
            PreferenceConfiguration.computeAutoScaleFactor(1920, 1080, 1920, 1080)
        )
    }

    @Test
    fun computeAutoScaleFactorMatchesCommonScreenTargets() {
        assertEquals(
            133,
            PreferenceConfiguration.computeAutoScaleFactor(1920, 1080, 2560, 1440)
        )
        assertEquals(
            150,
            PreferenceConfiguration.computeAutoScaleFactor(1280, 720, 1920, 1080)
        )
    }
}
