package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Slo-mo: Camera2 only when every honest condition holds, else UNSUPPORTED. */
class SloMoEnginePolicyTest {

    private fun readyInput(
        mode: CameraMode = CameraMode.SLOW_MO,
        lensFacing: LensFacing? = LensFacing.BACK,
        hasConstrainedHighSpeed: Boolean = true,
        hasSelection: Boolean = true,
        encoderSupportsCaptureRate: Boolean = true,
        recordingEngineReady: Boolean = true,
    ) = SloMoEngineInput(
        mode = mode,
        lensFacing = lensFacing,
        hasConstrainedHighSpeed = hasConstrainedHighSpeed,
        hasSelection = hasSelection,
        encoderSupportsCaptureRate = encoderSupportsCaptureRate,
        recordingEngineReady = recordingEngineReady,
    )

    @Test fun allConditionsMetSelectsCamera2() {
        val input = readyInput()
        assertEquals(SloMoEngine.CAMERA2, SloMoEnginePolicy.select(input))
        assertTrue(SloMoEnginePolicy.usesCamera2(input))
        assertNull(SloMoEnginePolicy.exclusionReason(input))
    }

    @Test fun everyMissingConditionIsUnsupportedWithReason() {
        val cases = listOf(
            readyInput(mode = CameraMode.VIDEO) to "not SLOW_MO",
            readyInput(lensFacing = LensFacing.FRONT) to "back-camera only",
            readyInput(hasConstrainedHighSpeed = false) to "CONSTRAINED_HIGH_SPEED_VIDEO",
            readyInput(hasSelection = false) to "no fixed HFR",
            readyInput(encoderSupportsCaptureRate = false) to "no encoder supports",
            readyInput(recordingEngineReady = false) to "not coupled/device-verified",
        )
        for ((input, fragment) in cases) {
            assertEquals(SloMoEngine.UNSUPPORTED, SloMoEnginePolicy.select(input), fragment)
            assertFalse(SloMoEnginePolicy.usesCamera2(input), fragment)
            assertTrue(
                SloMoEnginePolicy.exclusionReason(input)!!.contains(fragment),
                "reason for $fragment",
            )
        }
    }

    @Test fun noOffSwitchUnsupportedIsTheOnlyAlternative() {
        // With no conditions met the answer is UNSUPPORTED, never another engine.
        val input = readyInput(
            hasConstrainedHighSpeed = false, hasSelection = false,
            encoderSupportsCaptureRate = false, recordingEngineReady = false,
        )
        assertEquals(SloMoEngine.UNSUPPORTED, SloMoEnginePolicy.select(input))
    }

    @Test fun liveReadinessFlagSelectsCamera2() {
        assertTrue(SloMoRecordingReadiness.recordingEngineReady)
        assertEquals(
            SloMoEngine.CAMERA2,
            SloMoEnginePolicy.select(
                readyInput(recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady),
            ),
        )
    }
}
