package com.vayunmathur.camera.platform.camera2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.STARTUP_UNRESOLVED_LENS_ID
import com.vayunmathur.camera.platform.clearCamera2Fallback
import com.vayunmathur.camera.platform.clearCamera2NightFallback
import com.vayunmathur.camera.platform.onCamera2NightPreviewFailed
import com.vayunmathur.camera.platform.onCamera2PreviewFailed
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Camera2-only contract: there is no engine off switch anywhere. Policies
 * answer Camera2 for every supported condition, unsupported conditions
 * yield UNSUPPORTED / a dedicated route with a reason, and a failed bind
 * latches one-shot for its bind key until a manual retry clears it.
 */
@RunWith(AndroidJUnit4::class)
class Camera2GateDefaultsTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun previewHasNoOffSwitch() {
        assertEquals(CameraMode.entries.toSet(), PreviewEnginePolicy.supportedModes)
        for (mode in CameraMode.entries) {
            assertTrue(PreviewEnginePolicy.usesCamera2(mode), "$mode")
            assertNull(PreviewEnginePolicy.exclusionReason(mode), "$mode")
            assertFalse(PreviewEnginePolicy.usesCamera2(mode, fallbackLatched = true), "$mode")
        }
    }

    @Test fun stillHasNoOffSwitch() {
        val plain = StillEngineInput(mode = CameraMode.PHOTO, hasJpegOutput = true)
        assertEquals(StillEngine.CAMERA2, StillEnginePolicy.select(plain))
        assertTrue(StillEnginePolicy.usesCamera2(plain))
        // Unsupported-for-plain conditions route within Camera2, never out of it.
        assertEquals(
            StillEngine.CAMERA2_ROUTED,
            StillEnginePolicy.select(plain.copy(nightRoute = true)),
        )
    }

    @Test fun sloMoRecordingEngineIsReadyWithNoOffSwitch() {
        assertTrue(SloMoRecordingReadiness.recordingEngineReady)
        assertEquals(emptyList<String>(), SloMoRecordingReadiness.remainingPieces())
        val ready = SloMoEngineInput(
            mode = CameraMode.SLOW_MO, lensFacing = LensFacing.BACK,
            hasConstrainedHighSpeed = true, hasSelection = true,
            encoderSupportsCaptureRate = true,
            recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady,
        )
        assertEquals(SloMoEngine.CAMERA2, SloMoEnginePolicy.select(ready))
        assertEquals(
            SloMoEngine.UNSUPPORTED,
            SloMoEnginePolicy.select(ready.copy(hasConstrainedHighSpeed = false)),
        )
    }

    @Test fun nightExtensionHasNoOffSwitch() {
        val ready = ExtensionsEngineInput(
            mode = CameraMode.PHOTO, camera2ProbeOk = true, failureCacheClear = true,
        )
        assertEquals(NightEngine.CAMERA2_EXTENSION, ExtensionsEnginePolicy.select(ready))
        assertEquals(
            NightEngine.OWN_BURST,
            ExtensionsEnginePolicy.select(ready.copy(camera2ProbeOk = false)),
        )
    }

    @Test fun viewModelFallbackLatchIsOneShotPerBind() {
        val v = vm()
        // Seeded contract: before background reconciliation the current
        // selection is the startup-unresolved placeholder, so the latch
        // keys on it. The latch follows the current selection throughout.
        assertEquals(STARTUP_UNRESOLVED_LENS_ID, v.selectedLens.value?.logicalCameraId)
        assertTrue(v.onCamera2PreviewFailed("0"), "first failure latches the bind error")
        assertEquals(
            Camera2FallbackLatch.bindKey(STARTUP_UNRESOLVED_LENS_ID, CameraMode.PHOTO),
            v.camera2FallbackBindKey.value,
        )
        assertFalse(v.onCamera2PreviewFailed("0"), "repeat failure must not re-trigger")
        v.clearCamera2Fallback()
        assertEquals(null, v.camera2FallbackBindKey.value)
        assertTrue(v.onCamera2PreviewFailed("0"), "cleared latch is a fresh bind attempt")
        assertEquals(
            Camera2FallbackLatch.bindKey(STARTUP_UNRESOLVED_LENS_ID, CameraMode.PHOTO),
            v.camera2FallbackBindKey.value,
        )
    }

    @Test fun viewModelNightFallbackLatchFollowsCurrentSelection() {
        val v = vm()
        // Same seeded contract as the preview latch: the night latch keys
        // on the current (startup-unresolved) selection, not the stale id.
        v.onCamera2NightPreviewFailed("0")
        assertEquals(
            Camera2FallbackLatch.bindKey(STARTUP_UNRESOLVED_LENS_ID, CameraMode.PHOTO),
            v.camera2NightFallbackBindKey.value,
        )
        v.onCamera2NightPreviewFailed("0")
        assertEquals(
            Camera2FallbackLatch.bindKey(STARTUP_UNRESOLVED_LENS_ID, CameraMode.PHOTO),
            v.camera2NightFallbackBindKey.value,
        )
        v.clearCamera2NightFallback()
        assertEquals(null, v.camera2NightFallbackBindKey.value)
    }
}
