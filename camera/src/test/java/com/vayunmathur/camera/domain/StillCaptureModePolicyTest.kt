package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class StillCaptureModePolicyTest {

    private val zslFriendly = StillCaptureInputs(
        zslSupported = true,
        ultraHdr = false,
        ultraHdrReprocessable = false,
        autoExposure = true,
        nightMode = false,
    )

    private fun choose(inputs: StillCaptureInputs) = StillCaptureModePolicy.choose(inputs)

    @Test
    fun supportedAutoJpegUsesZsl() {
        assertEquals(StillCaptureMode.ZERO_SHUTTER_LAG, choose(zslFriendly))
    }

    @Test
    fun unsupportedCameraUsesMinimizeLatency() {
        assertEquals(StillCaptureMode.MINIMIZE_LATENCY, choose(zslFriendly.copy(zslSupported = false)))
    }

    @Test
    fun manualExposureUsesMinimizeLatency() {
        assertEquals(StillCaptureMode.MINIMIZE_LATENCY, choose(zslFriendly.copy(autoExposure = false)))
    }

    @Test
    fun nightModeUsesMinimizeLatency() {
        assertEquals(StillCaptureMode.MINIMIZE_LATENCY, choose(zslFriendly.copy(nightMode = true)))
    }

    @Test
    fun ultraHdrKeepsQualityUnlessJpegRIsReprocessable() {
        assertEquals(StillCaptureMode.MINIMIZE_LATENCY, choose(zslFriendly.copy(ultraHdr = true)))
        assertEquals(
            StillCaptureMode.ZERO_SHUTTER_LAG,
            choose(zslFriendly.copy(ultraHdr = true, ultraHdrReprocessable = true))
        )
    }

    @Test
    fun reprocessableFlagAloneDoesNotEnableUnsupportedZsl() {
        assertEquals(
            StillCaptureMode.MINIMIZE_LATENCY,
            choose(zslFriendly.copy(zslSupported = false, ultraHdr = true, ultraHdrReprocessable = true))
        )
    }
}
