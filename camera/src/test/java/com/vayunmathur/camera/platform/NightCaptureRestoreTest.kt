package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the frozen/black preview after a mode switch during a
 * night-extension capture: captureNightPhotoExtension() used to restore the
 * photo session unconditionally in its finally, unbinding whatever session
 * the UI effect had just bound for the new mode.
 */
@RunWith(AndroidJUnit4::class)
class NightCaptureRestoreTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun restoreAppliesWhenCaptureStillOwnsSession() {
        assertTrue(
            nightCaptureRestoreApplies(
                CameraMode.PHOTO, CameraMode.PHOTO, startTeardownCount = 3, currentTeardownCount = 3
            )
        )
    }

    @Test fun restoreSkippedAfterModeSwitchDuringCapture() {
        for (mode in listOf(CameraMode.VIDEO, CameraMode.PORTRAIT, CameraMode.PANORAMA, CameraMode.SLOW_MO)) {
            assertFalse(
                nightCaptureRestoreApplies(
                    CameraMode.PHOTO, mode, startTeardownCount = 3, currentTeardownCount = 3
                ),
                "restore must not run after switching to $mode"
            )
        }
    }

    @Test fun restoreSkippedAfterInterveningTeardown() {
        // Same mode, but the session effect tore down (lens flip, backgrounding,
        // codec rebind): its fresh bind owns the session now.
        assertFalse(
            nightCaptureRestoreApplies(
                CameraMode.PHOTO, CameraMode.PHOTO, startTeardownCount = 3, currentTeardownCount = 4
            )
        )
    }

    @Test fun restoreNeverAppliesOutsidePhoto() {
        assertFalse(
            nightCaptureRestoreApplies(
                CameraMode.PORTRAIT, CameraMode.PORTRAIT, startTeardownCount = 0, currentTeardownCount = 0
            )
        )
    }

    @Test fun teardownSessionAdvancesOwnershipToken() {
        val viewModel = vm()
        val before = viewModel.sessionTeardownCount
        viewModel.teardownSession()
        assertTrue(viewModel.sessionTeardownCount > before)
        // A restore decision snapshotted before the teardown must now refuse.
        assertFalse(
            nightCaptureRestoreApplies(
                CameraMode.PHOTO, viewModel.cameraModeMutable.value,
                startTeardownCount = before, currentTeardownCount = viewModel.sessionTeardownCount
            )
        )
    }

    @Test fun switchCameraModeInvalidatesRestoreDecision() {
        val viewModel = vm()
        val startMode = viewModel.cameraModeMutable.value
        val startCount = viewModel.sessionTeardownCount
        assertTrue(
            nightCaptureRestoreApplies(
                startMode, viewModel.cameraModeMutable.value,
                startTeardownCount = startCount, currentTeardownCount = viewModel.sessionTeardownCount
            )
        )
        viewModel.switchCameraMode(CameraMode.VIDEO)
        assertFalse(
            nightCaptureRestoreApplies(
                startMode, viewModel.cameraModeMutable.value,
                startTeardownCount = startCount, currentTeardownCount = viewModel.sessionTeardownCount
            )
        )
    }
}
