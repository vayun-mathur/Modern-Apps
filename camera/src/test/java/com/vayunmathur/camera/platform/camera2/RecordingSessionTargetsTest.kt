package com.vayunmathur.camera.platform.camera2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recording-session target composition: while recording, the repeating
 * request must keep BOTH the encoder surface (the file) and the preview
 * surface (the visible viewfinder) for the whole take, plus the
 * analysis surface when one exists. Regression contract for "preview
 * is blank while recording": a target list without PREVIEW records a
 * file the user cannot see being captured.
 */
class RecordingSessionTargetsTest {

    @Test
    fun configureTargetsIncludePreviewAndEncoder() {
        val targets = RecordingSessionTargets.configureTargets(
            hasPreviewSurface = true,
            hasAnalysisSurface = true,
        )
        assertEquals(
            listOf(
                RecordingTargetRole.ENCODER,
                RecordingTargetRole.PREVIEW,
                RecordingTargetRole.ANALYSIS,
            ),
            targets,
        )
    }

    @Test
    fun repeatingTargetsKeepPreviewForWholeRecording() {
        val configure = RecordingSessionTargets.configureTargets(
            hasPreviewSurface = true,
            hasAnalysisSurface = true,
        )
        val repeating = RecordingSessionTargets.repeatingTargets(
            hasPreviewSurface = true,
            hasAnalysisSurface = true,
        )
        // The repeating request is what runs for the whole recording:
        // it must be the same composition, never a preview-less subset.
        assertEquals(configure, repeating)
        assertTrue(RecordingTargetRole.PREVIEW in repeating)
        assertTrue(RecordingTargetRole.ENCODER in repeating)
    }

    @Test
    fun repeatingTargetsWithoutAnalysisStillKeepPreview() {
        val repeating = RecordingSessionTargets.repeatingTargets(
            hasPreviewSurface = true,
            hasAnalysisSurface = false,
        )
        assertEquals(
            listOf(RecordingTargetRole.ENCODER, RecordingTargetRole.PREVIEW),
            repeating,
        )
    }

    @Test
    fun noPreviewSurfaceMeansNoPreviewTarget() {
        // Only when no viewfinder surface was supplied at all may the
        // session run encoder-only; it never invents a preview target.
        val targets = RecordingSessionTargets.configureTargets(
            hasPreviewSurface = false,
            hasAnalysisSurface = true,
        )
        assertFalse(RecordingTargetRole.PREVIEW in targets)
        assertTrue(RecordingTargetRole.ENCODER in targets)
    }
}
