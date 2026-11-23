package com.vayunmathur.camera.platform.camera2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Piece-6 read-back contract: stretched vs realtime encoder output. */
class SloMoStretchMathTest {

    // 240 fps captured, 30 fps played back, 3 s of wall time:
    // an honouring encoder emits ~720 frames spaced 1/30 s -> 24 s span.
    @Test fun honouringEncoderReadsStretched() {
        val frames = 720
        val lastPtsUs = 24_000_000L
        assertEquals(30.0, SloMoStretchMath.effectiveFps(frames, 0L, lastPtsUs), 0.5)
        assertTrue(
            SloMoStretchMath.isStretched(
                frameCount = frames, firstPtsUs = 0L, lastPtsUs = lastPtsUs,
                playbackFps = 30, captureFps = 240, wallDurationMs = 3_000L,
            ),
        )
    }

    // An ignoring encoder emits the same 720 frames spaced 1/240 s:
    // 3 s span at an effective 240 fps — fast, not slow.
    @Test fun ignoringEncoderReadsRealtime() {
        val frames = 720
        val lastPtsUs = 3_000_000L
        assertEquals(240.0, SloMoStretchMath.effectiveFps(frames, 0L, lastPtsUs), 1.0)
        assertFalse(
            SloMoStretchMath.isStretched(
                frameCount = frames, firstPtsUs = 0L, lastPtsUs = lastPtsUs,
                playbackFps = 30, captureFps = 240, wallDurationMs = 3_000L,
            ),
        )
    }

    // Fewer captured frames (dropped edges) still proves stretch when
    // the spacing is at the playback rate and span holds >= 80%.
    @Test fun droppedEdgeFramesStillStretched() {
        assertTrue(
            SloMoStretchMath.isStretched(
                frameCount = 600, firstPtsUs = 0L, lastPtsUs = 20_000_000L,
                playbackFps = 30, captureFps = 240, wallDurationMs = 3_000L,
            ),
        )
    }

    // Synthesized playback PTS: frame N plays at N / playbackFps.
    @Test fun synthesizedPlaybackPts() {
        assertEquals(0L, SloMoTimestampStretch.playbackPtsUs(0L, 30))
        assertEquals(33_333L, SloMoTimestampStretch.playbackPtsUs(1L, 30))
        assertEquals(1_000_000L, SloMoTimestampStretch.playbackPtsUs(30L, 30))
        assertEquals(0L, SloMoTimestampStretch.playbackPtsUs(5L, 0))
    }

    @Test fun degenerateInputsNeverStretched() {
        assertFalse(
            SloMoStretchMath.isStretched(1, 0L, 0L, 30, 240, 3_000L),
        )
        assertFalse(
            SloMoStretchMath.isStretched(100, 0L, 3_000_000L, 30, 240, 0L),
        )
        assertEquals(0.0, SloMoStretchMath.effectiveFps(1, 0L, 0L), 0.0)
    }
}
