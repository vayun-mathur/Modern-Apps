package com.vayunmathur.camera.ui

import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.camera2.PreviewEnginePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QR-in-PHOTO routing (owner decision, replacing the short-lived QR
 * mode): there is no QR entry in the mode enum/row at all, PHOTO keeps
 * the Camera2 preview (which brings the analysis stream the scanner
 * feeds on), and the photo family keeps its capture behaviour — the
 * shutter in PHOTO always takes a photo, scanned code or not.
 */
class QrInPhotoRoutingTest {

    @Test
    fun noQrModeExists() {
        assertFalse(CameraMode.entries.any { it.name == "QR" })
        assertEquals(
            listOf(
                CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.PANORAMA,
                CameraMode.PHOTOSPHERE, CameraMode.VIDEO, CameraMode.SLOW_MO,
                CameraMode.TIMELAPSE, CameraMode.CINEMATIC,
            ),
            CameraMode.entries.toList(),
        )
    }

    @Test
    fun photoModeUsesCamera2PreviewWithAnalysisStream() {
        assertTrue(PreviewEnginePolicy.usesCamera2(CameraMode.PHOTO))
        assertTrue(CameraMode.PHOTO in PreviewEnginePolicy.supportedModes)
    }

    @Test
    fun photoSettingsIncludePhotoControlsZoomIncluded() {
        // PHOTO keeps its full control set; scanning adds no mode-specific
        // pane and removes none.
        val settings = settingsForMode(CameraMode.PHOTO)
        assertTrue(CameraSetting.ZOOM in settings)
        assertTrue(CameraSetting.BRIGHTNESS in settings)
    }
}
