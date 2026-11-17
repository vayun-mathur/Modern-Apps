package com.vayunmathur.youpipe.network.sabr

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * media3 [MediaSource] that publishes a generated DASH manifest (with exact SABR segment counts)
 * and serves segments from a session-based [SabrNgSession] via [SabrNgSegmentDataSource].
 *
 * The heavy play-time work — minting the content PO token and fetching each format's
 * initialization/segment index to build the [SabrNgSourceSpec] — is deferred to a dedicated
 * background thread started from [prepareSourceInternal]. This keeps
 * `MediaSource.Factory.createMediaSource` (called by ExoPlayer on the app main thread during
 * `setMediaItems`) non-blocking, while preserving the known-working play-time ordering
 * (token minted lazily on first source open, not prewarmed).
 */
@OptIn(UnstableApi::class)
class SabrNgDashMediaSource(
    context: Context,
    private val mediaItem: MediaItem,
    private val videoId: String,
    private val specSupplier: () -> SabrNgSourceSpec,
    private val tokenMinter: ((Boolean) -> ByteArray?)? = null,
    // Nullable: a missing/dead player read must NOT coalesce to 0 and shadow the
    // segment-tracked fallback (that froze the playhead and metered sessions to death).
    private val playheadMsProvider: (() -> Long?)? = null,
) : CompositeMediaSource<Int>() {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var session: SabrNgSession? = null

    @Volatile
    private var childSource: DashMediaSource? = null

    @Volatile
    private var buildError: IOException? = null

    @Volatile
    private var released = false

    private var playbackHandler: Handler? = null
    private var buildThread: Thread? = null
    private val lifecycleLock = Any()

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        // prepareSourceInternal runs on ExoPlayer's playback thread (not main); capture its looper
        // so the built child source can be prepared back on it once the background build finishes.
        val handler = Handler(checkNotNull(Looper.myLooper()))
        playbackHandler = handler
        val thread = Thread({ buildAndPrepare(handler) }, "SabrNgSourceOpen-$videoId")
        thread.isDaemon = true
        buildThread = thread
        thread.start()
    }

    private fun buildAndPrepare(handler: Handler) {
        try {
            // Heavy, off-main: mint the PO token (WebView/DOM JS) and fetch init segments.
            val spec = specSupplier()
            val spoolDir = File(appContext.cacheDir, "sabrng").apply { mkdirs() }
            val builtSession = SabrNgSession(spec, spoolDir, tokenMinter, playheadMsProvider)
            val durationMs = spec.getDurationMs()
            val dataSourceFactory = DataSource.Factory {
                SabrNgSegmentDataSource(builtSession, SEGMENT_TIMEOUT_MS)
            }
            val manifest = buildManifest(spec, durationMs)
            val builtChild = DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(dataSourceFactory),
                /* manifestDataSourceFactory= */ null
            ).createMediaSource(manifest, mediaItem)
            // Publish the built session/child under the lifecycle lock so a concurrent
            // releaseSourceInternal on the playback thread can always tear the session down,
            // even if it races with this background build completing.
            synchronized(lifecycleLock) {
                if (released) {
                    builtSession.stop()
                    return
                }
                session = builtSession
                childSource = builtChild
            }
            handler.post {
                if (released) {
                    return@post
                }
                builtSession.start()
                prepareChildSource(0, builtChild)
            }
        } catch (e: java.io.IOException) {
            buildError = e
        } catch (e: IllegalStateException) {
            buildError = IOException("Failed to open SABR media source for $videoId", e)
        } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            buildError = IOException("Failed to open SABR media source for $videoId", e)
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        buildError?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(
        id: Int?,
        mediaSource: MediaSource,
        timeline: Timeline
    ) {
        refreshSourceInfo(timeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val child = checkNotNull(childSource)
        if (startPositionUs > 0) {
            session?.requestSeek(startPositionUs / MICROS_PER_MILLISECOND)
        }
        val childPeriod = child.createPeriod(id, allocator, startPositionUs)
        return SabrNgMediaPeriod(childPeriod)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val period = mediaPeriod as SabrNgMediaPeriod
        childSource?.releasePeriod(period.child)
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        val sessionToStop: SabrNgSession?
        synchronized(lifecycleLock) {
            released = true
            sessionToStop = session
            session = null
            childSource = null
        }
        buildThread?.interrupt()
        buildThread = null
        playbackHandler?.removeCallbacksAndMessages(null)
        playbackHandler = null
        sessionToStop?.stop()
    }

    private inner class SabrNgMediaPeriod(val child: MediaPeriod) : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            child.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) {
                        callback.onPrepared(this@SabrNgMediaPeriod)
                    }

                    override fun onContinueLoadingRequested(source: MediaPeriod) {
                        callback.onContinueLoadingRequested(this@SabrNgMediaPeriod)
                    }
                },
                positionUs
            )
        }

        @Throws(IOException::class)
        override fun maybeThrowPrepareError() = child.maybeThrowPrepareError()

        override fun getTrackGroups(): TrackGroupArray = child.trackGroups

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
            child.getStreamKeys(trackSelections)

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long =
            child.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
            )

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
            child.discardBuffer(positionUs, toKeyframe)

        override fun readDiscontinuity(): Long = child.readDiscontinuity()

            override fun seekToUs(positionUs: Long): Long {
            session?.requestSeek(maxOf(0, positionUs) / MICROS_PER_MILLISECOND)
            return child.seekToUs(positionUs)
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long = child.getAdjustedSeekPositionUs(positionUs, seekParameters)

        override fun getBufferedPositionUs(): Long = child.bufferedPositionUs

        override fun getNextLoadPositionUs(): Long = child.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
            child.continueLoading(loadingInfo)

        override fun isLoading(): Boolean = child.isLoading

        override fun reevaluateBuffer(positionUs: Long) = child.reevaluateBuffer(positionUs)
    }

    private companion object {
        private const val MICROS_PER_MILLISECOND = 1000L
        private const val MILLIS_PER_SECOND = 1000L
        private const val DASH_TIMESCALE = 1000
        private const val MAX_EXACT_SEGMENT_COUNT = 10_000
        private const val SEGMENT_TIMEOUT_MS = 30_000L

        @Throws(IOException::class)
        private fun buildManifest(spec: SabrNgSourceSpec, durationMs: Long): DashManifest {
            val mpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" " +
                "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"" +
                formatDuration(durationMs) + "\">" +
                "<Period id=\"0\" start=\"PT0S\">" +
                adaptationSet(spec.videoTimeline, spec.videoFormat, C.TRACK_TYPE_VIDEO) +
                adaptationSet(spec.audioTimeline, spec.audioFormat, C.TRACK_TYPE_AUDIO) +
                "</Period></MPD>"
            try {
                return DashManifestParser().parse(
                    "sabr://${spec.videoId}".toUri(),
                    ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8))
                )
            } catch (e: IOException) {
                throw IOException("Error when parsing generated SABR DASH manifest", e)
            }
        }

        private fun adaptationSet(
            timeline: YoutubeSabrFormatTimeline,
            format: YoutubeSabrInfo.Format,
            trackType: Int
        ): String {
            val mime = containerMimeType(format)
            val codecs = codecs(format)
            val contentType = if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "video"
            val builder = StringBuilder()
                .append("<AdaptationSet id=\"").append(format.getItag())
                .append("\" contentType=\"").append(contentType)
                .append("\" mimeType=\"").append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">")
                .append("<Representation id=\"").append(format.getItag())
                .append("\" bandwidth=\"").append(maxOf(1, format.getBitrate())).append("\"")
            if (!codecs.isNullOrEmpty()) {
                builder.append(" codecs=\"").append(xml(codecs)).append("\"")
            }
            if (trackType == C.TRACK_TYPE_VIDEO) {
                builder.append(" width=\"").append(maxOf(1, format.getWidth()))
                    .append("\" height=\"").append(maxOf(1, format.getHeight())).append("\"")
            } else {
                builder.append(" audioSamplingRate=\"48000\"")
            }
            builder.append(">")
                .append("<BaseURL>sabrseg://").append(format.getItag()).append("/</BaseURL>")
                .append(segmentTemplate(timeline, format))
                .append("</Representation></AdaptationSet>")
            return builder.toString()
        }

        private fun segmentTemplate(
            timeline: YoutubeSabrFormatTimeline,
            format: YoutubeSabrInfo.Format
        ): String {
            val endSegment = timeline.getEndSequence()
            check(endSegment in 1..MAX_EXACT_SEGMENT_COUNT) {
                "Invalid exact SABR segment count: itag=${format.getItag()}, count=$endSegment"
            }
            val builder = StringBuilder()
                .append("<SegmentTemplate timescale=\"$DASH_TIMESCALE\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"\$Number\$\">")
                .append("<SegmentTimeline>")
            for (sequence in 1..endSegment) {
                val startMs = timeline.getStartMs(sequence)
                val endMs = timeline.getEndMs(sequence)
                val durationMs = maxOf(1, endMs - startMs)
                builder.append("<S t=\"").append(maxOf(0, startMs))
                    .append("\" d=\"").append(durationMs).append("\"/>")
            }
            return builder.append("</SegmentTimeline></SegmentTemplate>").toString()
        }

        private fun formatDuration(durationMs: Long): String {
            val safeDurationMs = maxOf(1, durationMs)
            return "PT" + (safeDurationMs / MILLIS_PER_SECOND) + "." +
                String.format(Locale.US, "%03d", safeDurationMs % MILLIS_PER_SECOND) + "S"
        }

        private fun containerMimeType(format: YoutubeSabrInfo.Format): String {
            val mime = format.getMimeType()
            if (mime.isNullOrEmpty()) {
                return if (format.isAudio()) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4
            }
            val semicolon = mime.indexOf(';')
            return if (semicolon >= 0) mime.substring(0, semicolon).trim() else mime.trim()
        }

        private fun codecs(format: YoutubeSabrInfo.Format): String? {
            val mime = format.getMimeType() ?: return null
            val start = mime.indexOf("codecs=")
            if (start < 0) {
                return null
            }
            return mime.substring(start + "codecs=".length).replace("\"", "").trim()
        }

        private fun xml(value: String): String =
            value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
