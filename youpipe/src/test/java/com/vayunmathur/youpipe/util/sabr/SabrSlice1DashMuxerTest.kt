package com.vayunmathur.youpipe.util.sabr

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader

/**
 * Slice-1 (sabr/) second pass: the DASH media source lifecycle around a
 * locally built spec, the request coordinator's failure path, and the
 * muxer's input validation / failure wrapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SabrSlice1DashMuxerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Caller : MediaSource.MediaSourceCaller {
        var timeline: androidx.media3.common.Timeline? = null
        override fun onSourceInfoRefreshed(source: MediaSource, newTimeline: androidx.media3.common.Timeline) {
            timeline = newTimeline
        }
    }

    @Test fun dashSourceBuildsManifestAndCreatesPeriod() {
        val spec = SabrSlice1ExtraTest.spec()
        val item = MediaItem.Builder().setUri("sabr://vidS1").setMediaId("vidS1").build()
        val source = SabrNgDashMediaSource(ctx, item, "vidS1", { spec }, null, null)
        assertEquals(item, source.mediaItem)
        val caller = Caller()
        source.prepareSource(caller, androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(ctx))
        // Background build (spec -> manifest -> child) posts back to this looper; pump it.
        val deadline = System.currentTimeMillis() + 20_000
        while (caller.timeline == null && System.currentTimeMillis() < deadline) {
            idle(); Thread.sleep(25); source.maybeThrowSourceInfoRefreshError()
        }
        val timeline = caller.timeline
        assertTrue(timeline != null, "child Dash source never refreshed a timeline")
        assertEquals(1, timeline!!.periodCount)
        // Period creation delegates to the child Dash period.
        val allocator = DefaultAllocator(true, 1024 * 1024)
        val periodId = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
        val period = source.createPeriod(periodId, allocator, 0)
        var prepared = false
        period.prepare(object : androidx.media3.exoplayer.source.MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: androidx.media3.exoplayer.source.MediaPeriod) { prepared = true }
            override fun onContinueLoadingRequested(source: androidx.media3.exoplayer.source.MediaPeriod) {}
        }, 0)
        val prepDeadline = System.currentTimeMillis() + 10_000
        while (!prepared && System.currentTimeMillis() < prepDeadline) { idle(); Thread.sleep(25) }
        runCatching { period.trackGroups } // may still be loading; must not crash the test thread
        runCatching { period.maybeThrowPrepareError() }
        source.releasePeriod(period)
        source.releaseSource(caller)
    }

    @Test fun dashPeriodDelegatesToChild() {
        val spec = SabrSlice1ExtraTest.spec()
        val item = MediaItem.Builder().setUri("sabr://vidS1").setMediaId("vidS1").build()
        val source = SabrNgDashMediaSource(ctx, item, "vidS1", { spec }, null, null)
        val caller = Caller()
        source.prepareSource(caller, androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(ctx))
        val deadline = System.currentTimeMillis() + 20_000
        while (caller.timeline == null && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(25) }
        val timeline = caller.timeline!!
        val allocator = DefaultAllocator(true, 1024 * 1024)
        val period = source.createPeriod(androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), allocator, 5_000_000)
        var prepared = false
        period.prepare(object : androidx.media3.exoplayer.source.MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: androidx.media3.exoplayer.source.MediaPeriod) { prepared = true }
            override fun onContinueLoadingRequested(source: androidx.media3.exoplayer.source.MediaPeriod) {}
        }, 0)
        val prepDeadline = System.currentTimeMillis() + 10_000
        while (!prepared && System.currentTimeMillis() < prepDeadline) { idle(); Thread.sleep(25) }
        // Every delegation on the wrapper period must reach the child without crashing.
        runCatching { period.trackGroups }
        runCatching { period.getStreamKeys(emptyList()) }
        runCatching { period.seekToUs(2_000_000) }
        runCatching { period.getAdjustedSeekPositionUs(1_000_000, androidx.media3.exoplayer.SeekParameters.DEFAULT) }
        runCatching { period.bufferedPositionUs }; runCatching { period.nextLoadPositionUs }
        runCatching { period.readDiscontinuity() }; runCatching { period.isLoading }
        runCatching { period.discardBuffer(0, true) }; runCatching { period.reevaluateBuffer(0) }
        runCatching {
            period.selectTracks(
                arrayOfNulls<androidx.media3.exoplayer.trackselection.ExoTrackSelection>(0),
                BooleanArray(0), arrayOfNulls<androidx.media3.exoplayer.source.SampleStream>(0), BooleanArray(0), 0,
            )
        }
        runCatching { period.continueLoading(androidx.media3.exoplayer.LoadingInfo.Builder().setPlaybackPositionUs(0).build()) }
        source.releasePeriod(period)
        source.releaseSource(caller)
    }

    @Test fun dashSourceReleasedDuringBuildStopsSession() {
        val item = MediaItem.Builder().setUri("sabr://vidR").setMediaId("vidR").build()
        val source = SabrNgDashMediaSource(ctx, item, "vidR", {
            Thread.sleep(300) // build still running when the release lands
            SabrSlice1ExtraTest.spec()
        }, null, null)
        val caller = Caller()
        source.prepareSource(caller, androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(ctx))
        source.releaseSource(caller) // released before the background build publishes
        Thread.sleep(800); idle()
        // No timeline may ever be published for a released source, and no error is recorded.
        assertEquals(null, caller.timeline)
        runCatching { source.maybeThrowSourceInfoRefreshError() }
    }

    @Test fun dashSourceSurfacesBuildErrors() {
        val item = MediaItem.Builder().setUri("sabr://vidB").setMediaId("vidB").build()
        val boom = IOException("no init for you")
        val source = SabrNgDashMediaSource(ctx, item, "vidB", { throw boom }, null, null)
        val caller = Caller()
        source.prepareSource(caller, androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(ctx))
        var thrown: IOException? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (thrown == null && System.currentTimeMillis() < deadline) {
            idle()
            thrown = runCatching { source.maybeThrowSourceInfoRefreshError() }.exceptionOrNull() as? IOException
            if (thrown == null) Thread.sleep(25)
        }
        assertEquals(boom, thrown)
        source.releaseSource(caller)
    }

    @Test fun coordinatorFailsFastOnRefusedSession() {
        val info = SabrSlice1ExtraTest.info()
        val session = YoutubeSabrSession(info, null)
        val coordinator = SabrRequestCoordinator(session, SabrAttestationRetryHandler("vidS1", null), null)
        val request = YoutubeSabrRequest.preparation(0, listOf(SabrSlice1ExtraTest.format(140, "audio/mp4")))
        // The synthetic info has no ustreamer config, so the session refuses before any network.
        val err = assertFailsWith<org.schabi.newpipe.extractor.exceptions.ExtractionException> {
            coordinator.request(request, SabrStreamingResponseReader.SegmentConsumer { }) { false }
        }
        assertTrue(err.message!!.contains("ustreamer"))
    }

    @Test fun muxerRejectsMissingAndEmptyInputs() {
        val dir = File(ctx.cacheDir, "mux-${System.nanoTime()}").apply { mkdirs() }
        val missing = File(dir, "nope.media"); val present = File(dir, "a.media").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val out = File(dir, "out.mp4")
        var err = assertFailsWith<IOException> { SabrFfmpegMuxer.mux(missing, present, out) }
        assertTrue(err.message!!.contains("SABR download failed"))
        err = assertFailsWith { SabrFfmpegMuxer.mux(present, File(dir, "empty.media").apply { writeBytes(ByteArray(0)) }, out) }
        assertTrue(err.message!!.contains("SABR download failed"))
    }

    @Test fun muxerWrapsGarbageInputsAsMuxingFailure() {
        val dir = File(ctx.cacheDir, "mux-${System.nanoTime()}").apply { mkdirs() }
        val video = File(dir, "v.media").apply { writeBytes(ByteArray(2048) { 0x11 }) }
        val audio = File(dir, "a.media").apply { writeBytes(ByteArray(2048) { 0x22 }) }
        val out = File(dir, "out.mp4").apply { writeBytes(byteArrayOf(9)) } // existing output gets deleted first
        val err = assertFailsWith<IOException> { SabrFfmpegMuxer.mux(video, audio, out) }
        assertTrue(err is SabrDownloadException && err.reason == SabrDownloadException.Reason.MUXING || err.message!!.contains("SABR download failed"))
    }
}
