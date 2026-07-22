package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrBufferedRange {
    private static final int MAX_INT32_VALUE = Integer.MAX_VALUE;

    private final int itag;
    private final long lastModified;
    @Nullable
    private final String xtags;
    private final long startTimeMs;
    private final long durationMs;
    private final int startSegmentIndex;
    private final int endSegmentIndex;
    private final int timescale;

    public SabrBufferedRange(final int itag,
                             final long lastModified,
                             @Nullable final String xtags,
                             final long startTimeMs,
                             final long durationMs,
                             final int startSegmentIndex,
                             final int endSegmentIndex,
                             final int timescale) {
        this.itag = itag;
        this.lastModified = lastModified;
        this.xtags = xtags;
        this.startTimeMs = startTimeMs;
        this.durationMs = durationMs;
        this.startSegmentIndex = startSegmentIndex;
        this.endSegmentIndex = endSegmentIndex;
        this.timescale = timescale;
    }

    @Nonnull
    static SabrBufferedRange full(@Nonnull final YoutubeSabrFormat format) {
        return new SabrBufferedRange(format.getItag(), format.getLastModified(), format.getXtags(),
                0, MAX_INT32_VALUE, MAX_INT32_VALUE, MAX_INT32_VALUE, 1000);
    }

    @Nonnull
    byte[] toProto() {
        return toProto(true);
    }

    @Nonnull
    byte[] toProto(final boolean includeTimeRange) {
        final SabrProto.Writer range = new SabrProto.Writer();
        range.writeMessage(1, formatIdProto());
        range.writeUInt64(2, startTimeMs);
        range.writeUInt64(3, durationMs);
        range.writeInt32(4, startSegmentIndex);
        range.writeInt32(5, endSegmentIndex);
        if (includeTimeRange) {
            range.writeMessage(6, timeRangeProto());
        }
        return range.toByteArray();
    }

    @Nonnull
    public String summarize() {
        return "itag=" + itag
                + ":seq=" + startSegmentIndex + "-" + endSegmentIndex
                + ":time=" + startTimeMs + "+" + durationMs
                + ":timescale=" + timescale;
    }

    @Nonnull
    private byte[] formatIdProto() {
        final SabrProto.Writer format = new SabrProto.Writer();
        format.writeInt32(1, itag);
        if (lastModified > 0) {
            format.writeUInt64(2, lastModified);
        }
        format.writeStringIfNotEmpty(3, xtags);
        return format.toByteArray();
    }

    @Nonnull
    private byte[] timeRangeProto() {
        final SabrProto.Writer timeRange = new SabrProto.Writer();
        timeRange.writeUInt64(1, startTimeMs);
        timeRange.writeUInt64(2, durationMs);
        timeRange.writeInt32(3, timescale);
        return timeRange.toByteArray();
    }
}
