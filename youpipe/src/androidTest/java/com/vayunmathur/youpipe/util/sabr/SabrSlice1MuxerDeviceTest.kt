package com.vayunmathur.youpipe.util.sabr

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice-1 (sabr/) device pass: the muxer's real sample-pump path, using
 * MediaMuxer-written fMP4 inputs (stream copy, no encoder involved).
 */
@RunWith(AndroidJUnit4::class)
class SabrSlice1MuxerDeviceTest {

    private fun writeTrackFile(dir: File, name: String, video: Boolean): File {
        val file = File(dir, name)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = if (video) {
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x1f.toByte())))
                setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x68, 0xee.toByte(), 0x3c, 0x80.toByte())))
            }
        } else {
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11.toByte(), 0x90.toByte())))
            }
        }
        val track = muxer.addTrack(format)
        muxer.start()
        val info = MediaCodec.BufferInfo()
        // Three samples per track at staggered times so the interleaving pump alternates.
        for (i in 0 until 3) {
            val sample = ByteArray(64) { (i * 16 + it).toByte() }
            info.set(0, sample.size, i * 100_000L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, ByteBuffer.wrap(sample), info)
        }
        muxer.stop(); muxer.release()
        return file
    }

    @Test fun muxCombinesRealTracksIntoMp4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "sabr-mux-device").apply { mkdirs() }
        val video = writeTrackFile(dir, "v.mp4", video = true)
        val audio = writeTrackFile(dir, "a.mp4", video = false)
        val output = File(dir, "combined.mp4")
        SabrFfmpegMuxer.mux(video, audio, output)
        assertTrue("muxed output missing/empty", output.exists() && output.length() > 0)
        // The output is a readable container with both tracks.
        val extractor = android.media.MediaExtractor().apply { setDataSource(output.absolutePath) }
        assertTrue("expected 2 tracks, got ${extractor.trackCount}", extractor.trackCount == 2)
        extractor.release()
    }

    @Test fun muxSingleTrackFileStillProducesOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "sabr-mux-device1").apply { mkdirs() }
        val video = writeTrackFile(dir, "v.mp4", video = true)
        val empty = File(dir, "empty.mp4").apply {
            // A valid container with no samples of the other kind: reuse video bytes so the
            // audio extractor finds no audio track and the video-only path is exercised.
            writeBytes(video.readBytes())
        }
        val output = File(dir, "combined.mp4")
        SabrFfmpegMuxer.mux(video, empty, output)
        assertTrue(output.exists() && output.length() > 0)
    }
}
