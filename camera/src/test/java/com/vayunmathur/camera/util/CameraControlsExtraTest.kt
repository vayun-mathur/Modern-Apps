package com.vayunmathur.camera.util

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CameraControlsExtraTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun qrAndZoomStateWithoutBoundCamera() {
        val v = vm()
        v.setQrResult("https://example.com")
        assertEquals("https://example.com", v.qrResult.value)
        v.setQrResult(null)
        assertNull(v.qrResult.value)

        v.onViewfinderZoomRatio(2.5f)
        assertEquals(2.5f, v.zoomRatio.value)
        // No camera bound: the requested ratio is stored unclamped.
        v.setZoomRatio(8f)
        assertEquals(8f, v.zoomRatio.value)
        v.restoreZoom(1f, 4f)
        assertEquals(4f, v.zoomRatio.value)
        v.restoreZoom(0.5f, 10f)
        assertEquals(4f, v.zoomRatio.value)
    }

    @Test fun zoomLevelsFollowLensRange() {
        val v = vm()
        v.updateZoomLevels(1f, 1f)
        assertEquals(listOf("1x" to 1f), v.availableZoomLevels.value)
        v.updateZoomLevels(0.5f, 10f)
        val levels = v.availableZoomLevels.value
        assertEquals(".5", levels.first().first)
        assertTrue(levels.any { it.second == 1f })
        assertTrue(levels.any { it.second == 5f })
        assertFalse(levels.any { it.second > 5f })
    }

    @Test fun focusTorchExposureWithoutCameraAreSafe() {
        val v = vm()
        v.clearFocusLock()
        assertFalse(v.focusLocked.value)
        v.enableTorch(true)
        // enableTorch forwards to cameraControl when bound; unbound it
        // must at least not throw (torch flow is driven by toggleTorch).
        v.applyExposureCompensation(1f)
        v.applyImageCaptureFlashMode()
        v.setImageAnalyzer(null)
        v.setBokehAnalyzer(null)
        v.setPhotoAnalyzer(null)
        v.onSessionBound()
    }

    @Test fun manualControlStateWithoutCamera() {
        val v = vm()
        v.setExposureTimeIndex(3)
        assertEquals(3, v.exposureTimeIndex.value)
        v.isoStopsMutable.value = listOf(100, 200, 400)
        v.setManualIsoIndex(2)
        assertEquals(2, v.manualIsoIndex.value)
        assertEquals(200, v.manualIso())
        assertFalse(v.isExposureAuto())
        v.setManualIsoIndex(99)
        assertEquals(3, v.manualIsoIndex.value)
        assertNull(v.camera2ControlOrNull())
        v.applyManualControls()
        v.readManualControlRanges()
    }

    @Test fun recordingTimerStartsAndStops() {
        val v = vm()
        v.startRecordingTimer()
        v.stopRecordingTimer()
        assertEquals(0L, v.recordingDurationSec.value)
    }

    @Test fun panoramaEngineFrameHandoff() {
        val engine = PanoramaEngine(ctx)
        assertFalse(engine.isSweeping.value)
        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        engine.offerFrame(first, 90)
        assertEquals(90, engine.latestFrameRotation)
        // Offering a second frame recycles the undelivered first one.
        engine.offerFrame(second, 180)
        assertTrue(first.isRecycled)
        val (taken, rotation) = engine.takeFrame()
        assertEquals(second, taken)
        assertEquals(180, rotation)
        assertNull(engine.latestFrame)
        taken?.recycle()
    }

    @Test fun panoramaSweepLifecycle() {
        val engine = PanoramaEngine(ctx)
        engine.startSweep()
        assertTrue(engine.isSweeping.value)
        assertTrue(engine.guideDots.value.isNotEmpty())
        // The sweep seeds dot 0 as the one being captured.
        assertEquals(GuideDotState.CAPTURING, engine.guideDots.value.first().state)
        assertEquals(GuideDotState.PENDING, engine.guideDots.value[1].state)
        engine.stopSweep()
        assertFalse(engine.isSweeping.value)
        engine.reset()
        assertEquals(0, engine.frameCount.value)
    }

    @Test fun panoramaFullSphereHasMoreDots() {
        val flat = PanoramaEngine(ctx).apply { startSweep(fullSphere = false) }
        val sphere = PanoramaEngine(ctx).apply { startSweep(fullSphere = true) }
        assertTrue(sphere.guideDots.value.size >= flat.guideDots.value.size)
        flat.stopSweep(); sphere.stopSweep()
    }

    @Test fun nightExtensionFailureRecordingDoesNotThrow() {
        val v = vm()
        assertFalse(v.nightExtFailedRecently())
        // The timestamp write is async on viewModelScope; recording must
        // not throw and must mark the extension unusable immediately.
        v.recordNightExtensionFailure()
        assertFalse(v.nightExtensionUsable.value)
    }

    @Test fun saveTreeUriHelpers() {
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        assertEquals(
            android.os.Environment.getExternalStorageDirectory().absolutePath + "/DCIM",
            SafDocuments.primaryPath(tree),
        )
        assertNull(SafDocuments.primaryPath(android.net.Uri.parse("content://x/y")))
    }
}
