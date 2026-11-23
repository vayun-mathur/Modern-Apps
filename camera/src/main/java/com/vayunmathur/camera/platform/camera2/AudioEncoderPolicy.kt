package com.vayunmathur.camera.platform.camera2

/**
 * Opus-only audio encoding policy for Camera2 video recordings.
 *
 * Opus (`audio/opus`) is the ONLY audio encoding this app produces: there
 * is no AAC branch and no fallback ordering that can produce AAC. Android
 * 10+ devices ship an Opus encoder, so on any supported device
 * [OPUS_MIME] is returned and the recording carries an Opus track.
 *
 * When no Opus encoder exists, the honest failure is a video-only
 * (mute-track) recording with a logged reason — never a silent AAC
 * fallback. [resolveAudioMime] returning null is that signal: the
 * recording core skips AudioRecord/encoder creation entirely and the
 * muxer starts on the video track alone (see [MuxerStartPolicy]).
 *
 * Pure (no Android types) so the selection is unit-testable without a
 * codec registry; the Android side probes `MediaCodecList` once
 * (`CodecSupport.isOpusAudioEncoderAvailable`) and feeds the boolean in.
 */
object AudioEncoderPolicy {

    /** The only audio mime a recording may ever carry. */
    const val OPUS_MIME: String = "audio/opus"

    /**
     * The audio mime to encode with, or null when this device has no Opus
     * encoder and the recording must proceed video-only. Never returns
     * AAC (or any other mime) under any input.
     */
    fun resolveAudioMime(opusEncoderAvailable: Boolean): String? =
        if (opusEncoderAvailable) OPUS_MIME else null

    /**
     * True when a recording that wants audio will actually get an audio
     * track: audio was requested AND an Opus encoder exists. This is the
     * `audioEnabled` value the muxer-start decision must see — a
     * requested-but-unavailable Opus track must not hold the muxer
     * waiting for a track that will never be added.
     */
    fun shouldEnableAudio(audioRequested: Boolean, opusEncoderAvailable: Boolean): Boolean =
        audioRequested && resolveAudioMime(opusEncoderAvailable) != null
}
