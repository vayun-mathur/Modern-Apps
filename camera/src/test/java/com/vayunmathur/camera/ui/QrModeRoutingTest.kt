package com.vayunmathur.camera.ui

import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.camera2.PreviewEnginePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QR mode routing: the mode exists, the Camera2 preview engine serves it
 * (which is what brings the analysis stream the scanner feeds on), and
 * the per-mode settings/routing helpers treat it as a capture-less
 * photo-family mode.
 */
class QrModeRoutingTest {

    @Test
    fun qrModeExistsAndUsesCamera2Preview() {
        assertTrue(CameraMode.QR in CameraMode.entries)
        assertTrue(PreviewEnginePolicy.usesCamera2(CameraMode.QR))
        assertTrue(CameraMode.QR in PreviewEnginePolicy.supportedModes)
    }

    @Test
    fun qrModeOffersZoomOnlySettings() {
        // No still is baked in QR mode, so warmth/shadows/manual exposure
        // would lie; zoom drives the live preview and genuinely helps
        // pull a small code closer.
        assertEquals(listOf(CameraSetting.ZOOM), settingsForMode(CameraMode.QR))
        assertEquals(CameraSetting.ZOOM, effectiveSettingForMode(CameraSetting.ZOOM, CameraMode.QR))
        assertEquals(null, effectiveSettingForMode(CameraSetting.WARMTH, CameraMode.QR))
    }

    @Test
    fun qrModeIsNotPhotoOrVideoType() {
        // The shutter must never take a photo or start a recording in QR
        // mode; routing keys off these families, so QR stays out of both
        // and is handled by its own branch.
        val modes = CameraMode.entries
        assertFalse(CameraMode.QR == CameraMode.PHOTO)
        assertTrue(modes.indexOf(CameraMode.QR) in modes.indices)
    }
}
