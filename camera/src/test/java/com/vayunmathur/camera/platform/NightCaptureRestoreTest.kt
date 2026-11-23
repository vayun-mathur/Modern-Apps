package com.vayunmathur.camera.platform
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*
/** Night restore contract after CameraX extension removal: teardown/mode-switch
 *  ownership (sessionTeardownCount) + surviving night progress helpers. */
@RunWith(AndroidJUnit4::class)
class NightCaptureRestoreTest {
    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())
    @Test fun progressLabelAndFractionClamp() {
        assertEquals("Capturing 3/8", nightCaptureProgressLabel(3,8)); assertEquals("Capturing", nightCaptureProgressLabel(1,0))
        assertEquals("Capturing 8/8", nightCaptureProgressLabel(99,8)); assertEquals(0.375f, nightCaptureProgressFraction(3,8)); assertEquals(0f, nightCaptureProgressFraction(1,0))
    }
    @Test fun showNightProgressPublishesToOverlayFlows() {
        val v=vm(); v.showNightCaptureProgress(4); assertEquals(nightCaptureProgressLabel(4, NightCaptureEngine.NIGHT_BURST_COUNT), v.longExposureRemainingMutable.value)
        v.showNightMerging(); assertEquals(NIGHT_MERGING_LABEL, v.longExposureRemainingMutable.value); assertEquals(1f, v.longExposureProgressMutable.value)
    }
    @Test fun teardownAdvancesOwnershipAndClearsCaptureState() {
        val v=vm(); val before=v.sessionTeardownCount; v.isCapturingMutable.value=true; v.teardownSession()
        assertTrue(v.sessionTeardownCount>before); assertFalse(v.isCapturingMutable.value)
        // A capturePhoto after teardown has no session: must not claim the shutter.
        v.capturePhoto(); assertFalse(v.isCapturingMutable.value)
    }
    @Test fun modeSwitchAlsoInvalidatesStaleCapture() {
        val v=vm(); v.switchCameraMode(CameraMode.VIDEO); assertEquals(CameraMode.VIDEO, v.cameraModeMutable.value)
        v.capturePhoto(); assertFalse(v.isCapturingMutable.value)
    }
}
