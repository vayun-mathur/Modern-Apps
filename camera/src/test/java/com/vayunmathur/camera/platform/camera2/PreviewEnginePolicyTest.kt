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

    @Test fun ringGatesDefaultOn() {
        assertTrue(ReprocessRingGate.ENABLED)
        assertTrue(RawRingGate.ENABLED)
    }

    @Test fun optOutStaysCameraX() {
        // User opt-out (Settings Camera2 engine toggle off) -> CameraX.
        assertFalse(PreviewEnginePolicy.usesCamera2(false, CameraMode.PHOTO))
    }

    @Test fun fallbackLatchSelectsCameraXForLatchedBind() {
        assertFalse(PreviewEnginePolicy.usesCamera2(true, CameraMode.PHOTO, fallbackLatched = true))
        assertEquals(
            PreviewEngine.CAMERAX,
            PreviewEnginePolicy.select(PreviewEngineInput(true, CameraMode.PHOTO, fallbackLatched = true)),
        )
    }

    @Test fun fallbackLatchIsOneShotPerBindKey() {
        val latch = Camera2FallbackLatch()
        val key = Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO)
        assertFalse(latch.isLatched(key))
        assertTrue(latch.recordFailure(key), "first failure must trigger the CameraX fallback")
        assertTrue(latch.isLatched(key))
        assertFalse(latch.recordFailure(key), "second failure for same key must not re-trigger")
        // A different lens/mode is a fresh bind attempt.
        val other = Camera2FallbackLatch.bindKey("1", CameraMode.PHOTO)
        assertFalse(latch.isLatched(other))
        assertTrue(latch.recordFailure(other))
        // Manual retry / re-enable clears the latch.
        latch.reset()
        assertFalse(latch.isLatched(key))
        assertTrue(latch.recordFailure(key))
        latch.clear(key)
        assertFalse(latch.isLatched(key))
    }
}
