package com.vayunmathur.youpipe.util.sabr

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader

/**
 * Slice-1 (sabr/) second pass: a hand-built UMP response (media-header /
 * media / media-end parts over protobuf headers) served through a fake
 * NewPipe downloader, driving the coordinator's retry loop, SessionStore's
 * init-segment fetch, the session pump's segment cache, and the segment
 * data source's streamed read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SabrSlice1UmpServerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    companion object {
        private fun umpInt(value: Int): ByteArray = when {
            value < 128 -> byteArrayOf(value.toByte())
            else -> byteArrayOf((128 + value % 64).toByte(), (value / 64).toByte())
        }
        fun umpPart(type: Int, payload: ByteArray): ByteArray = umpInt(type) + umpInt(payload.size) + payload

        fun headerProto(headerId: Int, itag: Int, init: Boolean, seq: Int, contentLength: Int): ByteArray =
            SabrProto.Writer().apply {
                writeInt32(1, headerId); writeInt32(3, itag); writeBool(8, init)
                writeInt32(9, seq); writeUInt64(14, contentLength.toLong())
            }.toByteArray()

        /** One complete segment: MEDIA_HEADER + MEDIA + MEDIA_END parts. */
        fun segmentParts(headerId: Int, itag: Int, init: Boolean, seq: Int, media: ByteArray): ByteArray =
            umpPart(20, headerProto(headerId, itag, init, seq, media.size)) +
                umpPart(21, byteArrayOf(headerId.toByte()) + media) +
                umpPart(22, byteArrayOf(headerId.toByte()))

        fun umpDownloader(body: () -> ByteArray): Downloader = object : Downloader() {
            override fun execute(request: Request): Response = Response(
                200, null, mapOf("Content-Type" to listOf("application/vnd.yt-ump")),
                String(body(), Charsets.ISO_8859_1), request.url,
            )
        }

        fun configInfo(vararg formats: YoutubeSabrInfo.Format): YoutubeSabrInfo =
            YoutubeSabrInfo("vidS1", "cpn", "1.0", null, "https://127.0.0.1:9/sabr", "AAAA", formats.toList(), null)
    }

    private fun tempDir(): File = File(ctx.cacheDir, "sabr-ump-${System.nanoTime()}").apply { mkdirs() }

    @Test fun coordinatorRetriesUntilProgress() {
        NewPipe.init(umpDownloader { ByteArray(0) })
        val session = YoutubeSabrSession(configInfo(SabrSlice1ExtraTest.format(140, "audio/mp4")), null)
        val observedBackoffs = mutableListOf<Long>()
        val coordinator = SabrRequestCoordinator(session, SabrAttestationRetryHandler("vidS1", null)) { observedBackoffs += it }
        var checkerCalls = 0
        val result = coordinator.request(
            YoutubeSabrRequest.preparation(0, listOf(SabrSlice1ExtraTest.format(140, "audio/mp4"))),
            SabrStreamingResponseReader.SegmentConsumer { },
        ) { ++checkerCalls > 1 }
        assertEquals(0, result.getSegmentCount()); assertEquals(2, checkerCalls)
        assertTrue(observedBackoffs.isNotEmpty())
    }

    @Test fun coordinatorHonorsServerBackoffBetweenAttempts() {
        var requests = 0
        NewPipe.init(umpDownloader {
            requests++
            if (requests == 1) {
                // NEXT_REQUEST_POLICY (35) with field 4 = backoff 400ms, no media.
                val policy = SabrProto.Writer().apply { writeInt32(4, 400) }.toByteArray()
                umpPart(35, policy)
            } else {
                ByteArray(0)
            }
        })
        val session = YoutubeSabrSession(configInfo(SabrSlice1ExtraTest.format(140, "audio/mp4")), null)
        val observed = mutableListOf<Long>()
        val coordinator = SabrRequestCoordinator(session, SabrAttestationRetryHandler("vidS1", null)) { observed += it }
        var checkerCalls = 0
        val started = System.currentTimeMillis()
        val result = coordinator.request(
            YoutubeSabrRequest.preparation(0, listOf(SabrSlice1ExtraTest.format(140, "audio/mp4"))),
            SabrStreamingResponseReader.SegmentConsumer { },
        ) { ++checkerCalls > 1 }
        assertEquals(0, result.getSegmentCount())
        assertTrue(System.currentTimeMillis() - started >= 300, "backoff was not honored")
        assertTrue(observed.any { it > 0 }, "backoff observer never saw the server backoff: $observed")
    }

    @Test fun sessionStoreFetchesInitSegmentsOverUmp() {
        val audioInit = SabrSlice1ExtraTest.sidx(durationsMs = listOf(5000, 5000, 5000))
        val videoInit = SabrSlice1ExtraTest.sidx(durationsMs = listOf(10000, 10000))
        NewPipe.init(umpDownloader {
            segmentParts(1, 140, init = true, seq = 0, media = audioInit) +
                segmentParts(2, 137, init = true, seq = 0, media = videoInit)
        })
        val audio = SabrSlice1ExtraTest.format(140, "audio/mp4")
        val video = SabrSlice1ExtraTest.format(137, "video/mp4; codecs=\"avc1.640028\"")
        val spec = SabrNgSessionStore.createSourceSpec(
            "vidS1", 137, 140, null, configInfo(audio, video), null, Localization("en", "US"), null,
        )
        assertEquals(3, spec.audioTimeline.getEndSequence()); assertEquals(2, spec.videoTimeline.getEndSequence())
        assertTrue(spec.getInitializationData(140)!!.contentEquals(audioInit))
        assertTrue(spec.getInitializationData(137)!!.contentEquals(videoInit))
    }

    @org.junit.Test(timeout = 90_000) fun sessionPumpDeliversSegmentsToCacheAndDataSource() {
        val audioInit = SabrSlice1ExtraTest.sidx(durationsMs = listOf(5000, 5000, 5000))
        val mediaBytes = ByteArray(60) { (it + 1).toByte() }
        NewPipe.init(umpDownloader {
            segmentParts(1, 140, init = true, seq = 0, media = audioInit) +
                segmentParts(2, 140, init = false, seq = 1, media = mediaBytes) +
                segmentParts(3, 140, init = false, seq = 2, media = mediaBytes)
        })
        val audio = SabrSlice1ExtraTest.format(140, "audio/mp4")
        val video = SabrSlice1ExtraTest.format(137, "video/mp4; codecs=\"avc1.640028\"")
        val spec = SabrNgSourceSpec(
            videoId = "vidS1", info = configInfo(audio, video), audioFormat = audio, videoFormat = video,
            localization = Localization("en", "US"),
            audioInitializationData = SabrSlice1ExtraTest.sidx(durationsMs = listOf(5000, 5000, 5000)),
            videoInitializationData = SabrSlice1ExtraTest.sidx(durationsMs = listOf(10000, 10000)),
            poToken = null,
        )
        val session = SabrNgSession(spec, tempDir(), null, null)
        session.start()
        try {
            // Poll the cache through the blocking getter with short timeouts.
            var segment: org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment? = null
            val deadline = System.currentTimeMillis() + 8_000
            while (segment == null && System.currentTimeMillis() < deadline) {
                segment = session.getMediaSegment(140, 1, 300)
            }
            assertNotNull(segment, "pump never delivered segment 1 to the cache")
            assertTrue(segment!!.getData().contentEquals(mediaBytes))
            assertNotNull(session.getMediaSegment(140, 2, 300))
            // The init segment landed in the init cache too.
            assertTrue(session.getInitialization(140)!!.contentEquals(audioInit))
            // Streamed read through the media3 data source.
            val source = SabrNgSegmentDataSource(session, 2_000)
            val dataSpec = DataSpec.Builder().setUri("sabrseg://140/1".toUri()).build()
            assertEquals(mediaBytes.size.toLong(), source.open(dataSpec))
            val buffer = ByteArray(mediaBytes.size)
            var readTotal = 0
            while (readTotal < buffer.size) {
                val read = source.read(buffer, readTotal, buffer.size - readTotal)
                if (read == C.RESULT_END_OF_INPUT) break
                check(read > 0) { "segment stream returned $read before end of segment" }
                readTotal += read
            }
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
            assertEquals(mediaBytes.size, readTotal); assertTrue(buffer.contentEquals(mediaBytes))
            source.close()
        } finally {
            session.stop()
        }
    }
}
