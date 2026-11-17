package com.vayunmathur.cast.platform.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.vayunmathur.library.log.Log

private const val TAG = "AudioEncoder"

/**
 * Captures what the phone is playing and encodes it as Opus.
 *
 * The Opus half lives in [OpusEncoder], shared with [PcmAudioEncoder]; what is left here is the
 * `AudioPlaybackCapture` half, which is the part that only screen mirroring can use.
 *
 * **Two honest limitations, neither of which this class can fix:**
 *
 *  1. `AudioPlaybackCapture` silently excludes any app that sets `ALLOW_CAPTURE_BY_NONE` - which is
 *     most DRM and music apps. Those are simply not in the stream, so the captured audio can be
 *     digital silence through no fault of ours and with no error anywhere.
 *  2. `audio/opus` encoders are not universally present.
 *
 * [EncoderSupport] answers the second before anything starts, and the UI says which one degraded.
 * Nothing here can detect the first, so the copy has to warn about it in general terms. An SDK
 * session avoids the first problem entirely, because the app hands over its own PCM.
 *
 * `RECORD_AUDIO` is required even though no microphone is involved - the permission gates
 * `AudioRecord` itself, not the source.
 */
class AudioEncoder(private val projection: MediaProjection?) : AudioStream {

    private var record: AudioRecord? = null
    private val opus = OpusEncoder()

    // WrongConstant: REMOTE_SUBMIX (used on the desktop path below) is a @SystemApi
    // audio source, so it is absent from lint's public-SDK constant list even though
    // it is the correct AudioRecord source here; it is gated on CAPTURE_AUDIO_OUTPUT.
    @SuppressLint("MissingPermission", "WrongConstant")
    override fun start(): Boolean {
        if (!opus.start()) return false
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OpusEncoder.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                OpusEncoder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(OpusEncoder.FRAME_BYTES * 4)
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
            if (projection != null) {
                // Screen mirroring. AudioPlaybackCapture is scoped by the projection the user
                // consented to, and honours each app's own allowCapture policy.
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                builder.setAudioPlaybackCaptureConfig(config)
            } else {
                // Desktop mode. There is no projection to scope a capture with - that is the
                // point of the path - so audio comes from REMOTE_SUBMIX, the source the platform
                // provides for remote displays. It needs CAPTURE_AUDIO_OUTPUT, which is
                // signature|privileged and allowlisted for this app in
                // privapp-permissions-modern-apps.xml.
                //
                // TWO DIFFERENCES FROM THE MIRRORING PATH, both inherent to the source:
                //  - It takes the whole output mix, not a per-usage selection, so it is not
                //    filtered by USAGE the way the capture config above is.
                //  - It does NOT honour an app's allowCapture policy, because that policy is
                //    enforced by AudioPlaybackCapture rather than by REMOTE_SUBMIX. An app that
                //    opts out of being captured is still audible on the TV.
                builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            }
            val created = builder.build()
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                Log.status(TAG, "AudioRecord did not initialise")
                created.release()
                release()
                return false
            }
            created.startRecording()
            record = created
            true
        } catch (e: IllegalStateException) {
            failStart(e)
        } catch (e: IllegalArgumentException) {
            failStart(e)
        } catch (e: SecurityException) {
            failStart(e)
        } catch (e: UnsupportedOperationException) {
            failStart(e)
        }
    }

    private fun failStart(e: Exception): Boolean {
        Log.status(TAG, "could not start audio capture", e)
        release()
        return false
    }

    /** Read one 20 ms buffer of PCM, feed it in, and return whatever came out. */
    override fun pump(): List<EncodedChunk> {
        val active = record ?: return emptyList()
        val pcm = ByteArray(OpusEncoder.FRAME_BYTES)
        val read = try {
            active.read(pcm, 0, pcm.size)
        } catch (e: IllegalStateException) {
            Log.status(TAG, "audio read failed", e)
            return emptyList()
        } catch (e: IllegalArgumentException) {
            Log.status(TAG, "audio read failed", e)
            return emptyList()
        }
        return opus.encode(pcm, read.coerceAtLeast(0))
    }

    override fun release() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        opus.release()
    }
}
