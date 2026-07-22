package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SabrDashMediaSource extends CompositeMediaSource<Integer> {
    private static final String TAG = "SabrDashMediaSource";
    private static final long SEEK_FORWARD_SYNC_TOLERANCE_US = 2_000_000L;
    private static final long START_POSITION_FORWARD_SNAP_US = 500_000L;
    private static final long END_SEEK_BACKOFF_US = 1_000L;

    private final MediaItem mediaItem;
    private final SabrSourceSpec spec;
    private final SabrSessionHandle sessionHandle;
    private final Localization localization;
    private final YoutubeSabrStreamState manifestState;
    private final long durationUs;
    private final DashMediaSource childSource;
    private final PlaybackState playbackState = new PlaybackState();
    public SabrDashMediaSource(@NonNull final Context context,
                               @NonNull final MediaItem mediaItem,
                               @NonNull final SabrSourceSpec spec) throws IOException {
        this.mediaItem = mediaItem;
        this.spec = spec;
        try {
            this.localization = spec.getLocalization();
            this.manifestState = spec.newStreamState();
            if (!manifestState.hasSegmentIndex(spec.getAudioFormat())
                    || !manifestState.hasSegmentIndex(spec.getVideoFormat())) {
                throw new IOException("Refusing to publish guessed SABR DASH timeline for "
                        + spec.getVideoId());
            }
            this.sessionHandle = new SabrSessionHandle(context, spec);
            this.playbackState.setReaderOwner(this);
            final long durationMs = spec.getDurationMs();
            this.durationUs = durationMs > 0 ? durationMs * 1000L : C.TIME_UNSET;
            final DataSource.Factory sabrDataSourceFactory =
                    () -> new SabrSegmentDataSource(sessionHandle, playbackState.getReaderOwner(),
                            localization, /* prependInit= */ false);
            final DashManifest manifest = buildManifest(spec, manifestState, durationMs);
            this.childSource = new DashMediaSource.Factory(
                    new DefaultDashChunkSource.Factory(sabrDataSourceFactory),
                    /* manifestDataSourceFactory= */ null)
                    .createMediaSource(manifest, mediaItem);
            Log.d(TAG, "create source video=" + spec.getVideoId()
                    + " videoItag=" + spec.getVideoFormat().getItag()
                    + " audioItag=" + spec.getAudioFormat().getItag());
        } catch (final IOException | RuntimeException | Error e) {
            spec.discardPreparedSession();
            throw e;
        }
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return mediaItem;
    }

    @Override
    protected void prepareSourceInternal(@Nullable final TransferListener mediaTransferListener) {
        super.prepareSourceInternal(mediaTransferListener);
        prepareChildSource(0, childSource);
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Integer id,
                                              final MediaSource mediaSource,
                                              final Timeline timeline) {
        refreshSourceInfo(timeline);
    }

    @Override
    public MediaPeriod createPeriod(final MediaPeriodId id, final Allocator allocator,
                                    final long startPositionUs) {
        sessionHandle.onPeriodCreated(Math.max(0, startPositionUs / 1000L));
        try {
            final MediaPeriod child = childSource.createPeriod(id, allocator, startPositionUs);
            final SabrDashMediaPeriod period = new SabrDashMediaPeriod(child);
            playbackState.setReaderOwner(period);
            Log.d(TAG, "createPeriod video=" + spec.getVideoId()
                    + " startUs=" + startPositionUs);
            return period;
        } catch (final RuntimeException e) {
            sessionHandle.onPeriodReleased();
            throw e;
        }
    }

    @Override
    public void releasePeriod(final MediaPeriod mediaPeriod) {
        Log.d(TAG, "releasePeriod video=" + spec.getVideoId());
        final SabrDashMediaPeriod period = (SabrDashMediaPeriod) mediaPeriod;
        period.release();
        try {
            childSource.releasePeriod(period.child);
        } finally {
            sessionHandle.onPeriodReleased();
        }
    }

    @Override
    protected void releaseSourceInternal() {
        Log.d(TAG, "release source video=" + spec.getVideoId());
        sessionHandle.close();
    }

    private static DashManifest buildManifest(final SabrSourceSpec spec,
                                              final YoutubeSabrStreamState state,
                                              final long durationMs)
            throws IOException {
        final String mpd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" "
                + "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" "
                + "minBufferTime=\"PT1.5S\" mediaPresentationDuration=\""
                + formatDuration(durationMs) + "\">"
                + "<Period id=\"0\" start=\"PT0S\">"
                + adaptationSet(state, spec.getVideoFormat(), C.TRACK_TYPE_VIDEO)
                + adaptationSet(state, spec.getAudioFormat(), C.TRACK_TYPE_AUDIO)
                + "</Period></MPD>";
        try {
            return new DashManifestParser().parse(Uri.parse("sabr://" + spec.getVideoId()),
                    new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8)));
        } catch (final IOException e) {
            throw new IOException("Error when parsing generated SABR DASH manifest", e);
        }
    }

    private static String adaptationSet(final YoutubeSabrStreamState state,
                                        final YoutubeSabrFormat format,
                                        final int trackType) {
        final String mime = containerMimeType(format);
        final String codecs = codecs(format);
        final String contentType = trackType == C.TRACK_TYPE_AUDIO ? "audio" : "video";
        final StringBuilder builder = new StringBuilder()
                .append("<AdaptationSet id=\"").append(format.getItag())
                .append("\" contentType=\"").append(contentType)
                .append("\" mimeType=\"").append(xml(mime))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">")
                .append("<Representation id=\"").append(format.getItag())
                .append("\" bandwidth=\"").append(Math.max(1, format.getBitrate())).append("\"");
        if (codecs != null && !codecs.isEmpty()) {
            builder.append(" codecs=\"").append(xml(codecs)).append("\"");
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            builder.append(" width=\"").append(Math.max(1, format.getWidth()))
                    .append("\" height=\"").append(Math.max(1, format.getHeight())).append("\"");
        } else {
            builder.append(" audioSamplingRate=\"48000\"");
        }
        builder.append(">")
                .append("<BaseURL>sabrseg://").append(format.getItag()).append("/</BaseURL>")
                .append(segmentTemplate(state, format))
                .append("</Representation></AdaptationSet>");
        return builder.toString();
    }

    private static String segmentTemplate(final YoutubeSabrStreamState state,
                                          final YoutubeSabrFormat format) {
        final long endSegment = state.getEndSegment(format);
        if (endSegment <= 0 || endSegment > 10_000) {
            throw new IllegalStateException("Invalid exact SABR segment count: itag="
                    + format.getItag() + ", count=" + endSegment);
        }
        final StringBuilder builder = new StringBuilder()
                .append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
                .append("initialization=\"init\" media=\"$Number$\">")
                .append("<SegmentTimeline>");
        for (int sequence = 1; sequence <= endSegment; sequence++) {
            final long startMs = state.getSegmentStartMs(format, sequence);
            final long endMs = state.getSegmentEndMs(format, sequence);
            final long durationMs = Math.max(1, endMs - startMs);
            builder.append("<S t=\"").append(Math.max(0, startMs))
                    .append("\" d=\"").append(durationMs).append("\"/>");
        }
        return builder.append("</SegmentTimeline></SegmentTemplate>").toString();
    }

    private static String formatDuration(final long durationMs) {
        final long safeDurationMs = Math.max(1, durationMs);
        return "PT" + (safeDurationMs / 1000) + "."
                + String.format(java.util.Locale.US, "%03d", safeDurationMs % 1000) + "S";
    }

    private static String containerMimeType(final YoutubeSabrFormat format) {
        final String mime = format.getMimeType();
        if (mime == null || mime.isEmpty()) {
            return format.isAudio() ? MimeTypes.AUDIO_MP4 : MimeTypes.VIDEO_MP4;
        }
        final int semicolon = mime.indexOf(';');
        return semicolon >= 0 ? mime.substring(0, semicolon).trim() : mime.trim();
    }

    @Nullable
    private static String codecs(final YoutubeSabrFormat format) {
        final String mime = format.getMimeType();
        if (mime == null) {
            return null;
        }
        final int start = mime.indexOf("codecs=");
        if (start < 0) {
            return null;
        }
        return mime.substring(start + "codecs=".length()).replace("\"", "").trim();
    }

    private static String xml(final String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private final class SabrDashMediaPeriod implements MediaPeriod {
        private final MediaPeriod child;
        @Nullable
        private Callback callback;
        private long preparedPositionUs = C.TIME_UNSET;
        private boolean initialPositionApplied;

        SabrDashMediaPeriod(final MediaPeriod child) {
            this.child = child;
        }

        @Override
        public void prepare(final Callback cb, final long positionUs) {
            this.callback = cb;
            this.preparedPositionUs = positionUs;
            playbackState.setReaderOwner(this);
            child.prepare(new Callback() {
                @Override
                public void onPrepared(final MediaPeriod mediaPeriod) {
                    cb.onPrepared(SabrDashMediaPeriod.this);
                }

                @Override
                public void onContinueLoadingRequested(final MediaPeriod source) {
                    cb.onContinueLoadingRequested(SabrDashMediaPeriod.this);
                }
            }, positionUs);
        }

        @Override
        public void maybeThrowPrepareError() throws IOException {
            child.maybeThrowPrepareError();
        }

        @Override
        public TrackGroupArray getTrackGroups() {
            return child.getTrackGroups();
        }

        @Override
        public List<StreamKey> getStreamKeys(final List<ExoTrackSelection> trackSelections) {
            return child.getStreamKeys(trackSelections);
        }

        @Override
        public long selectTracks(final ExoTrackSelection[] selections,
                                 final boolean[] mayRetainStreamFlags,
                                 final SampleStream[] streams,
                                 final boolean[] streamResetFlags,
                                 final long positionUs) {
            playbackState.setReaderOwner(this);
            final boolean hasActiveTracks = updateActiveTracks(selections);
            // Initial mid-starts near the next video boundary are cheaper if SABR starts on that
            // boundary; keep regular seeks on Media3's requested position/tolerance path.
            final long normalizedPositionUs = initialPositionApplied || !hasActiveTracks
                    ? normalizeSeekPositionUs(positionUs)
                    : normalizeInitialStartPositionUs(positionUs);
            applyInitialStartPosition(normalizedPositionUs, hasActiveTracks);
            return child.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags,
                    normalizedPositionUs);
        }

        private boolean updateActiveTracks(final ExoTrackSelection[] selections) {
            boolean videoActive = false;
            boolean audioActive = false;
            for (final ExoTrackSelection selection : selections) {
                if (selection == null) {
                    continue;
                }
                final Format format = selection.getSelectedFormat();
                if (format != null && String.valueOf(spec.getVideoFormat().getItag())
                        .equals(format.id)) {
                    videoActive = true;
                } else if (format != null && String.valueOf(spec.getAudioFormat().getItag())
                        .equals(format.id)) {
                    audioActive = true;
                }
            }
            sessionHandle.setActiveTracks(this, videoActive, audioActive);
            Log.d(TAG, "activeTracks video=" + spec.getVideoId()
                    + " video=" + videoActive + " audio=" + audioActive);
            return videoActive || audioActive;
        }

        private void applyInitialStartPosition(final long positionUs,
                                               final boolean hasActiveTracks) {
            if (initialPositionApplied || !hasActiveTracks) {
                return;
            }
            initialPositionApplied = true;
            final long targetUs = Math.max(validPositionUs(preparedPositionUs),
                    validPositionUs(positionUs));
            if (targetUs <= 0) {
                return;
            }
            final long normalizedTargetUs = normalizeSeekPositionUs(targetUs);
            Log.d(TAG, "initialStart video=" + spec.getVideoId()
                    + " positionUs=" + normalizedTargetUs);
            sessionHandle.requestSeek(normalizedTargetUs / 1000L);
        }

        private long validPositionUs(final long positionUs) {
            return positionUs == C.TIME_UNSET ? 0 : Math.max(0, positionUs);
        }

        @Override
        public void discardBuffer(final long positionUs, final boolean toKeyframe) {
            child.discardBuffer(positionUs, toKeyframe);
        }

        @Override
        public long readDiscontinuity() {
            return child.readDiscontinuity();
        }

        @Override
        public long seekToUs(final long positionUs) {
            playbackState.setReaderOwner(this);
            sessionHandle.advanceReaderGeneration(this);
            final long normalizedPositionUs = normalizeSeekPositionUs(positionUs);
            sessionHandle.requestSeek(normalizedPositionUs / 1000L);
            return child.seekToUs(normalizedPositionUs);
        }

        @Override
        public long getAdjustedSeekPositionUs(final long positionUs,
                                              final SeekParameters seekParameters) {
            final long normalizedPositionUs = normalizeSeekPositionUs(positionUs);
            return child.getAdjustedSeekPositionUs(
                    adjustSeekForwardToNearSegmentBoundary(normalizedPositionUs, seekParameters),
                    seekParameters);
        }

        private long normalizeSeekPositionUs(final long positionUs) {
            final long normalizedPositionUs = Math.max(0, positionUs);
            if (durationUs == C.TIME_UNSET || durationUs <= 0
                    || normalizedPositionUs < durationUs) {
                return normalizedPositionUs;
            }
            return Math.max(0, durationUs - END_SEEK_BACKOFF_US);
        }

        private long normalizeInitialStartPositionUs(final long positionUs) {
            return snapForwardToNearSegmentBoundary(normalizeSeekPositionUs(positionUs),
                    START_POSITION_FORWARD_SNAP_US);
        }

        private long adjustSeekForwardToNearSegmentBoundary(final long positionUs,
                                                           final SeekParameters seekParameters) {
            if (seekParameters.toleranceAfterUs <= 0) {
                return positionUs;
            }
            return snapForwardToNearSegmentBoundary(positionUs, Math.min(
                    SEEK_FORWARD_SYNC_TOLERANCE_US, seekParameters.toleranceAfterUs));
        }

        private long snapForwardToNearSegmentBoundary(final long positionUs,
                                                      final long toleranceUs) {
            if (toleranceUs <= 0) {
                return positionUs;
            }
            final long positionMs = Math.max(0, positionUs / 1000L);
            final int currentSequence = manifestState.getSegmentNumberAtOrAfterTimeMs(
                    spec.getVideoFormat(), positionMs);
            final long nextStartMs = manifestState.getSegmentStartMs(
                    spec.getVideoFormat(), currentSequence + 1);
            final long nextStartUs = nextStartMs * 1000L;
            if (nextStartUs > positionUs
                    && nextStartUs - positionUs <= toleranceUs) {
                return nextStartUs;
            }
            return positionUs;
        }

        @Override
        public long getBufferedPositionUs() {
            return child.getBufferedPositionUs();
        }

        @Override
        public long getNextLoadPositionUs() {
            return child.getNextLoadPositionUs();
        }

        @Override
        public boolean continueLoading(final LoadingInfo loadingInfo) {
            return child.continueLoading(loadingInfo);
        }

        @Override
        public boolean isLoading() {
            return child.isLoading();
        }

        @Override
        public void reevaluateBuffer(final long positionUs) {
            child.reevaluateBuffer(positionUs);
        }

        private void release() {
            sessionHandle.releaseTracks(this);
            if (callback != null) {
                callback = null;
            }
        }
    }

    private static final class PlaybackState {
        @NonNull
        private Object readerOwner = new Object();

        synchronized void setReaderOwner(@NonNull final Object readerOwner) {
            this.readerOwner = readerOwner;
        }

        @NonNull
        synchronized Object getReaderOwner() {
            return readerOwner;
        }
    }
}
