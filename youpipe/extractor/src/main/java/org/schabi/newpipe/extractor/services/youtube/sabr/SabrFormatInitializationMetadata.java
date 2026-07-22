package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrFormatInitializationMetadata {
    @Nonnull
    private final byte[] rawSummaryBytes;
    @Nullable
    private final String videoId;
    private final int itag;
    private final long lastModified;
    @Nullable
    private final String xtags;
    private final long endTimeMs;
    private final long endSegmentNumber;
    @Nullable
    private final String mimeType;
    private final long initRangeStart;
    private final long initRangeEnd;
    private final long indexRangeStart;
    private final long indexRangeEnd;
    private final long field8;
    private final long durationUnits;
    private final long durationTimescale;

    private SabrFormatInitializationMetadata(@Nullable final String videoId,
                                             @Nonnull final byte[] rawSummaryBytes,
                                             final int itag,
                                             final long lastModified,
                                             @Nullable final String xtags,
                                              final long endTimeMs,
                                              final long endSegmentNumber,
                                              @Nullable final String mimeType,
                                              final long initRangeStart,
                                               final long initRangeEnd,
                                               final long indexRangeStart,
                                               final long indexRangeEnd,
                                               final long field8,
                                               final long durationUnits,
                                               final long durationTimescale) {
        this.videoId = videoId;
        this.rawSummaryBytes = rawSummaryBytes;
        this.itag = itag;
        this.lastModified = lastModified;
        this.xtags = xtags;
        this.endTimeMs = endTimeMs;
        this.endSegmentNumber = endSegmentNumber;
        this.mimeType = mimeType;
        this.initRangeStart = initRangeStart;
        this.initRangeEnd = initRangeEnd;
        this.indexRangeStart = indexRangeStart;
        this.indexRangeEnd = indexRangeEnd;
        this.field8 = field8;
        this.durationUnits = durationUnits;
        this.durationTimescale = durationTimescale;
    }

    @Nonnull
    public static SabrFormatInitializationMetadata normalized(
            @Nullable final String videoId, final int itag, final long lastModified,
            @Nullable final String xtags, final long endTimeMs, final long endSegmentNumber,
            @Nullable final String mimeType, final long initRangeStart, final long initRangeEnd,
            final long indexRangeStart, final long indexRangeEnd, final long field8,
            final long durationUnits, final long durationTimescale) {
        if (itag <= 0 || endSegmentNumber < -1 || durationUnits < -1
                || durationTimescale < -1) {
            throw new IllegalArgumentException("Invalid normalized SABR format metadata");
        }
        return new SabrFormatInitializationMetadata(videoId, new byte[0], itag, lastModified,
                xtags, endTimeMs, endSegmentNumber, mimeType, initRangeStart, initRangeEnd,
                indexRangeStart, indexRangeEnd, field8, durationUnits, durationTimescale);
    }

    @Nonnull
    static SabrFormatInitializationMetadata decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        String videoId = null;
        int itag = -1;
        long lastModified = -1;
        String xtags = null;
        long endTimeMs = -1;
        long endSegmentNumber = -1;
        String mimeType = null;
        long initRangeStart = -1;
        long initRangeEnd = -1;
        long indexRangeStart = -1;
        long indexRangeEnd = -1;
        long field8 = -1;
        long durationUnits = -1;
        long durationTimescale = -1;

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    videoId = field.getString();
                    break;
                case 2:
                    for (final SabrProto.Field formatField : SabrProto.readFields(field.getBytes())) {
                        if (formatField.getNumber() == 1) {
                            itag = (int) formatField.getVarint();
                        } else if (formatField.getNumber() == 2) {
                            lastModified = formatField.getVarint();
                        } else if (formatField.getNumber() == 3) {
                            xtags = formatField.getString();
                        }
                    }
                    break;
                case 3:
                    endTimeMs = field.getVarint();
                    break;
                case 4:
                    endSegmentNumber = field.getVarint();
                    break;
                case 5:
                    mimeType = field.getString();
                    break;
                case 6:
                    final Range initRange = decodeRange(field.getBytes());
                    initRangeStart = initRange.start;
                    initRangeEnd = initRange.end;
                    break;
                case 7:
                    final Range indexRange = decodeRange(field.getBytes());
                    indexRangeStart = indexRange.start;
                    indexRangeEnd = indexRange.end;
                    break;
                case 8:
                    field8 = field.getVarint();
                    break;
                case 9:
                    durationUnits = field.getVarint();
                    break;
                case 10:
                    durationTimescale = field.getVarint();
                    break;
                default:
                    break;
            }
        }

        return new SabrFormatInitializationMetadata(videoId, data.clone(), itag, lastModified, xtags,
                endTimeMs, endSegmentNumber, mimeType, initRangeStart, initRangeEnd,
                indexRangeStart, indexRangeEnd, field8, durationUnits, durationTimescale);
    }

    @Nonnull
    private static Range decodeRange(@Nonnull final byte[] data) throws SabrProtocolException {
        long start = -1;
        long end = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if ((field.getNumber() == 1 || field.getNumber() == 3)
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                start = field.getVarint();
            } else if ((field.getNumber() == 2 || field.getNumber() == 4)
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                end = field.getVarint();
            }
        }
        return new Range(start, end);
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

    public long getEndSegmentNumber() {
        return endSegmentNumber;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    public long getDurationUnits() {
        return durationUnits;
    }

    public long getDurationTimescale() {
        return durationTimescale;
    }

    public long getInitRangeStart() {
        return initRangeStart;
    }

    public long getInitRangeEnd() {
        return initRangeEnd;
    }

    public long getIndexRangeStart() {
        return indexRangeStart;
    }

    public long getIndexRangeEnd() {
        return indexRangeEnd;
    }

    public long getField8() {
        return field8;
    }

    @Nonnull
    public String summarize() {
        String unknown = "unknown-error";
        try {
            unknown = SabrProto.summarizeUnknownFields(rawSummaryBytes, 1, 2, 3, 4, 5, 6, 7, 8,
                    9, 10);
        } catch (final Exception ignored) {
        }
        return "itag=" + itag
                + ", endSegment=" + endSegmentNumber
                + ", endTimeMs=" + endTimeMs
                + ", mime=" + (mimeType == null ? "null" : mimeType)
                + ", init=" + initRangeStart + '-' + initRangeEnd
                + ", index=" + indexRangeStart + '-' + indexRangeEnd
                + ", field8=" + field8
                + ", duration=" + durationUnits + '/' + durationTimescale
                + ", unknown=" + unknown;
    }

    private static final class Range {
        private final long start;
        private final long end;

        private Range(final long start, final long end) {
            this.start = start;
            this.end = end;
        }
    }
}
