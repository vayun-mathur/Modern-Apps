package com.vayunmathur.youpipe.util.sabr

import android.app.Application
import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo

/**
 * Slice-1 (sabr/) second pass: a real spec built from hand-crafted sidx init
 * segments, the session playhead/seek/timeout logic around it, and the
 * media3 segment data source serving init + timed-out media reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SabrSlice1ExtraTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    companion object {
        /** Minimal MP4 sidx box (version 0) with the given per-segment durations. */
        fun sidx(timescale: Int = 1000, durationsMs: List<Int>): ByteArray {
            val size = 8 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + durationsMs.size * 12
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(size); buffer.put("sidx".toByteArray(Charsets.US_ASCII))
            buffer.putInt(0) // version/flags
            buffer.putInt(1) // reference_ID
            buffer.putInt(timescale)
            buffer.putInt(0) // earliest_presentation_time
            buffer.putInt(0) // first_offset
            buffer.putShort(0); buffer.putShort(durationsMs.size.toShort())
            for (duration in durationsMs) {
                buffer.putInt(1024) // referenced_size
                buffer.putInt(duration * timescale / 1000) // subsegment_duration in timescale units
                buffer.putInt(0) // SAP flags
            }
            return buffer.array()
        }

        fun format(itag: Int, mime: String): YoutubeSabrInfo.Format =
            YoutubeSabrInfo.Format.fromParsedFormat(
                parsedFormat = ItagItem.getItag(itag), lastModified = 0L, xtags = null, mimeType = mime,
                audioTrackId = null, audioTrackDisplayName = null, drc = false,
                initializationUrl = null, initRangeStart = 0L, initRangeEnd = 0L,
            )

        fun info(url: String = "https://127.0.0.1:9/sabr", vararg formats: YoutubeSabrInfo.Format): YoutubeSabrInfo =
            YoutubeSabrInfo("vidS1", "cpn", "1.0", null, url, null, formats.toList(), null)

        fun spec(): SabrNgSourceSpec {
            val audio = format(140, "audio/mp4")
            val video = format(137, "video/mp4; codecs=\"avc1.640028\"")
            return SabrNgSourceSpec(
                videoId = "vidS1", info = info("https://127.0.0.1:9/sabr", audio, video),
                audioFormat = audio, videoFormat = video, localization = Localization("en", "US"),
                audioInitializationData = sidx(durationsMs = listOf(5000, 5000, 5000)),
                videoInitializationData = sidx(durationsMs = listOf(10000, 10000)),
                poToken = byteArrayOf(7, 7),
            )
        }
    }

    private fun tempDir(): File = File(ctx.cacheDir, "sabr-slice1-${System.nanoTime()}").apply { mkdirs() }

    // ---- spec routing over a real parsed timeline ----

    @Test fun specRoutesTimelinesAndInitDataByItag() {
        val spec = spec()
        assertEquals(3, spec.audioTimeline.getEndSequence()); assertEquals(2, spec.videoTimeline.getEndSequence())
        assertEquals(spec.audioTimeline, spec.timelineFor(140)); assertEquals(spec.videoTimeline, spec.timelineFor(137))
        assertNull(spec.timelineFor(999))
        assertEquals(5000L, spec.audioTimeline.getStartMs(2)); assertEquals(10000L, spec.audioTimeline.getEndMs(2))
        assertEquals(2, spec.audioTimeline.getSequenceAt(6000)); assertEquals(1, spec.audioTimeline.getSequenceAt(0))
        val audioInit = spec.getInitializationData(140)!!; assertTrue(audioInit.contentEquals(sidx(durationsMs = listOf(5000, 5000, 5000))))
        assertNull(spec.getInitializationData(999))
        // Defensive copies: mutating a returned array does not corrupt the spec.
        audioInit[0] = 99; assertTrue(spec.getInitializationData(140)!![0] != 99.toByte())
        assertEquals(maxOf(spec.audioFormat.getApproxDurationMs(), spec.videoFormat.getApproxDurationMs()), spec.getDurationMs())
        assertEquals("vidS1", spec.videoId); assertTrue(spec.poToken!!.contentEquals(byteArrayOf(7, 7)))
    }

    // ---- session playhead logic (no pump) ----

    @Test fun sessionPlayheadSeekAndTimeouts() {
        val spec = spec()
        val session = SabrNgSession(spec, tempDir(), null) { null }
        assertEquals(0L, session.currentPlayheadMs())
        session.setPlayerTimeMs(3000); assertEquals(3000L, session.currentPlayheadMs())
        session.setPlayerTimeMs(-100); assertEquals(0L, session.currentPlayheadMs())
        // Supplier wins when ahead, segment fallback wins when supplier is dead (null).
        val supplied = SabrNgSession(spec, tempDir(), null) { 8000L }
        supplied.setPlayerTimeMs(3000); assertEquals(8000L, supplied.currentPlayheadMs())
        val throwing = SabrNgSession(spec, tempDir(), null) { throw RuntimeException("dead player") }
        throwing.setPlayerTimeMs(4000); assertEquals(4000L, throwing.currentPlayheadMs())
        // updatePlayheadForSegment is monotonic.
        session.updatePlayheadForSegment(140, 2); assertEquals(5000L, session.currentPlayheadMs())
        session.updatePlayheadForSegment(140, 1); assertEquals(5000L, session.currentPlayheadMs())
        session.updatePlayheadForSegment(999, 1) // unknown itag: no crash, no move
        // Seek clamps and repositions both tracks.
        session.requestSeek(12000); assertEquals(12000L, session.currentPlayheadMs())
        session.requestSeek(-5); assertEquals(0L, session.currentPlayheadMs())
        session.setPlaybackRate(0f); session.setPlaybackRate(-1f); session.setPlaybackRate(1.5f)
        assertEquals(false, session.isBufferedFarAheadOfPlayhead())
        // Initialization comes straight from the spec; media times out to null without a pump.
        assertTrue(session.getInitialization(140)!!.contentEquals(spec.getInitializationData(140)!!))
        assertNull(session.getInitialization(999))
        val started = System.currentTimeMillis()
        assertNull(session.getMediaSegment(140, 1, 250))
        assertTrue(System.currentTimeMillis() - started >= 200)
        session.stop(); session.stop() // idempotent
    }

    @Test fun sessionPumpFailureTerminatesSegmentWaiters() {
        val spec = spec()
        val session = SabrNgSession(spec, tempDir(), null, null)
        session.start(); session.start() // second start is a no-op while running
        // The pump's first request hits 127.0.0.1:9 (connection refused) and fails the session;
        // a blocked segment waiter must then throw the terminal IOException, not hang.
        val err = assertFailsWith<IOException> { session.getMediaSegment(140, 1, 30_000) }
        assertTrue(err.message!!.isNotBlank())
        // Once terminal, later waiters fail immediately with the same error.
        assertFailsWith<IOException> { session.getMediaSegment(137, 1, 30_000) }
        session.stop()
    }

    @Test fun interruptedSegmentWaiterThrows() {
        val session = SabrNgSession(spec(), tempDir(), null, null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val waiter = Thread {
            try { session.getMediaSegment(140, 1, 30_000) } catch (e: Throwable) { failure.set(e) }
        }
        waiter.start(); Thread.sleep(300); waiter.interrupt(); waiter.join(10_000)
        assertTrue(failure.get() is IOException, "expected IOException, got ${failure.get()}")
        assertTrue(Thread.currentThread().isInterrupted.not())
        session.stop()
    }

    // ---- segment data source ----

    @Test fun segmentDataSourceServesInitAndTimesOutMedia() {
        val spec = spec()
        val session = SabrNgSession(spec, tempDir(), null, null)
        val source = SabrNgSegmentDataSource(session, 200)
        // Init segment: full read, then end-of-input.
        val initSpec = DataSpec.Builder().setUri("sabrseg://140/init".toUri()).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        val length = source.open(initSpec)
        val expected = spec.getInitializationData(140)!!
        assertEquals(expected.size.toLong(), length); assertEquals(initSpec.uri, source.uri)
        val buffer = ByteArray(expected.size)
        assertEquals(expected.size, source.read(buffer, 0, buffer.size)); assertTrue(buffer.contentEquals(expected))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(0, source.read(buffer, 0, 0))
        source.close()
        // Init with an offset position.
        val offsetSpec = DataSpec.Builder().setUri("sabrseg://137/init".toUri()).setPosition(4).setLength(C.LENGTH_UNSET.toLong()).build()
        assertEquals((spec.getInitializationData(137)!!.size - 4).toLong(), source.open(offsetSpec))
        val tail = ByteArray(4); assertEquals(4, source.read(tail, 0, 4))
        source.close()
        // Unknown itag init: served as empty, not an error.
        val unknownInit = DataSpec.Builder().setUri("sabrseg://999/init".toUri()).build()
        assertEquals(0L, source.open(unknownInit))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        source.close()
        // Media segment without a pump: times out, serves as empty.
        val mediaSpec = DataSpec.Builder().setUri("sabrseg://140/1".toUri()).build()
        assertEquals(0L, source.open(mediaSpec))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        source.close(); source.close()
        session.stop()
    }

    @Test fun segmentDataSourceRejectsBadUris() {
        val session = SabrNgSession(spec(), tempDir(), null, null)
        val source = SabrNgSegmentDataSource(session, 100)
        assertFailsWith<IOException> { source.open(DataSpec.Builder().setUri("sabrseg://abc/1".toUri()).build()) }
        assertFailsWith<IOException> { source.open(DataSpec.Builder().setUri("sabrseg://140/notanumber".toUri()).build()) }
        assertFailsWith<IOException> { source.open(DataSpec.Builder().setUri("sabrseg:///1".toUri()).build()) }
        session.stop()
    }
}
