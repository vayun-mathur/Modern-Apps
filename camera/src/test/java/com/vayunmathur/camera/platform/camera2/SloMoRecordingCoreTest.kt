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

    @Test fun readinessIsCompleteAndRecordingEngineReady() {
        assertTrue(SloMoRecordingReadiness.ENCODER_SURFACE_HANDOFF_READY)
        assertTrue(SloMoRecordingReadiness.SHARED_CODEC_CORE_READY)
        assertTrue(SloMoRecordingReadiness.SESSION_RECORDING_BOUNDARY_READY)
        assertTrue(SloMoRecordingReadiness.SLO_MO_PREVIEW_READY)
        assertTrue(SloMoRecordingReadiness.NO_AUDIO_MUXER_READY)
        assertTrue(SloMoRecordingReadiness.TIMESTAMP_VERIFICATION_READY)
        assertTrue(SloMoRecordingReadiness.SAVE_PATH_GLUE_READY)
        // Device-proven: the conjunction callers feed the policy is true.
        assertTrue(SloMoRecordingReadiness.recordingEngineReady)
    }

    @Test fun remainingPiecesIsEmptyWhenReady() {
        assertEquals(emptyList<String>(), SloMoRecordingReadiness.remainingPieces())
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

    @Test fun sloMoPreviewSurfaceAndLiveSelectionContracts() {
        assertTrue(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                isSlowMoMode = true, fallbackLatched = false, hasSelection = true,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                isSlowMoMode = true, fallbackLatched = true, hasSelection = true,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                isSlowMoMode = false, fallbackLatched = false, hasSelection = true,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.canSupplyPreviewSurface(
                isSlowMoMode = true, fallbackLatched = false, hasSelection = false,
            ),
        )
        // Live selection additionally requires a ready recording engine;
        // readiness is device-proven, so the live flag now selects it.
        assertTrue(
            SloMoPreviewPolicy.livePreviewSelected(
                isSlowMoMode = true, fallbackLatched = false, hasSelection = true,
                recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady,
            ),
        )
        assertFalse(
            SloMoPreviewPolicy.livePreviewSelected(
                isSlowMoMode = true, fallbackLatched = false, hasSelection = true,
                recordingEngineReady = false,
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
            SloMoSaveAction.DISCARD_AND_ERROR,
            SloMoSavePathPolicy.actionFor(
                outcomeError = true, outputReadable = true, fallbackAlreadyLatched = false,
            ),
        )
        assertEquals(
            SloMoSaveAction.DISCARD_AND_ERROR,
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
