package com.vayunmathur.camera.platform.camera2

import kotlin.test.Test
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
        assertTrue(SloMoRecordingReadiness.SESSION_RECORDING_BOUNDARY_READY)
        assertTrue(SloMoRecordingReadiness.NO_AUDIO_MUXER_READY)
        assertFalse(SloMoRecordingReadiness.SHARED_CODEC_CORE_READY)
        assertFalse(SloMoRecordingReadiness.SLO_MO_PREVIEW_READY)
        assertFalse(SloMoRecordingReadiness.TIMESTAMP_VERIFICATION_READY)
        assertFalse(SloMoRecordingReadiness.SAVE_PATH_GLUE_READY)
        // The conjunction is the flag callers feed the policy; it must not
        // flip while any piece above is open.
        assertFalse(SloMoRecordingReadiness.recordingEngineReady)
    }
}
