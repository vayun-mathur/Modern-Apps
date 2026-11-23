package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-PHOTO QR result flow on the ViewModel (no QR mode to switch to). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrInPhotoViewModelTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun defaultModeIsPhotoAndResultFlowsThere() {
        val viewModel = vm()
        assertEquals(CameraMode.PHOTO, viewModel.cameraMode.value)
        assertNull(viewModel.qrResult.value)
        viewModel.setQrResult("https://example.com/from-photo-mode")
        assertEquals("https://example.com/from-photo-mode", viewModel.qrResult.value)
        // Dismissing the chip never leaves PHOTO or blocks the shutter.
        viewModel.setQrResult(null)
        assertNull(viewModel.qrResult.value)
        assertEquals(CameraMode.PHOTO, viewModel.cameraMode.value)
    }
}
