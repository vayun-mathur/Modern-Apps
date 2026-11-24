package com.vayunmathur.camera.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.MotionPhotoDuration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The single Motion Photo setting: default on at 3s, Off and lengths persist
 * under one key, and the retired toggle+length pair migrates cleanly.
 */
@RunWith(AndroidJUnit4::class)
class CameraMotionPhotoSettingsTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    /**
     * Waits until the setter's async DataStore write is visible in the
     * synchronous mirror. The setter launches on `viewModelScope` (main), so
     * the main looper is idled between polls instead of blocked on.
     */
    private fun awaitStored(viewModel: CameraViewModel, key: String, expected: String?) {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.ds.getString(key) != expected) {
            assertTrue(System.currentTimeMillis() < deadline, "stored $key never became $expected")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(expected, viewModel.ds.getString(key))
    }

    @Test
    fun motionPhotoDefaultsOnAtThreeSecondsAndPersistsOffAndLengths() {
        val first = vm()
        try {
            assertEquals(MotionPhotoDuration.THREE, first.motionPhotoDuration.value, "default must be on at 3s")

            first.setMotionPhotoDuration(MotionPhotoDuration.OFF)
            assertEquals(MotionPhotoDuration.OFF, first.motionPhotoDuration.value)
            awaitStored(first, KEY, "OFF")
            assertEquals(MotionPhotoDuration.OFF, vm().motionPhotoDuration.value)

            first.setMotionPhotoDuration(MotionPhotoDuration.FIVE)
            awaitStored(first, KEY, "FIVE")
            assertEquals(MotionPhotoDuration.FIVE, vm().motionPhotoDuration.value)
        } finally {
            first.setMotionPhotoDuration(MotionPhotoDuration.THREE)
            awaitStored(first, KEY, "THREE")
        }
        assertEquals(MotionPhotoDuration.THREE, vm().motionPhotoDuration.value)
    }

    @Test
    fun legacyToggleAndLengthMigrateToTheSingleSetting() {
        // Pure migration matrix (no stored values -> current default behaviour).
        assertEquals(MotionPhotoDuration.THREE, migratedMotionPhotoDuration(null, null, null))
        assertEquals(MotionPhotoDuration.OFF, migratedMotionPhotoDuration(null, "false", "FIVE"))
        assertEquals(MotionPhotoDuration.FIVE, migratedMotionPhotoDuration(null, "true", "FIVE"))
        assertEquals(MotionPhotoDuration.ONE, migratedMotionPhotoDuration(null, null, "ONE"))
        assertEquals(MotionPhotoDuration.THREE, migratedMotionPhotoDuration(null, "true", null))
        // The current key wins over any legacy pair; malformed values fall back.
        assertEquals(MotionPhotoDuration.ONE, migratedMotionPhotoDuration("ONE", "false", "FIVE"))
        assertEquals(MotionPhotoDuration.OFF, migratedMotionPhotoDuration("OFF", "true", "FIVE"))
        assertEquals(MotionPhotoDuration.THREE, migratedMotionPhotoDuration("bogus", null, null))
        assertEquals(MotionPhotoDuration.THREE, migratedMotionPhotoDuration(null, "true", "bogus"))
    }

    private companion object {
        const val KEY = "camera_motion_photo"
    }
}
