package com.vayunmathur.library.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end codec pipelines on a real device: the platform decoders, resampler
 * and Opus encoder that [OpusTranscoder] drives, and the demux/mux pair that
 * [OpusRemuxer] drives, none of which exist under Robolectric.
 */
class CodecPipelineTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ------------------------------------------------------------------
    // Source generation: encode a sine tone with the platform encoders.
    // ------------------------------------------------------------------

    private fun pcmSine(sampleRate: Int, channels: Int, seconds: Double = 0.5): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        val out = ByteArray(frames * channels * 2)
        var at = 0
        for (frame in 0 until frames) {
            val value = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 12_000).toInt()
            repeat(channels) {
                out[at++] = value.toByte()
                out[at++] = (value shr 8).toByte()
            }
        }
        return out
    }

    /** Encodes PCM into a muxed file (MP4/AAC or WebM/Opus) and returns its bytes. */
    private fun encodeToFile(mime: String, muxerFormat: Int, suffix: String): ByteArray {
        val sampleRate = 44_100
        val channels = 2
        val pcm = pcmSine(sampleRate, channels)
        val file = File.createTempFile("codec-src", suffix, context.cacheDir)
        file.delete()
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(file.absolutePath, muxerFormat)
        try {
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                }
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var track = -1
            var muxerStarted = false
            var inputDone = false
            var outputDone = false
            var pcmOffset = 0
            var framesQueued = 0L
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        if (pcmOffset >= pcm.size) {
                            codec.queueInputBuffer(
                                index, 0, 0,
                                framesQueued * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val chunk = minOf(buffer.remaining(), pcm.size - pcmOffset)
                            val frames = chunk / (channels * 2) * (channels * 2)
                            buffer.put(pcm, pcmOffset, frames)
                            codec.queueInputBuffer(
                                index, 0, frames,
                                framesQueued * 1_000_000L / sampleRate, 0,
                            )
                            framesQueued += frames / (channels * 2)
                            pcmOffset += frames
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0 && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            assertTrue("muxer must have started for $mime", muxerStarted)
            // Stop the muxer before reading: an MP4 without its moov atom (or an
            // unfinalised WebM) has no readable tracks, so reading first would
            // hand the transcoders a file with nothing in it.
            runCatching { codec.stop() }
            runCatching { muxer.stop() }
            val bytes = file.readBytes()
            assertTrue("encoded $mime source must not be empty", bytes.size > 500)
            return bytes
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            file.delete()
        }
    }

    private fun aacMp4(): ByteArray =
        encodeToFile(MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, ".m4a")

    private fun opusWebM(): ByteArray =
        encodeToFile(MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, ".webm")

    private fun assertOggOpus(bytes: ByteArray, minSize: Int = 200) {
        assertTrue("expected an Ogg stream, got ${bytes.size} bytes", bytes.size > minSize)
        assertEquals("OggS", String(bytes, 0, 4, Charsets.ISO_8859_1))
        assertTrue("stream must carry OpusHead", containsSubarray(bytes, "OpusHead".toByteArray()))
        assertTrue("stream must carry OpusTags", containsSubarray(bytes, "OpusTags".toByteArray()))
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // OpusTranscoder
    // ------------------------------------------------------------------

    @Test
    fun transcode_convertsAacMp4ToOggOpus() {
        val result = OpusTranscoder.transcode(aacMp4())
        assertNotNull("AAC MP4 must transcode on device", result)
        assertOggOpus(result!!)
        // The result must be retaggable, which is the path every download takes.
        val tagged = OggOpusTagger.tag(result, VorbisTags(title = "Tone"))
        assertNotNull(tagged)
        assertTrue(String(tagged!!, Charsets.UTF_8).contains("TITLE=Tone"))
    }

    @Test
    fun transcodeTo_streamsAndReportsProgress() {
        val sink = ByteArrayOutputStream()
        val progress = ArrayList<Float>()
        val count = OpusTranscoder.transcodeTo(aacMp4(), sink) { progress.add(it) }
        assertNotNull(count)
        assertEquals(sink.size().toLong(), count)
        assertOggOpus(sink.toByteArray())
        assertTrue("progress must be reported", progress.isNotEmpty())
        assertTrue(progress.all { it in 0f..1f })
    }

    @Test
    fun transcode_cancelledReturnsNull() {
        val sink = ByteArrayOutputStream()
        val count = OpusTranscoder.transcodeTo(aacMp4(), sink, isStopped = { true })
        assertNull(count)
        assertNull(OpusTranscoder.transcode(aacMp4(), isStopped = { true }))
    }

    @Test
    fun transcode_reencodesWebmOpus() {
        val result = OpusTranscoder.transcode(opusWebM())
        assertNotNull(result)
        assertOggOpus(result!!)
    }

    // ------------------------------------------------------------------
    // OpusRemuxer
    // ------------------------------------------------------------------

    @Test
    fun remux_convertsWebmOpusToOggOpus() {
        val result = OpusRemuxer.remux(context, opusWebM())
        assertNotNull("WebM/Opus must remux on device", result)
        assertOggOpus(result!!)
    }

    @Test
    fun remux_rejectsGarbage() {
        assertNull(OpusRemuxer.remux(context, ByteArray(512) { 0x33 }))
        assertNull(OpusRemuxer.remux(context, ByteArray(0)))
    }
}
