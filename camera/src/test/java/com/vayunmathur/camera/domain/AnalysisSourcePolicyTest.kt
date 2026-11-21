package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnalysisSourcePolicyTest {

    private fun input(
        enabled: Boolean = true,
        qr: Boolean = false,
        bitmap: Boolean = false,
        cheapOnly: Boolean = true,
    ) = AnalysisSourceInput(
        camera2Enabled = enabled, needsQr = qr, needsBitmapFrame = bitmap,
        cheapPlaneConsumersOnly = cheapOnly,
    )

    @Test fun defaultFlagIsCameraX() {
        assertFalse(AnalysisSourcePolicy.CAMERA2_ENABLED)
        val defaulted = AnalysisSourceInput(needsQr = false, needsBitmapFrame = false, cheapPlaneConsumersOnly = true)
        assertEquals(AnalysisSource.CAMERAX, AnalysisSourcePolicy.select(defaulted))
    }

    @Test fun cheapPlaneSessionMayUseImageReaderWhenEnabled() {
        assertEquals(AnalysisSource.CAMERA2_IMAGE_READER, AnalysisSourcePolicy.select(input()))
    }

    @Test fun qrStaysOnCameraX() {
        assertEquals(AnalysisSource.CAMERAX, AnalysisSourcePolicy.select(input(qr = true)))
    }

    @Test fun bitmapConsumersStayOnCameraX() {
        // Bokeh/Pano/Motion-Photo: segmenters and PanoramaEngine take Bitmaps.
        assertEquals(AnalysisSource.CAMERAX, AnalysisSourcePolicy.select(input(bitmap = true)))
    }

    @Test fun mixedConsumersStayOnCameraX() {
        assertEquals(AnalysisSource.CAMERAX, AnalysisSourcePolicy.select(input(cheapOnly = false)))
    }

    @Test fun disabledFlagAlwaysCameraX() {
        assertEquals(AnalysisSource.CAMERAX, AnalysisSourcePolicy.select(input(enabled = false)))
    }
}
