package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Phase-6 policy matrix: Camera2 extension only when gate on + probe ok +
 * failure-cache clear; else CameraX extension; else own burst. Pure JVM —
 * the policy carries no Android types beyond CameraMode.
 */
class ExtensionsEnginePolicyTest {

    private fun input(
        gate: Boolean = true,
        mode: CameraMode = CameraMode.PHOTO,
        probeOk: Boolean = true,
        cacheClear: Boolean = true,
        cameraXUsable: Boolean = true,
    ) = ExtensionsEngineInput(
        camera2GateOn = gate,
        mode = mode,
        camera2ProbeOk = probeOk,
        failureCacheClear = cacheClear,
        cameraXExtensionUsable = cameraXUsable,
    )

    @Test fun gateOnByDefaultConstant() {
        assertTrue(NightCamera2Gate.ENABLED)
    }

    @Test fun camera2WinsWhenGateProbeAndCacheAllPass() {
        val chosen = input()
        assertEquals(NightEngine.CAMERA2_EXTENSION, ExtensionsEnginePolicy.select(chosen))
        assertTrue(ExtensionsEnginePolicy.usesCamera2Extension(chosen))
        assertNull(ExtensionsEnginePolicy.camera2ExclusionReason(chosen))
    }

    @Test fun gateOffFallsToCameraXExtension() {
        assertEquals(
            NightEngine.CAMERAX_EXTENSION,
            ExtensionsEnginePolicy.select(input(gate = false)),
        )
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(input(gate = false))!!
                .contains("gate off"),
        )
    }

    @Test fun probeFailureFallsToCameraXExtension() {
        assertEquals(
            NightEngine.CAMERAX_EXTENSION,
            ExtensionsEnginePolicy.select(input(probeOk = false)),
        )
    }

    @Test fun nonPhotoModeFallsToCameraXExtension() {
        assertEquals(
            NightEngine.CAMERAX_EXTENSION,
            ExtensionsEnginePolicy.select(input(mode = CameraMode.VIDEO)),
        )
    }

    @Test fun noExtensionAnywhereFallsToOwnBurst() {
        assertEquals(
            NightEngine.OWN_BURST,
            ExtensionsEnginePolicy.select(
                input(gate = false, probeOk = false, cameraXUsable = false),
            ),
        )
        assertEquals(
            NightEngine.OWN_BURST,
            ExtensionsEnginePolicy.select(input(cameraXUsable = false, probeOk = false)),
        )
    }

    @Test fun warmFailureCacheSuppressesBothExtensions() {
        // A recorded failure (either engine, same store) makes at most one
        // attempt per TTL: even with gate+probe green and CameraX reporting
        // usable in isolation, the decision is the own burst.
        val warm = input(cacheClear = false)
        assertEquals(NightEngine.OWN_BURST, ExtensionsEnginePolicy.select(warm))
        assertFalse(ExtensionsEnginePolicy.usesCamera2Extension(warm))
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(warm)!!.contains("failure cache"),
        )
        // Cache check precedes the gate check: reason stays the cache.
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(input(gate = false, cacheClear = false))!!
                .contains("failure cache"),
        )
    }

    @Test fun strengthShownOnlyOnCameraXExtension() {
        assertTrue(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERAX_EXTENSION, 42))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERA2_EXTENSION, 42))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.OWN_BURST, 42))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERAX_EXTENSION, null))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERA2_EXTENSION, null))
    }
}
