package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YoutubeSabrStreamState {
    public static final int TRACK_MODE_VIDEO_AND_AUDIO =
            YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO;
    public static final int TRACK_MODE_AUDIO_ONLY =
            YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY;
    public static final int TRACK_MODE_VIDEO_ONLY =
            YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY;

    private final FormatProgress audio;
    private final FormatProgress video;
    private final Map<Integer, SabrContextUpdate> sabrContexts = new LinkedHashMap<>();
    private final Set<Integer> activeSabrContextTypes = new LinkedHashSet<>();
    @Nullable
    private byte[] playbackCookie;
    @Nullable
    private byte[] poToken;
    @Nullable
    private volatile SabrNextRequestPolicy nextRequestPolicy;
    private long playerTimeMsOverride = -1;
    private boolean audioFullyBuffered;
    private boolean videoFullyBuffered;
    private boolean audioLastOnlyRange;
    private boolean videoLastOnlyRange;
    private boolean lastOnlyRangesUseObservedTiming;
    private volatile int enabledTrackTypesBitfield = YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO;
    private volatile boolean selectAudioFormat = true;
    private volatile boolean selectVideoFormat = true;
    private boolean writeTopLevelPlayerTimeMs = true;
    private int clientViewportWidth = -1;
    private int clientViewportHeight = -1;
    private long bandwidthEstimate = -1;
    private float playbackRate = 1.0f;
    // Experimental knobs used by local SABR probes. Defaults preserve the normal request shape.
    private int bufferedRangeStartSegmentIndexOffset;
    private int bufferedRangeEndSegmentIndexOffset;
    @Nullable
    private Integer clientAbrVisibility = 1;
    private boolean writeLastManualSelectedResolution;
    private boolean writeAllPreferredFormats;
    private boolean writeOfficialWebPreferredFormats;
    private boolean selectVideoFormatBeforeAudio;
    private boolean writeBufferedRangeTimeRange = true;
    @Nullable
    private Integer stickyResolutionOverride;
    @Nullable
    private Long officialTimeSinceLastSeekOverride;
    @Nullable
    private Long officialElapsedWallTimeOverride;
    @Nullable
    private Long officialTimeSinceLastActionOverride;
    @Nullable
    private Long officialField57Override;
    @Nullable
    private Long officialField68Override;
    @Nullable
    private Integer sabrReportRequestCancellationInfoOverride;
    private boolean writeOfficialWebClientAbrFields;
    @Nullable
    private List<SabrBufferedRange> bufferedRangesOverride;

    // how close to the head counts as "at the live edge" (segments of slack before we wait)
    private static final long LIVE_EDGE_MARGIN_SEGMENTS = 2;
    // live: foundation only. we record what the server tells us about the live edge (via
    // LIVE_METADATA) so a future live-aware pump can follow the head. VOD never sets these.
    private boolean live;
    private boolean postLiveDvr;
    private long liveHeadSequenceNumber = -1;
    private long liveHeadTimeMs = -1;

    public YoutubeSabrStreamState(@Nonnull final YoutubeSabrFormat audioFormat,
                                  @Nonnull final YoutubeSabrFormat videoFormat) {
        audio = new FormatProgress(audioFormat);
        video = new FormatProgress(videoFormat);
    }

    public boolean ingest(@Nonnull final SabrDecodedResponse response) {
        return ingest(SabrResponseStatePatch.builtin(response));
    }

    public boolean ingest(@Nonnull final SabrResponseStatePatch patch) {
        boolean progressed = false;
        final SabrNextRequestPolicy nextRequestPolicy = patch.getNextRequestPolicy();
        if (nextRequestPolicy != null) {
            this.nextRequestPolicy = nextRequestPolicy;
        }
        if (nextRequestPolicy != null && nextRequestPolicy.getRawPlaybackCookie() != null) {
            playbackCookie = nextRequestPolicy.getRawPlaybackCookie().clone();
        }
        for (final SabrLiveMetadata meta : patch.getLiveMetadata()) {
            live = true;
            postLiveDvr = meta.isPostLiveDvr();
            if (meta.getHeadSequenceNumber() >= 0) {
                liveHeadSequenceNumber = meta.getHeadSequenceNumber();
            }
            if (meta.getHeadTimeMs() >= 0) {
                liveHeadTimeMs = meta.getHeadTimeMs();
            }
        }
        for (final SabrFormatInitializationMetadata metadata
                : patch.getFormatMetadata()) {
            final FormatProgress progress = findProgressForItag(metadata.getItag());
            if (progress != null) {
                progressed |= progress.observeMetadata(metadata);
            }
        }
        for (final SabrMediaHeader header : patch.getMediaHeaders()) {
            final FormatProgress progress = findProgressForItag(header.getItag());
            if (progress != null) {
                progressed |= progress.observeHeader(header);
            }
        }
        for (final SabrContextUpdate contextUpdate : patch.getContextUpdates()) {
            ingestContextUpdate(contextUpdate);
        }
        if (patch.getContextSendingPolicy() != null) {
            ingestContextSendingPolicy(patch.getContextSendingPolicy());
        }
        return progressed;
    }

    public boolean ingest(@Nonnull final SabrMediaSegment segment) {
        final FormatProgress progress = findProgressForItag(segment.getHeader().getItag());
        return progress != null && progress.observeSegment(segment);
    }

    public boolean ingestInitializationData(@Nonnull final YoutubeSabrFormat format,
                                            @Nonnull final byte[] data) {
        final FormatProgress progress = findProgressForItag(format.getItag());
        if (progress == null) {
            return false;
        }
        progress.initReceived = true;
        progress.observeInitializationData(data);
        return true;
    }

    @Nonnull
    public List<SabrBufferedRange> getBufferedRanges() {
        if (bufferedRangesOverride != null) {
            return new ArrayList<>(bufferedRangesOverride);
        }
        final List<SabrBufferedRange> ranges = new ArrayList<>();
        if (isAudioEnabled()) {
            if (audioFullyBuffered) {
                ranges.add(SabrBufferedRange.full(audio.format));
            } else {
                audio.addBufferedRange(ranges, audioLastOnlyRange,
                        lastOnlyRangesUseObservedTiming,
                        bufferedRangeStartSegmentIndexOffset, bufferedRangeEndSegmentIndexOffset);
            }
        }
        if (isVideoEnabled()) {
            if (videoFullyBuffered) {
                ranges.add(SabrBufferedRange.full(video.format));
            } else {
                video.addBufferedRange(ranges, videoLastOnlyRange,
                        lastOnlyRangesUseObservedTiming,
                        bufferedRangeStartSegmentIndexOffset, bufferedRangeEndSegmentIndexOffset);
            }
        }
        return ranges;
    }

    public void setBufferedRangesOverride(
            @Nullable final List<SabrBufferedRange> bufferedRangesOverride) {
        this.bufferedRangesOverride = bufferedRangesOverride == null
                ? null
                : new ArrayList<>(bufferedRangesOverride);
    }

    public long getPlayerTimeMs() {
        if (playerTimeMsOverride >= 0) {
            return playerTimeMsOverride;
        }
        return Math.max(audio.getBufferedEndMs(), video.getBufferedEndMs());
    }

    long getRequestPlayerTimeMs() {
        if (playerTimeMsOverride >= 0) {
            return playerTimeMsOverride;
        }
        if ((isAudioEnabled() && !audio.initReceived)
                || (isVideoEnabled() && !video.initReceived)) {
            return 0;
        }
        return getPlayerTimeMs();
    }

    /** buffered end (ms) of the slower track = how far we can actually play. the weakest link wins. */
    public long getMinBufferedEndMs() {
        if (!isVideoEnabled()) {
            return audio.getBufferedEndMs();
        }
        if (!isAudioEnabled()) {
            return video.getBufferedEndMs();
        }
        return Math.min(audio.getBufferedEndMs(), video.getBufferedEndMs());
    }

    public long getBufferedEndMs(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).getBufferedEndMs();
    }

    public void setPlayerTimeMs(final long playerTimeMs) {
        playerTimeMsOverride = Math.max(0, playerTimeMs);
    }

    public void clearPlayerTimeMsOverride() {
        playerTimeMsOverride = -1;
    }

    void clearPlaybackCookie() {
        playbackCookie = null;
    }

    boolean isInitialized(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).initReceived;
    }

    void resetInitialization(@Nonnull final YoutubeSabrFormat format) {
        progressForItag(format.getItag()).initReceived = false;
    }

    @Nullable
    public byte[] getPlaybackCookie() {
        return playbackCookie == null ? null : playbackCookie.clone();
    }

    public void setPoToken(@Nullable final byte[] poToken) {
        this.poToken = poToken == null ? null : poToken.clone();
    }

    @Nullable
    public byte[] getPoToken() {
        return poToken == null ? null : poToken.clone();
    }

    @Nullable
    byte[] getRawPlaybackCookie() {
        return playbackCookie;
    }

    @Nullable
    byte[] getRawPoToken() {
        return poToken;
    }

    @Nonnull
    Collection<SabrContextUpdate> getActiveSabrContexts() {
        final List<SabrContextUpdate> activeSabrContexts = new ArrayList<>();
        for (final Integer type : activeSabrContextTypes) {
            final SabrContextUpdate contextUpdate = sabrContexts.get(type);
            if (contextUpdate != null) {
                activeSabrContexts.add(contextUpdate);
            }
        }
        return activeSabrContexts;
    }

    @Nonnull
    Collection<Integer> getUnsentSabrContextTypes() {
        final List<Integer> unsentSabrContextTypes = new ArrayList<>();
        for (final Integer type : sabrContexts.keySet()) {
            if (!activeSabrContextTypes.contains(type)) {
                unsentSabrContextTypes.add(type);
            }
        }
        return unsentSabrContextTypes;
    }

    public boolean isComplete() {
        return (!isAudioEnabled() || audio.isComplete())
                && (!isVideoEnabled() || video.isComplete());
    }

    /** True once the server has sent live metadata for this stream (foundation for live support). */
    public boolean isLive() {
        return live;
    }

    /** True for an ended live stream still seekable as DVR. */
    public boolean isPostLiveDvr() {
        return postLiveDvr;
    }

    /** Latest segment the live edge has reached, or -1 if unknown / not live. */
    public long getLiveHeadSequenceNumber() {
        return liveHeadSequenceNumber;
    }

    /** Live head position in ms, or -1 if unknown / not live. */
    public long getLiveHeadTimeMs() {
        return liveHeadTimeMs;
    }

    /**
     * True when we have fetched up to (within a small margin of) the live head: the slower track has
     * reached the edge, so a live-aware pump should wait for the head to advance instead of treating
     * an empty response as the end of the stream. Always false for VOD or before the head is known.
     */
    public boolean isAtLiveEdge(@Nonnull final YoutubeSabrFormat audioFormat,
                                @Nonnull final YoutubeSabrFormat videoFormat) {
        if (!live || liveHeadSequenceNumber < 0) {
            return false;
        }
        final long slowerTrack = Math.min(getMaxSegment(audioFormat), getMaxSegment(videoFormat));
        return slowerTrack >= liveHeadSequenceNumber - LIVE_EDGE_MARGIN_SEGMENTS;
    }

    public int getMaxSegment(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).maxSegment;
    }

    public long getEndSegment(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).endSegment;
    }

    /** True only after initialization bytes yielded an exact per-segment time index. */
    public boolean hasSegmentIndex(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).segmentIndex != null;
    }

    public boolean isComplete(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).isComplete();
    }

    public void assumeBufferedUntil(@Nonnull final YoutubeSabrFormat format,
                                    final int endSegment) {
        if (endSegment > 0) {
            progressForItag(format.getItag()).assumeBufferedUntil(endSegment);
        }
    }

    /**
     * Backward seek: forget buffered segments at/after {@code fromSegment} so the next request
     * re-sends from it. {@link #assumeBufferedUntil} only ever extends the buffered head, so it
     * cannot rewind; this shrinks it.
     */
    public void rewindBufferedTo(@Nonnull final YoutubeSabrFormat format, final int fromSegment) {
        if (fromSegment > 0) {
            progressForItag(format.getItag()).rewindBufferedTo(fromSegment);
        }
    }

    /**
     * Forward jump (cold seek far past the buffered edge): claim everything before
     * {@code fromSegment} as buffered and move the head onto it, so the server streams from the
     * target instead of filling the skipped span. {@link #rewindBufferedTo} is the backward
     * counterpart.
     */
    public void jumpBufferedTo(@Nonnull final YoutubeSabrFormat format, final int fromSegment) {
        if (fromSegment > 0) {
            progressForItag(format.getItag()).jumpBufferedTo(fromSegment);
        }
    }

    public void setFullyBuffered(@Nonnull final YoutubeSabrFormat format,
                                  final boolean fullyBuffered) {
        if (audio.itag == format.getItag()) {
            audioFullyBuffered = fullyBuffered;
        } else if (video.itag == format.getItag()) {
            videoFullyBuffered = fullyBuffered;
        } else {
            throw new IllegalArgumentException("Unknown SABR itag: " + format.getItag());
        }
    }

    public void setLastOnlyRange(@Nonnull final YoutubeSabrFormat format,
                                    final boolean lastOnlyRange) {
        if (audio.itag == format.getItag()) {
            audioLastOnlyRange = lastOnlyRange;
        } else if (video.itag == format.getItag()) {
            videoLastOnlyRange = lastOnlyRange;
        } else {
            throw new IllegalArgumentException("Unknown SABR itag: " + format.getItag());
        }
    }

    public void setLastOnlyRangesUseObservedTiming(final boolean useObservedTiming) {
        lastOnlyRangesUseObservedTiming = useObservedTiming;
    }

    public void setBufferedRangeSegmentIndexOffset(final int bufferedRangeSegmentIndexOffset) {
        bufferedRangeStartSegmentIndexOffset = bufferedRangeSegmentIndexOffset;
        bufferedRangeEndSegmentIndexOffset = bufferedRangeSegmentIndexOffset;
    }

    public void setBufferedRangeSegmentIndexOffsets(final int startSegmentIndexOffset,
                                                    final int endSegmentIndexOffset) {
        bufferedRangeStartSegmentIndexOffset = startSegmentIndexOffset;
        bufferedRangeEndSegmentIndexOffset = endSegmentIndexOffset;
    }

    public synchronized void setRequestTrackMode(final int enabledTrackTypesBitfield,
                                                 final boolean selectAudioFormat,
                                                 final boolean selectVideoFormat) {
        this.enabledTrackTypesBitfield = enabledTrackTypesBitfield;
        this.selectAudioFormat = selectAudioFormat;
        this.selectVideoFormat = selectVideoFormat;
    }

    public void setActiveTrackTypes(final boolean videoActive, final boolean audioActive) {
        if (audioActive && !videoActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY,
                    true, false);
        } else if (videoActive && !audioActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY,
                    false, true);
        } else if (videoActive) {
            setRequestTrackMode(YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO,
                    true, true);
        }
    }

    public void setAudioOnlyRequestMode() {
        setRequestTrackMode(TRACK_MODE_AUDIO_ONLY, true, false);
    }

    public void setVideoOnlyRequestMode() {
        setRequestTrackMode(TRACK_MODE_VIDEO_ONLY, false, true);
    }

    public void setVideoAndAudioRequestMode() {
        setRequestTrackMode(TRACK_MODE_VIDEO_AND_AUDIO, true, true);
    }

    private boolean isAudioEnabled() {
        return enabledTrackTypesBitfield
                != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_VIDEO_ONLY;
    }

    private boolean isVideoEnabled() {
        return enabledTrackTypesBitfield
                != YoutubeSabrRequestBuilder.ENABLED_TRACK_TYPES_AUDIO_ONLY;
    }

    public void setClientViewport(final int clientViewportWidth,
                                  final int clientViewportHeight) {
        this.clientViewportWidth = clientViewportWidth;
        this.clientViewportHeight = clientViewportHeight;
    }

    int getClientViewportWidth() {
        return clientViewportWidth;
    }

    int getClientViewportHeight() {
        return clientViewportHeight;
    }

    public void setBandwidthEstimate(final long bandwidthEstimate) {
        this.bandwidthEstimate = bandwidthEstimate;
    }

    public long getBandwidthEstimate() {
        return bandwidthEstimate;
    }

    @Nullable
    public SabrNextRequestPolicy getNextRequestPolicy() {
        return nextRequestPolicy;
    }

    public void setPlaybackRate(final float playbackRate) {
        if (playbackRate > 0.0f) {
            this.playbackRate = playbackRate;
        }
    }

    float getPlaybackRate() {
        return playbackRate;
    }

    int getEnabledTrackTypesBitfield() {
        return enabledTrackTypesBitfield;
    }

    boolean shouldSelectAudioFormat() {
        return selectAudioFormat;
    }

    boolean shouldSelectVideoFormat() {
        return selectVideoFormat;
    }

    public void setWriteTopLevelPlayerTimeMs(final boolean writeTopLevelPlayerTimeMs) {
        this.writeTopLevelPlayerTimeMs = writeTopLevelPlayerTimeMs;
    }

    boolean shouldWriteTopLevelPlayerTimeMs() {
        return writeTopLevelPlayerTimeMs;
    }

    public void setClientAbrVisibility(@Nullable final Integer clientAbrVisibility) {
        this.clientAbrVisibility = clientAbrVisibility;
    }

    @Nullable
    Integer getClientAbrVisibility() {
        return clientAbrVisibility;
    }

    public void setWriteLastManualSelectedResolution(
            final boolean writeLastManualSelectedResolution) {
        this.writeLastManualSelectedResolution = writeLastManualSelectedResolution;
    }

    boolean shouldWriteLastManualSelectedResolution() {
        return writeLastManualSelectedResolution;
    }

    public void setWriteAllPreferredFormats(final boolean writeAllPreferredFormats) {
        this.writeAllPreferredFormats = writeAllPreferredFormats;
    }

    boolean shouldWriteAllPreferredFormats() {
        return writeAllPreferredFormats;
    }

    public void setWriteOfficialWebPreferredFormats(
            final boolean writeOfficialWebPreferredFormats) {
        this.writeOfficialWebPreferredFormats = writeOfficialWebPreferredFormats;
    }

    boolean shouldWriteOfficialWebPreferredFormats() {
        return writeOfficialWebPreferredFormats;
    }

    public void setSelectVideoFormatBeforeAudio(final boolean selectVideoFormatBeforeAudio) {
        this.selectVideoFormatBeforeAudio = selectVideoFormatBeforeAudio;
    }

    boolean shouldSelectVideoFormatBeforeAudio() {
        return selectVideoFormatBeforeAudio;
    }

    public void setWriteBufferedRangeTimeRange(final boolean writeBufferedRangeTimeRange) {
        this.writeBufferedRangeTimeRange = writeBufferedRangeTimeRange;
    }

    boolean shouldWriteBufferedRangeTimeRange() {
        return writeBufferedRangeTimeRange;
    }

    public void setStickyResolutionOverride(@Nullable final Integer stickyResolutionOverride) {
        this.stickyResolutionOverride = stickyResolutionOverride;
    }

    @Nullable
    Integer getStickyResolutionOverride() {
        return stickyResolutionOverride;
    }

    public void setOfficialWebClientAbrTimingOverrides(
            @Nullable final Long timeSinceLastSeek,
            @Nullable final Long elapsedWallTime,
            @Nullable final Long timeSinceLastAction,
            @Nullable final Long field57) {
        officialTimeSinceLastSeekOverride = timeSinceLastSeek;
        officialElapsedWallTimeOverride = elapsedWallTime;
        officialTimeSinceLastActionOverride = timeSinceLastAction;
        officialField57Override = field57;
    }

    public void setOfficialField68Override(@Nullable final Long field68) {
        officialField68Override = field68;
    }

    @Nullable
    Long getOfficialTimeSinceLastSeekOverride() {
        return officialTimeSinceLastSeekOverride;
    }

    @Nullable
    Long getOfficialElapsedWallTimeOverride() {
        return officialElapsedWallTimeOverride;
    }

    @Nullable
    Long getOfficialTimeSinceLastActionOverride() {
        return officialTimeSinceLastActionOverride;
    }

    @Nullable
    Long getOfficialField57Override() {
        return officialField57Override;
    }

    @Nullable
    Long getOfficialField68Override() {
        return officialField68Override;
    }

    public void setSabrReportRequestCancellationInfoOverride(
            @Nullable final Integer sabrReportRequestCancellationInfoOverride) {
        this.sabrReportRequestCancellationInfoOverride = sabrReportRequestCancellationInfoOverride;
    }

    @Nullable
    Integer getSabrReportRequestCancellationInfoOverride() {
        return sabrReportRequestCancellationInfoOverride;
    }

    public void setWriteOfficialWebClientAbrFields(
            final boolean writeOfficialWebClientAbrFields) {
        this.writeOfficialWebClientAbrFields = writeOfficialWebClientAbrFields;
    }

    boolean shouldWriteOfficialWebClientAbrFields() {
        return writeOfficialWebClientAbrFields;
    }

    @Nonnull
    public String summarizeBufferedRanges() {
        final List<SabrBufferedRange> ranges = getBufferedRanges();
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(ranges.get(i).summarize());
        }
        return builder.toString();
    }

    public long getAverageSegmentDurationMs(@Nonnull final YoutubeSabrFormat format) {
        return progressForItag(format.getItag()).averageDurationMs;
    }

    public long getSegmentStartMs(@Nonnull final YoutubeSabrFormat format,
                                  final int sequenceNumber) {
        return progressForItag(format.getItag()).getSegmentStartMs(sequenceNumber);
    }

    public long getSegmentEndMs(@Nonnull final YoutubeSabrFormat format,
                                 final int sequenceNumber) {
        return progressForItag(format.getItag()).getSegmentEndMs(sequenceNumber);
    }

    public int getSegmentNumberAtOrAfterTimeMs(@Nonnull final YoutubeSabrFormat format,
                                               final long timeMs) {
        return progressForItag(format.getItag()).getSegmentNumberAtOrAfterTimeMs(timeMs);
    }

    @Nonnull
    private FormatProgress progressForItag(final int itag) {
        final FormatProgress progress = findProgressForItag(itag);
        if (progress == null) {
            throw new IllegalArgumentException("Unknown SABR itag: " + itag);
        }
        return progress;
    }

    @Nullable
    private FormatProgress findProgressForItag(final int itag) {
        if (audio.itag == itag) {
            return audio;
        }
        if (video.itag == itag) {
            return video;
        }
        return null;
    }

    private void ingestContextUpdate(@Nonnull final SabrContextUpdate contextUpdate) {
        if (contextUpdate.getType() < 0 || contextUpdate.getValueLength() == 0) {
            return;
        }
        if (contextUpdate.getWritePolicy() == SabrContextUpdate.WRITE_POLICY_KEEP_EXISTING
                && sabrContexts.containsKey(contextUpdate.getType())) {
            return;
        }
        sabrContexts.put(contextUpdate.getType(), contextUpdate);
        if (contextUpdate.isSendByDefault()) {
            activeSabrContextTypes.add(contextUpdate.getType());
        }
    }

    private void ingestContextSendingPolicy(@Nonnull final SabrContextSendingPolicy policy) {
        activeSabrContextTypes.addAll(policy.getStartPolicy());
        activeSabrContextTypes.removeAll(policy.getStopPolicy());
        for (final Integer type : policy.getDiscardPolicy()) {
            sabrContexts.remove(type);
            activeSabrContextTypes.remove(type);
        }
    }

    private static final class FormatProgress {
        @Nonnull
        private final YoutubeSabrFormat format;
        private final int itag;
        private final long lastModified;
        @Nullable
        private final String xtags;
        // pump thread writes it, ExoPlayer loader threads read it. volatile so they actually see it.
        private volatile boolean initReceived;
        private volatile int maxSegment;
        // Highest segment with NO gap from the start. We tell the server we have up to here so it
        // fills the exact next segment, instead of skipping ahead off an overstated maxSegment and
        // leaving a hole the sequential reader then starves on forever.
        private volatile int contiguousMaxSegment;
        private final java.util.Set<Integer> aheadOfContiguous = new java.util.HashSet<>();
        private int observedMaxSegment;
        private volatile long endSegment = -1;
        private long averageDurationMs = 5000;
        private int firstObservedSegment = -1;
        private int lastObservedSegment = -1;
        private long observedStartMs = -1;
        private long observedEndMs = -1;
        private long lastObservedDurationMs = -1;
        @Nullable
        private SabrFormatInitializationMetadata metadata;
        @Nullable
        private SabrSegmentIndex segmentIndex;

        private FormatProgress(@Nonnull final YoutubeSabrFormat format) {
            this.format = format;
            itag = format.getItag();
            lastModified = format.getLastModified();
            xtags = format.getXtags();
        }

        private boolean observeMetadata(@Nonnull final SabrFormatInitializationMetadata metadata) {
            this.metadata = metadata;
            final long previousEndSegment = endSegment;
            endSegment = metadata.getEndSegmentNumber();
            if (metadata.getDurationUnits() > 0 && metadata.getDurationTimescale() > 0
                    && metadata.getEndSegmentNumber() > 0) {
                final long totalMs = metadata.getDurationUnits() * 1000L
                        / metadata.getDurationTimescale();
                averageDurationMs = Math.max(1L, totalMs / metadata.getEndSegmentNumber());
            } else if (endSegment > 0 && format.getApproxDurationMs() > 0) {
                // The init metadata gives the segment count but no per-segment timing for this
                // format (seen on some YouTube responses). Derive the average from the format's
                // total duration so a cold seek maps the time to the right segment, instead of the
                // 5000ms default that doubles ~10s-audio-segment numbers and overshoots endSegment
                // -> out-of-bounds request -> the server returns nothing -> endless buffering.
                averageDurationMs = Math.max(1L, format.getApproxDurationMs() / endSegment);
            }
            return previousEndSegment != endSegment;
        }

        private boolean observeSegment(@Nonnull final SabrMediaSegment segment) {
            if (!segment.getHeader().isInitSegment() || metadata == null || segmentIndex != null) {
                return false;
            }
            return observeInitializationData(segment.getData());
        }

        private boolean observeInitializationData(@Nonnull final byte[] data) {
            if (segmentIndex != null) {
                return false;
            }
            final String mimeType = metadata == null ? format.getMimeType() : metadata.getMimeType();
            if (mimeType == null) {
                return false;
            }
            try {
                if (mimeType.contains("mp4")) {
                    segmentIndex = metadata == null
                            ? SabrMp4SegmentIndexParser.parse(data, format)
                            : SabrMp4SegmentIndexParser.parse(data, metadata);
                } else if (mimeType.contains("webm")) {
                    segmentIndex = metadata == null
                            ? SabrWebmSegmentIndexParser.parse(data, format)
                            : SabrWebmSegmentIndexParser.parse(data, metadata);
                } else {
                    return false;
                }
                observeSegmentIndex();
                return true;
            } catch (final SabrProtocolException ignored) {
                if (metadata == null) {
                    return false;
                }
                try {
                    if (mimeType.contains("mp4")) {
                        segmentIndex = SabrMp4SegmentIndexParser.parse(data, format);
                    } else if (mimeType.contains("webm")) {
                        segmentIndex = SabrWebmSegmentIndexParser.parse(data, format);
                    } else {
                        return false;
                    }
                    observeSegmentIndex();
                    return true;
                } catch (final SabrProtocolException ignoredFallback) {
                    return false;
                }
            } catch (final Exception ignored) {
                return false;
            }
        }

        private void observeSegmentIndex() {
            if (segmentIndex == null) {
                return;
            }
            if (endSegment <= 0) {
                endSegment = segmentIndex.size();
            }
            if (format.getApproxDurationMs() > 0 && endSegment > 0) {
                averageDurationMs = Math.max(1L, format.getApproxDurationMs() / endSegment);
            }
        }

        private boolean observeHeader(@Nonnull final SabrMediaHeader header) {
            if (header.isInitSegment()) {
                final boolean changed = !initReceived;
                initReceived = true;
                return changed;
            }
            if (header.getSequenceNumber() > maxSegment) {
                maxSegment = header.getSequenceNumber();
            }
            final int seq = header.getSequenceNumber();
            if (seq == contiguousMaxSegment + 1) {
                contiguousMaxSegment = seq;
                while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) {
                    contiguousMaxSegment++;
                }
            } else if (seq > contiguousMaxSegment + 1) {
                aheadOfContiguous.add(seq);
            }
            if (header.getSequenceNumber() > observedMaxSegment) {
                observedMaxSegment = header.getSequenceNumber();
            }
            if (firstObservedSegment < 0 || header.getSequenceNumber() < firstObservedSegment) {
                firstObservedSegment = header.getSequenceNumber();
            }
            if (header.getSequenceNumber() >= lastObservedSegment) {
                lastObservedSegment = header.getSequenceNumber();
                lastObservedDurationMs = header.getDurationMs();
            }
            if (header.getStartMs() >= 0 && header.getDurationMs() > 0) {
                if (observedStartMs < 0 || header.getStartMs() < observedStartMs) {
                    observedStartMs = header.getStartMs();
                }
                observedEndMs = Math.max(observedEndMs, header.getStartMs() + header.getDurationMs());
            }
            if (header.getSequenceNumber() == maxSegment) {
                return true;
            }
            return false;
        }

        private void addBufferedRange(@Nonnull final List<SabrBufferedRange> ranges,
                                      final boolean lastOnlyRange,
                                      final boolean lastOnlyRangeUseObservedTiming,
                                      final int startSegmentIndexOffset,
                                      final int endSegmentIndexOffset) {
            if (!initReceived || maxSegment <= 0) {
                return;
            }
            if (lastOnlyRange && lastObservedSegment > 0) {
                final long durationMs = lastObservedDurationMs > 0
                        ? lastObservedDurationMs : averageDurationMs;
                final long startTimeMs = lastOnlyRangeUseObservedTiming
                        ? getSegmentStartMs(lastObservedSegment) : 0;
                ranges.add(new SabrBufferedRange(itag, lastModified, xtags, startTimeMs,
                        durationMs,
                        applySegmentIndexOffset(lastObservedSegment, startSegmentIndexOffset),
                        applySegmentIndexOffset(lastObservedSegment, endSegmentIndexOffset), 1000));
                return;
            }
            // Only trust observed timing when there is NO hole (contiguous == max); otherwise the
            // observed end overstates past the gap and the server skips the segment we still need.
            final boolean canUseObservedTiming = observedStartMs >= 0 && observedEndMs > observedStartMs
                    && observedMaxSegment >= maxSegment && firstObservedSegment > 0
                    && contiguousMaxSegment >= maxSegment;
            ranges.add(new SabrBufferedRange(itag, lastModified, xtags,
                    canUseObservedTiming ? observedStartMs : 0,
                    canUseObservedTiming ? observedEndMs - observedStartMs : getBufferedEndMs(),
                    applySegmentIndexOffset(canUseObservedTiming ? firstObservedSegment : 1,
                            startSegmentIndexOffset),
                    applySegmentIndexOffset(contiguousMaxSegment, endSegmentIndexOffset), 1000));
        }

        private int applySegmentIndexOffset(final int segmentIndex,
                                            final int segmentIndexOffset) {
            return Math.max(0, segmentIndex + segmentIndexOffset);
        }

        private long getBufferedEndMs() {
            // contiguous, not maxSegment: a hole means we are NOT really buffered past it.
            final long indexedEndMs = getSegmentEndMs(contiguousMaxSegment);
            if (indexedEndMs >= 0) {
                return indexedEndMs;
            }
            return contiguousMaxSegment * averageDurationMs;
        }

        private long getSegmentStartMs(final int sequenceNumber) {
            if (sequenceNumber <= 1) {
                return 0;
            }
            if (segmentIndex != null) {
                final SabrSegmentIndex.Entry entry = segmentIndex.getEntry(sequenceNumber);
                if (entry != null) {
                    return entry.getStartMs();
                }
            }
            return Math.max(0, sequenceNumber - 1L) * averageDurationMs;
        }

        private long getSegmentEndMs(final int sequenceNumber) {
            if (segmentIndex != null) {
                final SabrSegmentIndex.Entry entry = segmentIndex.getEntry(sequenceNumber);
                if (entry != null) {
                    return entry.getEndMs();
                }
            }
            if (sequenceNumber <= 0) {
                return -1;
            }
            return sequenceNumber * averageDurationMs;
        }

        private int getSegmentNumberAtOrAfterTimeMs(final long timeMs) {
            if (timeMs <= 0) {
                return 1;
            }
            if (segmentIndex != null) {
                for (int i = 1; i <= segmentIndex.size(); i++) {
                    final SabrSegmentIndex.Entry entry = segmentIndex.getEntry(i);
                    if (entry != null && entry.getEndMs() > timeMs) {
                        return entry.getSequenceNumber();
                    }
                }
                return segmentIndex.size() == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE : Math.max(1, segmentIndex.size() + 1);
            }
            final long durationMs = Math.max(1, averageDurationMs);
            final long sequenceNumber = timeMs / durationMs + 1;
            return sequenceNumber > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : Math.max(1, (int) sequenceNumber);
        }

        private void assumeBufferedUntil(final int endSegment) {
            maxSegment = Math.max(maxSegment, endSegment);
        }

        private void rewindBufferedTo(final int fromSegment) {
            final int last = Math.max(0, fromSegment - 1);
            if (last >= contiguousMaxSegment) {
                return; // not a rewind for this track
            }
            // The buffered range the server reads ends at contiguousMaxSegment (+ observed timing),
            // not maxSegment. Shrink both and drop the observed-timing window so the range falls back
            // to the contiguous end; otherwise the server thinks we still hold the target and the
            // re-request comes back empty.
            maxSegment = last;
            contiguousMaxSegment = last;
            observedMaxSegment = Math.min(observedMaxSegment, last);
            firstObservedSegment = -1;
            lastObservedSegment = -1;
            observedStartMs = -1;
            observedEndMs = -1;
        }

        private void jumpBufferedTo(final int fromSegment) {
            final int last = Math.max(0, fromSegment - 1);
            if (last <= contiguousMaxSegment) {
                return; // not a forward jump for this track
            }
            // Forward jump (cold seek far past the edge): claim everything before the target as
            // buffered. The play head jumped past it and will never play it, and keeping the old
            // contiguous edge made the pump keep reporting/filling the skipped span (ping-pong +
            // duplicate re-sends). A later backward seek into the skipped span goes through the
            // normal rewind path, which re-requests it honestly.
            contiguousMaxSegment = last;
            // Fold in target-zone segments that already arrived out of order, drop pre-jump ones.
            aheadOfContiguous.removeIf(seq -> seq <= last);
            while (aheadOfContiguous.remove(contiguousMaxSegment + 1)) {
                contiguousMaxSegment++;
            }
            maxSegment = Math.max(maxSegment, contiguousMaxSegment);
            // The observed-timing window describes pre-jump data; drop it so the reported range
            // falls back to the contiguous end until fresh headers rebuild it at the target.
            observedMaxSegment = Math.min(observedMaxSegment, contiguousMaxSegment);
            firstObservedSegment = -1;
            lastObservedSegment = -1;
            observedStartMs = -1;
            observedEndMs = -1;
        }

        private boolean isComplete() {
            return initReceived && endSegment > 0 && maxSegment >= endSegment;
        }
    }
}
