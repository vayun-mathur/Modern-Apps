package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrLiveMetadata {
    @Nullable
    private final String broadcastId;
    private final long headSequenceNumber;
    private final long headTimeMs;
    private final long wallTimeMs;
    @Nullable
    private final String videoId;
    private final boolean postLiveDvr;
    private final long headm;
    private final long minSeekableTimeTicks;
    private final int minSeekableTimescale;
    private final long maxSeekableTimeTicks;
    private final int maxSeekableTimescale;

    private SabrLiveMetadata(@Nullable final String broadcastId,
                             final long headSequenceNumber,
                             final long headTimeMs,
                             final long wallTimeMs,
                             @Nullable final String videoId,
                             final boolean postLiveDvr,
                             final long headm,
                             final long minSeekableTimeTicks,
                             final int minSeekableTimescale,
                             final long maxSeekableTimeTicks,
                             final int maxSeekableTimescale) {
        this.broadcastId = broadcastId;
        this.headSequenceNumber = headSequenceNumber;
        this.headTimeMs = headTimeMs;
        this.wallTimeMs = wallTimeMs;
        this.videoId = videoId;
        this.postLiveDvr = postLiveDvr;
        this.headm = headm;
        this.minSeekableTimeTicks = minSeekableTimeTicks;
        this.minSeekableTimescale = minSeekableTimescale;
        this.maxSeekableTimeTicks = maxSeekableTimeTicks;
        this.maxSeekableTimescale = maxSeekableTimescale;
    }

    @Nonnull
    public static SabrLiveMetadata normalized(@Nullable final String broadcastId,
                                               final long headSequenceNumber,
                                               final long headTimeMs,
                                               final long wallTimeMs,
                                               @Nullable final String videoId,
                                               final boolean postLiveDvr,
                                               final long headm,
                                               final long minSeekableTimeTicks,
                                               final int minSeekableTimescale,
                                               final long maxSeekableTimeTicks,
                                               final int maxSeekableTimescale) {
        if (headSequenceNumber < -1 || headTimeMs < -1 || wallTimeMs < -1
                || minSeekableTimeTicks < -1 || minSeekableTimescale < -1
                || maxSeekableTimeTicks < -1 || maxSeekableTimescale < -1) {
            throw new IllegalArgumentException("Invalid normalized SABR live metadata");
        }
        return new SabrLiveMetadata(broadcastId, headSequenceNumber, headTimeMs, wallTimeMs,
                videoId, postLiveDvr, headm, minSeekableTimeTicks, minSeekableTimescale,
                maxSeekableTimeTicks, maxSeekableTimescale);
    }

    @Nonnull
    static SabrLiveMetadata decode(@Nonnull final byte[] data) throws SabrProtocolException {
        String broadcastId = null;
        long headSequenceNumber = -1;
        long headTimeMs = -1;
        long wallTimeMs = -1;
        String videoId = null;
        boolean postLiveDvr = false;
        long headm = -1;
        long minSeekableTimeTicks = -1;
        int minSeekableTimescale = -1;
        long maxSeekableTimeTicks = -1;
        int maxSeekableTimescale = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    broadcastId = field.getString();
                    break;
                case 3:
                    headSequenceNumber = field.getVarint();
                    break;
                case 4:
                    headTimeMs = field.getVarint();
                    break;
                case 5:
                    wallTimeMs = field.getVarint();
                    break;
                case 6:
                    videoId = field.getString();
                    break;
                case 8:
                    postLiveDvr = field.getVarint() != 0;
                    break;
                case 10:
                    headm = field.getVarint();
                    break;
                case 12:
                    minSeekableTimeTicks = field.getVarint();
                    break;
                case 13:
                    minSeekableTimescale = (int) field.getVarint();
                    break;
                case 14:
                    maxSeekableTimeTicks = field.getVarint();
                    break;
                case 15:
                    maxSeekableTimescale = (int) field.getVarint();
                    break;
                default:
                    break;
            }
        }
        return new SabrLiveMetadata(broadcastId, headSequenceNumber, headTimeMs, wallTimeMs,
                videoId, postLiveDvr, headm, minSeekableTimeTicks, minSeekableTimescale,
                maxSeekableTimeTicks, maxSeekableTimescale);
    }

    /** Latest segment the live edge has reached, or -1 if unknown. */
    public long getHeadSequenceNumber() {
        return headSequenceNumber;
    }

    /** Wall-clock-ish position (ms) of the live head, or -1 if unknown. */
    public long getHeadTimeMs() {
        return headTimeMs;
    }

    public long getWallTimeMs() {
        return wallTimeMs;
    }

    /** True for an ended live stream still seekable as DVR. */
    public boolean isPostLiveDvr() {
        return postLiveDvr;
    }

    @Nullable
    public String getBroadcastId() {
        return broadcastId;
    }

    public long getMinSeekableTimeTicks() {
        return minSeekableTimeTicks;
    }

    public int getMinSeekableTimescale() {
        return minSeekableTimescale;
    }

    public long getMaxSeekableTimeTicks() {
        return maxSeekableTimeTicks;
    }

    public int getMaxSeekableTimescale() {
        return maxSeekableTimescale;
    }

    @Nonnull
    public String summarize() {
        return "broadcastIdLength=" + (broadcastId == null ? 0 : broadcastId.length())
                + ", headSeq=" + headSequenceNumber
                + ", headTimeMs=" + headTimeMs
                + ", wallTimeMs=" + wallTimeMs
                + ", videoIdLength=" + (videoId == null ? 0 : videoId.length())
                + ", postLiveDvr=" + postLiveDvr
                + ", headm=" + headm
                + ", minSeekable=" + minSeekableTimeTicks + '/' + minSeekableTimescale
                + ", maxSeekable=" + maxSeekableTimeTicks + '/' + maxSeekableTimescale;
    }
}
