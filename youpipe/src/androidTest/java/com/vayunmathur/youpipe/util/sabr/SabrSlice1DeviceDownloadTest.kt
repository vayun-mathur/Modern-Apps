package com.vayunmathur.youpipe.util.sabr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) device pass: the download helper end to end — token mint
 * (fake BotGuard VM), init fetch and media writeTracks over a fake UMP
 * server, then the mux failure cleanup when the written bytes are not a
 * real container.
 */
@RunWith(AndroidJUnit4::class)
class SabrSlice1DeviceDownloadTest {

    @Test fun downloadWritesTracksThenFailsMuxCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioInit = SabrSlice1DeviceFixtures.sidx(durationsMs = listOf(5000, 5000, 5000))
        val videoInit = SabrSlice1DeviceFixtures.sidx(durationsMs = listOf(10000, 10000))
        val media = ByteArray(48) { 0x2A }
        NewPipe.init(SabrSlice1DeviceMintTest.Companion.RoutingDownloader { request ->
            when {
                request.url.contains("youtube.com") && !request.url.contains("sabr") ->
                    Response(200, null, null, SabrSlice1DeviceMintTest.Companion.homePage(), request.url)
                else -> Response(
                    200, null, mapOf("Content-Type" to listOf("application/vnd.yt-ump")),
                    String(
                        SabrSlice1DeviceUmp.segmentParts(1, 140, init = true, seq = 0, media = audioInit) +
                            SabrSlice1DeviceUmp.segmentParts(2, 137, init = true, seq = 0, media = videoInit) +
                            SabrSlice1DeviceUmp.segmentParts(3, 140, init = false, seq = 1, media = media) +
                            SabrSlice1DeviceUmp.segmentParts(4, 140, init = false, seq = 2, media = media) +
                            SabrSlice1DeviceUmp.segmentParts(5, 140, init = false, seq = 3, media = media) +
                            SabrSlice1DeviceUmp.segmentParts(6, 137, init = false, seq = 1, media = media) +
                            SabrSlice1DeviceUmp.segmentParts(7, 137, init = false, seq = 2, media = media),
                        Charsets.ISO_8859_1,
                    ),
                    request.url,
                )
            }
        })
        // Flush any stale shared-provider initialization left by an earlier test in this
        // process: a failed task is dropped on first use, so the download below re-mints
        // against this test's downloader.
        runCatching { LocalDomPoTokenProvider.shared(context).getPoTokenBytes("flush") }
        val workDir = File(context.cacheDir, "sabr-dl-device").apply { mkdirs() }
        val output = File(workDir, "out.mp4")
        val info = SabrSlice1DeviceUmp.configInfo(
            SabrSlice1DeviceFixtures.format(140, "audio/mp4"),
            SabrSlice1DeviceFixtures.format(137, "video/mp4; codecs=\"avc1.640028\""),
        )
        var progressSeen = false
        try {
            SabrNgDownloadHelper.download(context, "vidS1", 137, 140, null, info, workDir, output) { progressSeen = true }
            fail("muxing garbage segments must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("SABR download failed"))
        }
        assertTrue("no output may survive a failed download", !output.exists())
    }

    @Test fun generatorWithInterpreterUrlFetchesVmThenMints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(SabrSlice1DeviceMintTest.Companion.RoutingDownloader { request ->
            if (request.url.contains("interp.js")) {
                Response(200, null, null, SabrSlice1DeviceMintTest.Companion.FAKE_VM_JS, request.url)
            } else {
                Response(200, null, null, "", request.url)
            }
        })
        val bootstrap = YoutubePageAttestationBootstrap(
            visitorData = "visitor-data", clientName = "WEB", clientVersion = "2.5.1",
            binding = YoutubePoTokenBinding.CONTENT, eventId = "evt-1",
            challenge = SabrAttChallengeData("PROG", "MyVM", null, "https://example.com/interp.js"),
        )
        val generator = LocalDomPoTokenGenerator.create(context, bootstrap)
        assertTrue(generator.mint("vid-url").contentEquals(byteArrayOf(1, 2, 3, 4)))
        generator.close()
    }

    @Test fun generatorFailsOnGarbageIntegrityResponse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(object : org.schabi.newpipe.extractor.downloader.Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response =
                if (request.url.contains("jnn-pa.googleapis.com")) {
                    Response(200, null, null, "not an integrity response", request.url)
                } else {
                    Response(200, null, null, "", request.url)
                }
        })
        val bootstrap = YoutubePageAttestationBootstrap(
            visitorData = "visitor-data", clientName = "WEB", clientVersion = "2.5.1",
            binding = YoutubePoTokenBinding.CONTENT, eventId = "evt-1",
            challenge = SabrAttChallengeData("PROG", "MyVM", SabrSlice1DeviceMintTest.Companion.FAKE_VM_JS, null),
        )
        try {
            LocalDomPoTokenGenerator.create(context, bootstrap)
            fail("garbage integrity response must fail initialization")
        } catch (expected: SabrProtocolException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
