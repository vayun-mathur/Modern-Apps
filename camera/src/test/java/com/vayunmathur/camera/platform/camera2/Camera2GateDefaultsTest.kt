package com.vayunmathur.camera.platform.camera2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.RecordingPath
import com.vayunmathur.camera.domain.RecordingPathInput
import com.vayunmathur.camera.domain.RecordingPathPolicy
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.CustomRecordingGate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Camera2 is the default engine; incomplete paths stay honestly on CameraX. */
@RunWith(AndroidJUnit4::class)
class Camera2GateDefaultsTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun viewModelCamera2GatesDefaultOn() {
        val v = vm()
        assertTrue(v.camera2PreviewEnabled.value)
        assertTrue(v.camera2SloMoEnabled.value)
        assertTrue(v.camera2NightEnabled.value)
        assertTrue(v.camera2RawNightEnabled.value)
    }

    @Test fun optOutSelectsCameraXAndReEnableClearsFallback() {
        val v = vm()
        v.onCamera2PreviewFailed("0")
        assertTrue(v.camera2FallbackBindKey.value != null)
        v.setCamera2PreviewEnabled(false)
        assertFalse(v.camera2PreviewEnabled.value)
        assertFalse(PreviewEnginePolicy.usesCamera2(v.camera2PreviewEnabled.value, CameraMode.PHOTO))
        v.setCamera2PreviewEnabled(true)
        assertTrue(v.camera2PreviewEnabled.value)
        assertEquals(null, v.camera2FallbackBindKey.value)
        assertTrue(PreviewEnginePolicy.usesCamera2(v.camera2PreviewEnabled.value, CameraMode.PHOTO))
    }

    @Test fun viewModelFallbackLatchIsOneShotPerBind() {
        val v = vm()
        assertTrue(v.onCamera2PreviewFailed("0"), "first failure falls back to CameraX")
        assertEquals(
            Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO),
            v.camera2FallbackBindKey.value,
        )
        assertFalse(v.onCamera2PreviewFailed("0"), "repeat failure must not re-trigger")
        v.clearCamera2Fallback()
        assertEquals(null, v.camera2FallbackBindKey.value)
        assertTrue(v.onCamera2PreviewFailed("0"), "cleared latch is a fresh bind attempt")
    }

    @Test fun videoCustomGateStaysOffUntilDeviceVerified() {
        assertFalse(CustomRecordingGate.ENABLED)
        val path = RecordingPathPolicy.select(
            RecordingPathInput(
                customPathEnabled = CustomRecordingGate.ENABLED,
                liveAdaptationWanted = true,
                hlgRequested = false,
                stabilizationRequested = false,
                codecAv1Requested = false,
                analysisBound = true,
            ),
        )
        assertEquals(RecordingPath.CAMERAX_RECORDER, path)
    }
}
