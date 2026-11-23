package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Night policy matrix: Camera2 extension when PHOTO + probe ok + failure
 * cache clear; else our own burst. There is no other extension engine.
 */
class ExtensionsEnginePolicyTest {

    private fun input(
        mode: CameraMode = CameraMode.PHOTO,
        probeOk: Boolean = true,
        cacheClear: Boolean = true,
    ) = ExtensionsEngineInput(
        mode = mode,
        camera2ProbeOk = probeOk,
        failureCacheClear = cacheClear,
    )

    @Test fun camera2WinsWhenProbeAndCachePass() {
        val chosen = input()
        assertEquals(NightEngine.CAMERA2_EXTENSION, ExtensionsEnginePolicy.select(chosen))
        assertTrue(ExtensionsEnginePolicy.usesCamera2Extension(chosen))
        assertNull(ExtensionsEnginePolicy.camera2ExclusionReason(chosen))
    }

    @Test fun noOffSwitchProbeAndCacheAlwaysWinInPhoto() {
        // Repeated decisions are stable: no gate can demote a passing probe.
        repeat(3) {
            assertEquals(NightEngine.CAMERA2_EXTENSION, ExtensionsEnginePolicy.select(input()))
        }
    }

    @Test fun probeFailureFallsToOwnBurst() {
        val chosen = input(probeOk = false)
        assertEquals(NightEngine.OWN_BURST, ExtensionsEnginePolicy.select(chosen))
        assertFalse(ExtensionsEnginePolicy.usesCamera2Extension(chosen))
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(chosen)!!.contains("probe failed"),
        )
    }

    @Test fun nonPhotoModeFallsToOwnBurst() {
        val chosen = input(mode = CameraMode.VIDEO)
        assertEquals(NightEngine.OWN_BURST, ExtensionsEnginePolicy.select(chosen))
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(chosen)!!.contains("not PHOTO"),
        )
    }

    @Test fun warmFailureCacheSuppressesExtension() {
        val warm = input(cacheClear = false)
        assertEquals(NightEngine.OWN_BURST, ExtensionsEnginePolicy.select(warm))
        assertFalse(ExtensionsEnginePolicy.usesCamera2Extension(warm))
        assertTrue(
            ExtensionsEnginePolicy.camera2ExclusionReason(warm)!!.contains("failure cache"),
        )
        // Cache wins even over a passing probe in PHOTO.
        assertEquals(NightEngine.OWN_BURST, ExtensionsEnginePolicy.select(input(cacheClear = false)))
    }

    @Test fun strengthShownOnlyOnCamera2Extension() {
        assertTrue(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERA2_EXTENSION, 42))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.OWN_BURST, 42))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.CAMERA2_EXTENSION, null))
        assertFalse(ExtensionsEnginePolicy.showsExtensionStrength(NightEngine.OWN_BURST, null))
    }
}
