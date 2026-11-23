package com.vayunmathur.camera.platform.camera2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural pieces 1/3/5 ordering rules, plus the readiness gate. */
class SloMoRecordingCoreTest {

    @Test fun handoffRequiresCodecBeforeSurfaceBeforeStreamingBeforeEos() {
        val handoff = EncoderSurfaceHandoff()
        assertFalse(handoff.markSurfaceExposed(), "surface before codec must be rejected")
        assertFalse(handoff.markSessionStreaming())
        assertFalse(handoff.markEosSignalled())
        assertTrue(handoff.markCodecConfigured())
        assertFalse(handoff.markCodecConfigured(), "codec configure is not repeatable")
        assertFalse(handoff.markEosSignalled(), "EOS before streaming must be rejected")
        assertTrue(handoff.markSurfaceExposed())
        assertTrue(handoff.markSessionStreaming())
        assertTrue(handoff.markEosSignalled())
        assertFalse(handoff.markSurfaceExposed(), "no re-expose after EOS")
    }

    @Test fun handoffReleaseIsTerminal() {
        val handoff = EncoderSurfaceHandoff()
        assertTrue(handoff.markReleased())
        assertFalse(handoff.markReleased())
        assertFalse(handoff.markCodecConfigured())
    }

    @Test fun muxerStartsOnVideoAloneOnlyWhenNoAudio() {
        assertTrue(MuxerStartPolicy.shouldStart(true, false, audioEnabled = false))
        assertFalse(MuxerStartPolicy.shouldStart(true, false, audioEnabled = true))
        assertTrue(MuxerStartPolicy.shouldStart(true, true, audioEnabled = true))
        assertFalse(MuxerStartPolicy.shouldStart(false, true, audioEnabled = false))
    }

    @Test fun readinessKeepsRecordingOnCameraXUntilEveryPieceCloses() {
        assertTrue(SloMoRecordingReadiness.ENCODER_SURFACE_HANDOFF_READY)
        assertTrue(SloMoRecordingReadiness.SHARED_CODEC_CORE_READY)
        assertTrue(SloMoRecordingReadiness.SESSION_RECORDING_BOUNDARY_READY)
        assertTrue(SloMoRecordingReadiness.SLO_MO_PREVIEW_READY)
        assertTrue(SloMoRecordingReadiness.NO_AUDIO_MUXER_READY)
        assertFalse(SloMoRecordingReadiness.TIMESTAMP_VERIFICATION_READY)
        assertTrue(SloMoRecordingReadiness.SAVE_PATH_GLUE_READY)
        // The conjunction is the flag callers feed the policy; it must not
        // flip while any piece above is open.
        assertFalse(SloMoRecordingReadiness.recordingEngineReady)
    }

    @Test fun remainingPiecesNamesExactlyTheOpenPieces() {
        // Pieces 2/4/7 closed in code; only device verification (6) remains.
        assertEquals(
            listOf("6. timestamp verification (device)"),
            SloMoRecordingReadiness.remainingPieces(),
        )
    }

    @Test fun sharedCoreLifecycleRequiresSurfaceBeforeDrainBeforeEosBeforeFinalize() {
        val core = SharedRecordingCoreLifecycle(RecordingSessionOwnership.EXTERNALLY_OWNED)
        assertFalse(core.markDraining(), "drain before surface must be rejected")
        assertFalse(core.markEosSignalled())
        assertFalse(core.markFinalized())
        assertTrue(core.markSurfacePrepared())
        assertFalse(core.markSurfacePrepared(), "surface prepare is not repeatable")
        assertFalse(core.markEosSignalled(), "EOS before draining must be rejected")
        assertTrue(core.markDraining())
        assertTrue(core.markEosSignalled())
        assertFalse(core.markDraining(), "no re-drain after EOS")
        assertTrue(core.markFinalized())
        assertTrue(core.markReleased())
        assertFalse(core.markReleased(), "release is terminal")
        assertFalse(core.markSurfacePrepared())
    }

    @Test fun sloMoPreviewSurfaceExistsButLiveSelectionNeedsRecordingReady() {
        // Piece 4: the surface can be supplied behind the gate...
        assertTrue(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                sloMoGateEnabled = true, isSlowMoMode = true,
                fallbackLatched = false, hasSelection = true,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                sloMoGateEnabled = true, isSlowMoMode = true,
                fallbackLatched = true, hasSelection = true,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                sloMoGateEnabled = false, isSlowMoMode = true,
                fallbackLatched = false, hasSelection = true,
            ),
        )
        // ...but live preview selection still requires the recording engine,
        // which stays not-ready until piece 6 device verification.
        assertFalse(
            SloMoPreviewPolicy.livePreviewSelected(
                sloMoGateEnabled = true, isSlowMoMode = true,
                fallbackLatched = false, hasSelection = true,
                recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady,
            ),
        )
        assertTrue(
            SloMoPreviewPolicy.livePreviewSelected(
                sloMoGateEnabled = true, isSlowMoMode = true,
                fallbackLatched = false, hasSelection = true,
                recordingEngineReady = true,
            ),
        )
    }

    @Test fun savePathPolicyRoutesSuccessAndLatchesFailureOnce() {
        assertEquals(
            SloMoSaveAction.SAVE_STAGED,
            SloMoSavePathPolicy.actionFor(
                outcomeError = false, outputReadable = true, fallbackAlreadyLatched = false,
            ),
        )
        assertEquals(
            SloMoSaveAction.DISCARD_AND_REBIND_CAMERAX,
            SloMoSavePathPolicy.actionFor(
                outcomeError = true, outputReadable = true, fallbackAlreadyLatched = false,
            ),
        )
        assertEquals(
            SloMoSaveAction.DISCARD_AND_REBIND_CAMERAX,
            SloMoSavePathPolicy.actionFor(
                outcomeError = false, outputReadable = false, fallbackAlreadyLatched = false,
            ),
        )
        // Already latched for this bind: discard without a rebind loop.
        assertEquals(
            SloMoSaveAction.DISCARD_LATCHED,
            SloMoSavePathPolicy.actionFor(
                outcomeError = true, outputReadable = false, fallbackAlreadyLatched = true,
            ),
        )
    }
}
