package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrNextRequestPolicy {
    private final int targetAudioReadaheadMs;
    private final int targetVideoReadaheadMs;
    private final int maxTimeSinceLastRequestMs;
    private final int backoffTimeMs;
    private final int minAudioReadaheadMs;
    private final int minVideoReadaheadMs;
    @Nullable
    private final byte[] playbackCookie;
    @Nullable
    private final SabrPlaybackCookie decodedPlaybackCookie;
    @Nullable
    private final String videoId;
    @Nonnull
    private final String unknownFields;

    private SabrNextRequestPolicy(final int targetAudioReadaheadMs,
                                  final int targetVideoReadaheadMs,
                                  final int maxTimeSinceLastRequestMs,
                                  final int backoffTimeMs,
                                  final int minAudioReadaheadMs,
                                  final int minVideoReadaheadMs,
                                   @Nullable final byte[] playbackCookie,
                                   @Nullable final SabrPlaybackCookie decodedPlaybackCookie,
                                   @Nullable final String videoId,
                                   @Nonnull final String unknownFields) {
        this.targetAudioReadaheadMs = targetAudioReadaheadMs;
        this.targetVideoReadaheadMs = targetVideoReadaheadMs;
        this.maxTimeSinceLastRequestMs = maxTimeSinceLastRequestMs;
        this.backoffTimeMs = backoffTimeMs;
        this.minAudioReadaheadMs = minAudioReadaheadMs;
        this.minVideoReadaheadMs = minVideoReadaheadMs;
        this.playbackCookie = playbackCookie == null ? null : playbackCookie.clone();
        this.decodedPlaybackCookie = decodedPlaybackCookie;
        this.videoId = videoId;
        this.unknownFields = unknownFields;
    }

    @Nonnull
    public static SabrNextRequestPolicy normalized(final int targetAudioReadaheadMs,
                                                    final int targetVideoReadaheadMs,
                                                    final int maxTimeSinceLastRequestMs,
                                                    final int backoffTimeMs,
                                                    final int minAudioReadaheadMs,
                                                    final int minVideoReadaheadMs,
                                                    @Nullable final byte[] playbackCookie,
                                                    @Nullable final String videoId) {
        if (playbackCookie != null && playbackCookie.length > 64 * 1024) {
            throw new IllegalArgumentException("SABR playback cookie exceeded Host limit");
        }
        return new SabrNextRequestPolicy(targetAudioReadaheadMs, targetVideoReadaheadMs,
                maxTimeSinceLastRequestMs, backoffTimeMs, minAudioReadaheadMs,
                minVideoReadaheadMs, playbackCookie, null, videoId, "policy");
    }

    @Nonnull
    static SabrNextRequestPolicy decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int targetAudioReadaheadMs = -1;
        int targetVideoReadaheadMs = -1;
        int maxTimeSinceLastRequestMs = -1;
        int backoffTimeMs = -1;
        int minAudioReadaheadMs = -1;
        int minVideoReadaheadMs = -1;
        byte[] playbackCookie = null;
        SabrPlaybackCookie decodedPlaybackCookie = null;
        String videoId = null;
        final String unknownFields = SabrProto.summarizeUnknownFields(data,
                1, 2, 3, 4, 5, 6, 7, 8);

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    targetAudioReadaheadMs = (int) field.getVarint();
                    break;
                case 2:
                    targetVideoReadaheadMs = (int) field.getVarint();
                    break;
                case 3:
                    maxTimeSinceLastRequestMs = (int) field.getVarint();
                    break;
                case 4:
                    backoffTimeMs = (int) field.getVarint();
                    break;
                case 5:
                    minAudioReadaheadMs = (int) field.getVarint();
                    break;
                case 6:
                    minVideoReadaheadMs = (int) field.getVarint();
                    break;
                case 7:
                    playbackCookie = field.getBytes();
                    decodedPlaybackCookie = SabrPlaybackCookie.decode(playbackCookie);
                    break;
                case 8:
                    videoId = field.getString();
                    break;
                default:
                    break;
            }
        }

        return new SabrNextRequestPolicy(targetAudioReadaheadMs, targetVideoReadaheadMs,
                maxTimeSinceLastRequestMs, backoffTimeMs, minAudioReadaheadMs,
                minVideoReadaheadMs, playbackCookie, decodedPlaybackCookie, videoId,
                unknownFields);
    }

    public int getTargetAudioReadaheadMs() {
        return targetAudioReadaheadMs;
    }

    public int getTargetVideoReadaheadMs() {
        return targetVideoReadaheadMs;
    }

    public int getMaxTimeSinceLastRequestMs() {
        return maxTimeSinceLastRequestMs;
    }

    public int getBackoffTimeMs() {
        return backoffTimeMs;
    }

    public int getMinAudioReadaheadMs() {
        return minAudioReadaheadMs;
    }

    public int getMinVideoReadaheadMs() {
        return minVideoReadaheadMs;
    }

    @Nullable
    public byte[] getPlaybackCookie() {
        return playbackCookie == null ? null : playbackCookie.clone();
    }

    @Nullable
    byte[] getRawPlaybackCookie() {
        return playbackCookie;
    }

    @Nullable
    public SabrPlaybackCookie getDecodedPlaybackCookie() {
        return decodedPlaybackCookie;
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    @Nonnull
    public String getUnknownFields() {
        return unknownFields;
    }

    @Nonnull
    public String summarize() {
        return "targetAudio=" + targetAudioReadaheadMs
                + ", targetVideo=" + targetVideoReadaheadMs
                + ", maxSinceLast=" + maxTimeSinceLastRequestMs
                + ", backoff=" + backoffTimeMs
                + ", minAudio=" + minAudioReadaheadMs
                + ", minVideo=" + minVideoReadaheadMs
                + ", cookie=" + (decodedPlaybackCookie == null
                ? "bytes(" + (playbackCookie == null ? 0 : playbackCookie.length) + ')'
                : decodedPlaybackCookie.summarize())
                + ", videoIdLength=" + (videoId == null ? 0 : videoId.length())
                + ("none".equals(unknownFields) ? "" : ", unknown=" + unknownFields);
    }
}
