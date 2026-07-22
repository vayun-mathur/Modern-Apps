package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrMediaHeader {
    private final int headerId;
    @Nullable
    private final String videoId;
    private final int itag;
    private final long lastModified;
    @Nullable
    private final String xtags;
    private final long startRange;
    private final int compressionAlgorithm;
    private final boolean initSegment;
    private final int sequenceNumber;
    private final long bitrateBps;
    private final long startMs;
    private final long durationMs;
    private final long contentLength;
    private final long timeRangeStartTicks;
    private final long timeRangeDurationTicks;
    private final int timeRangeTimescale;
    private final long sequenceLastModified;

    private SabrMediaHeader(final int headerId,
                            @Nullable final String videoId,
                            final int itag,
                             final long lastModified,
                             @Nullable final String xtags,
                             final long startRange,
                             final int compressionAlgorithm,
                             final boolean initSegment,
                             final int sequenceNumber,
                             final long bitrateBps,
                             final long startMs,
                             final long durationMs,
                             final long contentLength,
                             final long timeRangeStartTicks,
                             final long timeRangeDurationTicks,
                             final int timeRangeTimescale,
                             final long sequenceLastModified) {
        this.headerId = headerId;
        this.videoId = videoId;
        this.itag = itag;
        this.lastModified = lastModified;
        this.xtags = xtags;
        this.startRange = startRange;
        this.compressionAlgorithm = compressionAlgorithm;
        this.initSegment = initSegment;
        this.sequenceNumber = sequenceNumber;
        this.bitrateBps = bitrateBps;
        this.startMs = startMs;
        this.durationMs = durationMs;
        this.contentLength = contentLength;
        this.timeRangeStartTicks = timeRangeStartTicks;
        this.timeRangeDurationTicks = timeRangeDurationTicks;
        this.timeRangeTimescale = timeRangeTimescale;
        this.sequenceLastModified = sequenceLastModified;
    }

    @Nonnull
    public static SabrMediaHeader normalized(final int headerId,
                                             @Nullable final String videoId,
                                             final int itag, final long lastModified,
                                             @Nullable final String xtags,
                                             final long startRange, final int compressionAlgorithm,
                                             final boolean initSegment, final int sequenceNumber,
                                             final long bitrateBps, final long startMs,
                                             final long durationMs, final long contentLength,
                                             final long timeRangeStartTicks,
                                             final long timeRangeDurationTicks,
                                             final int timeRangeTimescale,
                                             final long sequenceLastModified) {
        return new SabrMediaHeader(headerId, videoId, itag, lastModified, xtags, startRange,
                compressionAlgorithm, initSegment, sequenceNumber, bitrateBps, startMs, durationMs,
                contentLength, timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale,
                sequenceLastModified);
    }

    @Nonnull
    static SabrMediaHeader decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int headerId = -1;
        String videoId = null;
        int itag = -1;
        long lastModified = -1;
        String xtags = null;
        long startRange = -1;
        int compressionAlgorithm = -1;
        boolean initSegment = false;
        int sequenceNumber = -1;
        long bitrateBps = -1;
        long startMs = -1;
        long durationMs = -1;
        long contentLength = -1;
        long timeRangeStartTicks = -1;
        long timeRangeDurationTicks = -1;
        int timeRangeTimescale = -1;
        long sequenceLastModified = -1;

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    headerId = (int) field.getVarint();
                    break;
                case 2:
                    videoId = field.getString();
                    break;
                case 3:
                    itag = (int) field.getVarint();
                    break;
                case 4:
                    lastModified = field.getVarint();
                    break;
                case 5:
                    xtags = field.getString();
                    break;
                case 6:
                    startRange = field.getVarint();
                    break;
                case 7:
                    compressionAlgorithm = (int) field.getVarint();
                    break;
                case 8:
                    initSegment = field.getVarint() != 0;
                    break;
                case 9:
                    sequenceNumber = (int) field.getVarint();
                    break;
                case 10:
                    bitrateBps = field.getVarint();
                    break;
                case 11:
                    startMs = field.getVarint();
                    break;
                case 12:
                    durationMs = field.getVarint();
                    break;
                case 13:
                    final FormatId formatId = decodeFormatId(field.getBytes());
                    if (itag < 0) {
                        itag = formatId.itag;
                    }
                    if (lastModified < 0) {
                        lastModified = formatId.lastModified;
                    }
                    if (xtags == null) {
                        xtags = formatId.xtags;
                    }
                    break;
                case 14:
                    contentLength = field.getVarint();
                    break;
                case 15:
                    final TimeRange timeRange = decodeTimeRange(field.getBytes());
                    timeRangeStartTicks = timeRange.startTicks;
                    timeRangeDurationTicks = timeRange.durationTicks;
                    timeRangeTimescale = timeRange.timescale;
                    break;
                case 16:
                    sequenceLastModified = field.getVarint();
                    break;
                default:
                    break;
            }
        }

        if (timeRangeTimescale > 0) {
            if (startMs < 0 && timeRangeStartTicks >= 0) {
                startMs = timeRangeStartTicks * 1000L / timeRangeTimescale;
            }
            if (durationMs < 0 && timeRangeDurationTicks >= 0) {
                durationMs = timeRangeDurationTicks * 1000L / timeRangeTimescale;
            }
        }

        return new SabrMediaHeader(headerId, videoId, itag, lastModified, xtags, startRange,
                compressionAlgorithm, initSegment, sequenceNumber, bitrateBps, startMs, durationMs,
                contentLength, timeRangeStartTicks, timeRangeDurationTicks, timeRangeTimescale,
                sequenceLastModified);
    }

    @Nonnull
    private static FormatId decodeFormatId(@Nonnull final byte[] data) throws SabrProtocolException {
        int itag = -1;
        long lastModified = -1;
        String xtags = null;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                itag = (int) field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                lastModified = field.getVarint();
            } else if (field.getNumber() == 3
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                xtags = field.getString();
            }
        }
        return new FormatId(itag, lastModified, xtags);
    }

    @Nonnull
    private static TimeRange decodeTimeRange(@Nonnull final byte[] data)
            throws SabrProtocolException {
        long startTicks = -1;
        long durationTicks = -1;
        int timescale = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                startTicks = field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                durationTicks = field.getVarint();
            } else if (field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT) {
                timescale = (int) field.getVarint();
            }
        }
        return new TimeRange(startTicks, durationTicks, timescale);
    }

    public int getHeaderId() {
        return headerId;
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    public int getItag() {
        return itag;
    }

    public long getLastModified() {
        return lastModified;
    }

    @Nullable
    public String getXtags() {
        return xtags;
    }

    public long getStartRange() {
        return startRange;
    }

    public int getCompressionAlgorithm() {
        return compressionAlgorithm;
    }

    public boolean isInitSegment() {
        return initSegment;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getBitrateBps() {
        return bitrateBps;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getContentLength() {
        return contentLength;
    }

    public long getTimeRangeStartTicks() {
        return timeRangeStartTicks;
    }

    public long getTimeRangeDurationTicks() {
        return timeRangeDurationTicks;
    }

    public int getTimeRangeTimescale() {
        return timeRangeTimescale;
    }

    public long getSequenceLastModified() {
        return sequenceLastModified;
    }

    @Nonnull
    public String summarize() {
        return "id=" + headerId
                + ", itag=" + itag
                + ", init=" + initSegment
                + ", seq=" + sequenceNumber
                + ", startRange=" + startRange
                + ", startMs=" + startMs
                + ", durationMs=" + durationMs
                + ", contentLength=" + contentLength
                + ", compression=" + compressionAlgorithm
                + ", bitrateBps=" + bitrateBps
                + ", timeRange=" + timeRangeStartTicks + '+' + timeRangeDurationTicks
                + '/' + timeRangeTimescale
                + ", sequenceLmt=" + sequenceLastModified;
    }

    private static final class FormatId {
        private final int itag;
        private final long lastModified;
        @Nullable
        private final String xtags;

        private FormatId(final int itag,
                         final long lastModified,
                         @Nullable final String xtags) {
            this.itag = itag;
            this.lastModified = lastModified;
            this.xtags = xtags;
        }
    }

    private static final class TimeRange {
        private final long startTicks;
        private final long durationTicks;
        private final int timescale;

        private TimeRange(final long startTicks,
                          final long durationTicks,
                          final int timescale) {
            this.startTicks = startTicks;
            this.durationTicks = durationTicks;
            this.timescale = timescale;
        }
    }
}
