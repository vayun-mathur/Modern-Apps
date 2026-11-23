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

    @Test fun photoHasNoExclusionWhenGateOnAndUnlatched() {
        assertEquals(
            null,
            PreviewEnginePolicy.exclusionReason(PreviewEngineInput(true, CameraMode.PHOTO)),
        )
    }

    @Test fun everyNonPhotoModeNamesItsCaptureBlocker() {
        // Preview alone is split-brain for these modes: selecting Camera2
        // preview unbinds the CameraX session their capture/analysis needs.
        val blockers = mapOf(
            CameraMode.PORTRAIT to "PORTRAIT capture",
            CameraMode.PANORAMA to "analysis bitmap",
            CameraMode.PHOTOSPHERE to "analysis bitmap",
            CameraMode.VIDEO to "CameraX Recorder",
            CameraMode.TIMELAPSE to "CameraX Recorder",
            CameraMode.CINEMATIC to "CameraX Recorder",
            CameraMode.SLOW_MO to "high-speed",
        )
        for ((mode, fragment) in blockers) {
            val reason = PreviewEnginePolicy.exclusionReason(PreviewEngineInput(true, mode))
            assertTrue(reason!!.contains(fragment), "$mode reason must name its blocker: $reason")
        }
    }

    @Test fun gateAndLatchReasonsWinOverModeBlockers() {
        assertTrue(
            PreviewEnginePolicy.exclusionReason(PreviewEngineInput(false, CameraMode.VIDEO))!!
                .contains("gate off"),
        )
        assertTrue(
            PreviewEnginePolicy.exclusionReason(
                PreviewEngineInput(true, CameraMode.VIDEO, fallbackLatched = true),
            )!!.contains("latched"),
        )
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
