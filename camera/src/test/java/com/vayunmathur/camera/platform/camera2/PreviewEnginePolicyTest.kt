package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PreviewEnginePolicyTest {

    @Test fun gateOffAlwaysCameraX() {
        for (mode in CameraMode.entries) {
            assertEquals(
                PreviewEngine.CAMERAX,
                PreviewEnginePolicy.select(PreviewEngineInput(false, mode)),
                "gate off must stay on CameraX for $mode",
            )
        }
    }

    @Test fun gateOnSelectsCamera2ForPhotoOnly() {
        assertEquals(
            PreviewEngine.CAMERA2,
            PreviewEnginePolicy.select(PreviewEngineInput(true, CameraMode.PHOTO)),
        )
        assertTrue(PreviewEnginePolicy.usesCamera2(true, CameraMode.PHOTO))
        for (mode in CameraMode.entries - CameraMode.PHOTO) {
            assertEquals(
                PreviewEngine.CAMERAX,
                PreviewEnginePolicy.select(PreviewEngineInput(true, mode)),
                "Phase 2 supports PHOTO only; $mode must stay on CameraX",
            )
            assertFalse(PreviewEnginePolicy.usesCamera2(true, mode))
        }
    }

    @Test fun supportedModesIsPhotoOnlyInPhase2() {
        assertEquals(setOf(CameraMode.PHOTO), PreviewEnginePolicy.supportedModes)
    }
}
