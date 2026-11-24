package com.vayunmathur.camera.ui

import com.vayunmathur.camera.platform.camera2.RecordingPreviewPolicy
import com.vayunmathur.camera.platform.camera2.RecordingViewfinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recording-state UI contract: while a recording is active the screen
 * composes the recording preview (a live TextureView fed by the
 * recording session), never the standard preview and never a black
 * placeholder in its place; and the recording engine only starts once
 * that preview has published its own surface, so the surface the
 * session targets is the surface the on-screen view renders.
 */
class RecordingPreviewUiTest {

    @Test
    fun recordingShowsRecordingPreview() {
        assertEquals(
            RecordingViewfinder.RECORDING,
            RecordingPreviewPolicy.viewfinder(recordingActive = true),
        )
    }

    @Test
    fun idleShowsStandardPreview() {
        assertEquals(
            RecordingViewfinder.STANDARD,
            RecordingPreviewPolicy.viewfinder(recordingActive = false),
        )
    }

    @Test
    fun engineStartsOnlyWhenPreviewSurfaceIsLive() {
        assertTrue(
            RecordingPreviewPolicy.shouldStartEngine(
                startPending = true,
                alreadyLaunched = false,
                previewSurfaceAvailable = true,
            )
        )
    }

    @Test
    fun engineDoesNotStartBeforePreviewSurfaceArrives() {
        // Starting earlier would configure the session against a
        // surface the view does not own (the old black-preview wiring).
        assertFalse(
            RecordingPreviewPolicy.shouldStartEngine(
                startPending = true,
                alreadyLaunched = false,
                previewSurfaceAvailable = false,
            )
        )
    }

    @Test
    fun surfaceArrivalWithoutPendingStartDoesNotStartEngine() {
        assertFalse(
            RecordingPreviewPolicy.shouldStartEngine(
                startPending = false,
                alreadyLaunched = false,
                previewSurfaceAvailable = true,
            )
        )
    }

    @Test
    fun engineStartsAtMostOncePerRecording() {
        assertFalse(
            RecordingPreviewPolicy.shouldStartEngine(
                startPending = true,
                alreadyLaunched = true,
                previewSurfaceAvailable = true,
            )
        )
    }
}
