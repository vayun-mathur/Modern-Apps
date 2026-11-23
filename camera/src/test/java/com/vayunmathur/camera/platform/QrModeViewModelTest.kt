package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QR mode switching and result flow on the ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrModeViewModelTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun switchToQrModePublishesMode() {
        val viewModel = vm()
        viewModel.switchCameraMode(CameraMode.QR)
        assertEquals(CameraMode.QR, viewModel.cameraMode.value)
        viewModel.switchCameraMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, viewModel.cameraMode.value)
    }

    @Test
    fun qrResultRoundTripsThroughSharedFlow() {
        val viewModel = vm()
        assertNull(viewModel.qrResult.value)
        viewModel.setQrResult("https://example.com/from-qr-mode")
        assertEquals("https://example.com/from-qr-mode", viewModel.qrResult.value)
        viewModel.setQrResult(null)
        assertNull(viewModel.qrResult.value)
    }
}
