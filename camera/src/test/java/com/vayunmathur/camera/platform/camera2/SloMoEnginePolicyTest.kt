package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Phase-5 gating: Camera2 slo-mo only when every honest condition holds. */
class SloMoEnginePolicyTest {

    private fun readyInput(
        camera2Enabled: Boolean = true,
        mode: CameraMode = CameraMode.SLOW_MO,
        lensFacing: LensFacing? = LensFacing.BACK,
        hasConstrainedHighSpeed: Boolean = true,
        hasSelection: Boolean = true,
        encoderSupportsCaptureRate: Boolean = true,
        recordingEngineReady: Boolean = true,
    ) = SloMoEngineInput(
        camera2Enabled = camera2Enabled,
        mode = mode,
        lensFacing = lensFacing,
        hasConstrainedHighSpeed = hasConstrainedHighSpeed,
        hasSelection = hasSelection,
        encoderSupportsCaptureRate = encoderSupportsCaptureRate,
        recordingEngineReady = recordingEngineReady,
    )

    @Test fun gateOffAlwaysCameraX() {
        assertEquals(SloMoEngine.CAMERAX, SloMoEnginePolicy.select(readyInput(camera2Enabled = false)))
        assertTrue(SloMoEnginePolicy.exclusionReason(readyInput(camera2Enabled = false))!!.contains("gate off"))
    }

    @Test fun masterGateConstantIsOnButRecordingStaysCameraXUntilReady() {
        assertTrue(SloMoCamera2Gate.ENABLED, "Camera2 is the default; slo-mo decision is evaluated")
        // The recording engine is not coupled/device-verified yet, so even
        // with the gate on the honest selection remains CameraX.
        assertEquals(SloMoEngine.CAMERAX, SloMoEnginePolicy.select(readyInput(recordingEngineReady = false)))
    }

    @Test fun allConditionsMetSelectsCamera2() {
        val input = readyInput()
        assertEquals(SloMoEngine.CAMERA2, SloMoEnginePolicy.select(input))
        assertTrue(SloMoEnginePolicy.usesCamera2(input))
        assertNull(SloMoEnginePolicy.exclusionReason(input))
    }

    @Test fun everyMissingConditionStaysOnCameraX() {
        val cases = listOf(
            readyInput(mode = CameraMode.VIDEO) to "not SLOW_MO",
            readyInput(lensFacing = LensFacing.FRONT) to "back-camera only",
            readyInput(hasConstrainedHighSpeed = false) to "CONSTRAINED_HIGH_SPEED_VIDEO",
            readyInput(hasSelection = false) to "no fixed HFR",
            readyInput(encoderSupportsCaptureRate = false) to "no encoder supports",
            readyInput(recordingEngineReady = false) to "not coupled/device-verified",
        )
        for ((input, fragment) in cases) {
            assertEquals(SloMoEngine.CAMERAX, SloMoEnginePolicy.select(input), fragment)
            assertFalse(SloMoEnginePolicy.usesCamera2(input), fragment)
            assertTrue(
                SloMoEnginePolicy.exclusionReason(input)!!.contains(fragment),
                "reason for $fragment",
            )
        }
    }

    @Test fun liveReadinessFlagStillKeepsRecordingOnCameraX() {
        // Pieces 1-5 and 7 are done in code, 6 (device verification) is
        // open, so the flag callers should feed the policy must still
        // select CameraX.
        assertEquals(
            SloMoEngine.CAMERAX,
            SloMoEnginePolicy.select(readyInput(recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady)),
        )
    }

    @Test fun gateOffReasonWinsOverOtherExclusions() {
        // The gate is reported first so a disabled path never looks like a
        // device-capability failure in logs.
        val input = readyInput(camera2Enabled = false, hasConstrainedHighSpeed = false)
        assertTrue(SloMoEnginePolicy.exclusionReason(input)!!.contains("gate off"))
    }
}
