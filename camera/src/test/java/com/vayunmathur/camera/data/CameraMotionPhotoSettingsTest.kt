package com.vayunmathur.camera.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.CameraViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Motion Photo on/off persistence: default on, explicit off survives a new ViewModel. */
@RunWith(AndroidJUnit4::class)
class CameraMotionPhotoSettingsTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    /**
     * Waits until the setter's async DataStore write is visible in the
     * synchronous mirror. The setter launches on `viewModelScope` (main), so
     * the main looper is idled between polls instead of blocked on.
     */
    private fun awaitStored(viewModel: CameraViewModel, expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.ds.getString(KEY) != expected) {
            assertTrue(System.currentTimeMillis() < deadline, "stored $KEY never became $expected")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(expected, viewModel.ds.getString(KEY))
    }

    @Test
    fun motionPhotoEnabledDefaultsOnAndPersistsOffAndOn() {
        val first = vm()
        try {
            assertTrue(first.motionPhotoEnabled.value, "default must be on")

            first.setMotionPhotoEnabled(false)
            assertFalse(first.motionPhotoEnabled.value)
            awaitStored(first, "false")
            // A fresh ViewModel loads the persisted off state at startup.
            assertFalse(vm().motionPhotoEnabled.value)
        } finally {
            // Leave the shared store on for other tests, with the restore
            // landed rather than only launched.
            first.setMotionPhotoEnabled(true)
            awaitStored(first, "true")
        }
        assertTrue(vm().motionPhotoEnabled.value)
    }

    private companion object {
        const val KEY = "camera_motion_photo_enabled"
    }
}
