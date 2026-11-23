package com.vayunmathur.camera.platform.camera2

import android.media.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opus-only audio selection: the encoder choice can never produce AAC,
 * and a device without an Opus encoder records video-only (the muxer
 * must not wait for an audio track that will never arrive).
 */
class AudioEncoderPolicyTest {

    @Test fun opusIsTheOnlyAudioMime() {
        assertEquals("audio/opus", AudioEncoderPolicy.OPUS_MIME)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioEncoderPolicy.OPUS_MIME)
        assertEquals(AudioEncoderPolicy.OPUS_MIME, AudioEncoderPolicy.resolveAudioMime(true))
    }

    @Test fun noOpusEncoderMeansNoAudioTrackNeverAac() {
        assertNull(AudioEncoderPolicy.resolveAudioMime(false))
        // No input produces AAC (or any non-Opus mime).
        for (available in listOf(true, false)) {
            val mime = AudioEncoderPolicy.resolveAudioMime(available)
            assertNotEquals(MediaFormat.MIMETYPE_AUDIO_AAC, mime)
            assertTrue(mime == null || mime == AudioEncoderPolicy.OPUS_MIME)
        }
    }

    @Test fun shouldEnableAudioRequiresRequestAndOpusEncoder() {
        assertTrue(AudioEncoderPolicy.shouldEnableAudio(audioRequested = true, opusEncoderAvailable = true))
        assertFalse(AudioEncoderPolicy.shouldEnableAudio(audioRequested = true, opusEncoderAvailable = false))
        assertFalse(AudioEncoderPolicy.shouldEnableAudio(audioRequested = false, opusEncoderAvailable = true))
        assertFalse(AudioEncoderPolicy.shouldEnableAudio(audioRequested = false, opusEncoderAvailable = false))
    }

    @Test fun muxerDoesNotWaitForMissingOpusTrack() {
        // Requested audio but no Opus encoder: the muxer-start decision
        // must see audio as disabled and start on the video track alone.
        val effectiveAudioEnabled = AudioEncoderPolicy.shouldEnableAudio(
            audioRequested = true, opusEncoderAvailable = false,
        )
        assertTrue(
            MuxerStartPolicy.shouldStart(
                hasVideoTrack = true, hasAudioTrack = false, audioEnabled = effectiveAudioEnabled,
            ),
        )
        // With an Opus encoder, the muxer still waits for both tracks.
        val opusAudioEnabled = AudioEncoderPolicy.shouldEnableAudio(
            audioRequested = true, opusEncoderAvailable = true,
        )
        assertFalse(
            MuxerStartPolicy.shouldStart(
                hasVideoTrack = true, hasAudioTrack = false, audioEnabled = opusAudioEnabled,
            ),
        )
        assertTrue(
            MuxerStartPolicy.shouldStart(
                hasVideoTrack = true, hasAudioTrack = true, audioEnabled = opusAudioEnabled,
            ),
        )
    }
}
