package com.hanfengruyue.pocketrdp.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicResolutionCapTest {
    @Test
    fun uncappedSizeIsUnchanged() {
        assertEquals(2560 to 1440, capDynamicResolutionToMax(2560, 1440, 0))
    }

    @Test
    fun capAppliesToLandscapeSixteenByNine() {
        assertEquals(1920 to 1080, capDynamicResolutionToMax(2560, 1440, 1080))
    }

    @Test
    fun capAppliesToPortraitSixteenByNine() {
        assertEquals(1080 to 1920, capDynamicResolutionToMax(1440, 2560, 1080))
    }

    @Test
    fun smallerSizeIsNotUpscaled() {
        assertEquals(1280 to 720, capDynamicResolutionToMax(1280, 720, 1080))
    }

    @Test
    fun newFourKCapIsAccepted() {
        assertEquals(3840 to 2160, capDynamicResolutionToMax(5120, 2880, 2160))
    }

    @Test
    fun cappedSizeRoundsDownToEvenDimensions() {
        assertEquals(914 to 1920, capDynamicResolutionToMax(1001, 2101, 1080))
    }
}
