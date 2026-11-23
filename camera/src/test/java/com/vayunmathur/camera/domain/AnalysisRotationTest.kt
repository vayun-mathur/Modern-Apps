package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [AnalysisRotation] against the known device-orientation table
 * (CameraX `imageInfo.rotationDegrees` semantics): typical phone
 * sensors are back=90, front=270; portrait display rotation is 0.
 */
class AnalysisRotationTest {

    @Test fun backLensKnownOrientations() {
        // Usual back lens (sensor 90) held portrait: frame needs 90 CW.
        assertEquals(90, AnalysisRotation.rotationDegrees(90, 0, LensFacing.BACK))
        // Rotated to landscape (display 90): already upright.
        assertEquals(0, AnalysisRotation.rotationDegrees(90, 90, LensFacing.BACK))
        // Upside down (display 180).
        assertEquals(270, AnalysisRotation.rotationDegrees(90, 180, LensFacing.BACK))
        // Other landscape (display 270).
        assertEquals(180, AnalysisRotation.rotationDegrees(90, 270, LensFacing.BACK))
    }

    @Test fun frontLensKnownOrientations() {
        // Usual front lens (sensor 270) held portrait.
        assertEquals(270, AnalysisRotation.rotationDegrees(270, 0, LensFacing.FRONT))
        // Landscape (display 90): front formula adds, wrapping to upright.
        assertEquals(0, AnalysisRotation.rotationDegrees(270, 90, LensFacing.FRONT))
        assertEquals(90, AnalysisRotation.rotationDegrees(270, 180, LensFacing.FRONT))
        assertEquals(180, AnalysisRotation.rotationDegrees(270, 270, LensFacing.FRONT))
    }

    @Test fun backFormulaMatchesStillPlannerForBackLens() {
        // The still path's planner is the back-lens half of this formula;
        // analysis must agree with it for every display rotation.
        for (display in listOf(0, 90, 180, 270)) {
            val still = com.vayunmathur.camera.platform.camera2.StillCapturePlanner
                .jpegRotationDegrees(90, display)
            assertEquals(still, AnalysisRotation.rotationDegrees(90, display, LensFacing.BACK))
        }
    }

    @Test fun normalizesUnnormalizedInputs() {
        assertEquals(90, AnalysisRotation.rotationDegrees(450, 360, LensFacing.BACK))
        assertEquals(90, AnalysisRotation.rotationDegrees(90, -360, LensFacing.BACK))
    }

    @Test fun fullFrameCropIsIdentity() {
        val crop = FrameCrop.fullFrame(640, 480)
        assertTrue(crop.isFullFrame(640, 480))
        assertEquals(640, crop.width)
        assertEquals(480, crop.height)
    }
}
