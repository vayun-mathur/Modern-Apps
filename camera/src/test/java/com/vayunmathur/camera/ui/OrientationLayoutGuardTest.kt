package com.vayunmathur.camera.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.MainActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * Guards the rotation-in-place behaviour: the camera is portrait-locked, so
 * rotating the phone never rearranges the capture controls — only their icons
 * rotate (via [deviceRotationForOrientation]) and CameraX auto-rotation
 * orients the capture. Reverting any leg fails here: dropping the manifest
 * lock (as the lint sweep in d0ec660e4 did), remapping a quadrant, or
 * reintroducing a side-by-side layout switch on rotation.
 *
 * Host-side limits: Robolectric cannot physically rotate a device or measure
 * real window bounds, so actual on-device placement, the animated icon
 * rotation, and EXIF/video orientation can only be checked on a device; here
 * we pin the manifest contract, the quadrant math, and the placement decision
 * the layout actually calls.
 */
@RunWith(AndroidJUnit4::class)
class OrientationLayoutGuardTest {

    @Test
    fun activityStaysPortraitLockedSoLayoutNeverRotates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), 0,
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
    }

    @Test
    fun sensorAngleMapsToIconQuadrant() {
        // Upright, including the dead zones either side of 0/360.
        assertEquals(0, deviceRotationForOrientation(0))
        assertEquals(0, deviceRotationForOrientation(44))
        assertEquals(0, deviceRotationForOrientation(315))
        assertEquals(0, deviceRotationForOrientation(359))
        // Each landscape / upside-down quadrant.
        assertEquals(270, deviceRotationForOrientation(45))
        assertEquals(270, deviceRotationForOrientation(90))
        assertEquals(270, deviceRotationForOrientation(134))
        assertEquals(180, deviceRotationForOrientation(135))
        assertEquals(180, deviceRotationForOrientation(180))
        assertEquals(180, deviceRotationForOrientation(224))
        assertEquals(90, deviceRotationForOrientation(225))
        assertEquals(90, deviceRotationForOrientation(270))
        assertEquals(90, deviceRotationForOrientation(314))
    }

    @Test
    fun unknownSensorAngleKeepsPreviousRotation() {
        assertNull(deviceRotationForOrientation(-1))
    }

    @Test
    fun controlPlacementIdenticalAcrossOrientations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertFalse(
                captureControlsSideBySide(rotation),
                "controls must stack identically at deviceRotation=$rotation",
            )
        }
    }
}
