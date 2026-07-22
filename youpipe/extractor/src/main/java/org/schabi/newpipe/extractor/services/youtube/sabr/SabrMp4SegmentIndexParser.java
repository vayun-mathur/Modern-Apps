package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SabrMp4SegmentIndexParser {
    private static final String SIDX_BOX = "sidx";

    private SabrMp4SegmentIndexParser() {
    }

    @Nonnull
    static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                  @Nonnull final SabrFormatInitializationMetadata metadata)
            throws SabrProtocolException {
        return parse(initData,
                checkedRangeOffset(metadata.getIndexRangeStart(), initData.length),
                checkedRangeOffset(metadata.getIndexRangeEnd(), initData.length));
    }

    @Nonnull
    static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                  @Nonnull final YoutubeSabrFormat format)
            throws SabrProtocolException {
        return parse(initData, 0, initData.length - 1);
    }

    @Nonnull
    private static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                          final int indexStart,
                                          final int indexEnd)
            throws SabrProtocolException {
        if (indexEnd < indexStart) {
            throw new SabrProtocolException("Invalid MP4 SIDX range");
        }
        final int sidxOffset = findSidxBox(initData, indexStart, indexEnd + 1);
        return parseSidx(initData, sidxOffset, indexEnd + 1);
    }

    @Nonnull
    static SabrSegmentIndex parse(@Nonnull final byte[] initData)
            throws SabrProtocolException {
        final int sidxOffset = findSidxBox(initData, 0, initData.length);
        return parseSidx(initData, sidxOffset, initData.length);
    }

    @Nonnull
    private static SabrSegmentIndex parseSidx(@Nonnull final byte[] initData,
                                              final int sidxOffset,
                                              final int rangeEnd)
            throws SabrProtocolException {
        final long boxSize = readUint32(initData, sidxOffset);
        final int boxEnd = checkedBoxEnd(sidxOffset, boxSize, rangeEnd);
        int cursor = sidxOffset + 8;
        final int version = initData[cursor] & 0xff;
        cursor += 4; // reference_ID

        cursor += 4;
        final long timescale = readUint32(initData, cursor);
        cursor += 4;
        if (timescale <= 0) {
            throw new SabrProtocolException("Invalid MP4 SIDX timescale");
        }

        final long earliestPresentationTime;
        if (version == 0) {
            earliestPresentationTime = readUint32(initData, cursor);
            cursor += 8; // earliest_presentation_time + first_offset
        } else if (version == 1) {
            earliestPresentationTime = readUint64(initData, cursor);
            cursor += 16; // earliest_presentation_time + first_offset
        } else {
            throw new SabrProtocolException("Unsupported MP4 SIDX version: " + version);
        }

        cursor += 2; // reserved
        final int referenceCount = readUint16(initData, cursor);
        cursor += 2;
        final List<SabrSegmentIndex.Entry> entries = new ArrayList<>(referenceCount);
        long unscaledStart = earliestPresentationTime;
        for (int i = 0; i < referenceCount; i++) {
            if (cursor + 12 > boxEnd) {
                throw new SabrProtocolException("Truncated MP4 SIDX references");
            }
            final long reference = readUint32(initData, cursor);
            cursor += 4;
            final boolean nestedSidx = (reference & 0x80000000L) != 0;
            if (nestedSidx) {
                throw new SabrProtocolException("Nested MP4 SIDX references are unsupported");
            }
            final long duration = readUint32(initData, cursor);
            cursor += 8; // subsegment_duration + SAP flags
            entries.add(new SabrSegmentIndex.Entry(i + 1,
                    scaleToMs(unscaledStart, timescale),
                    scaleToMs(duration, timescale)));
            unscaledStart += duration;
        }
        return new SabrSegmentIndex(entries);
    }

    private static int findSidxBox(@Nonnull final byte[] data,
                                   final int start,
                                   final int end) throws SabrProtocolException {
        for (int offset = start; offset + 8 <= end; offset++) {
            if (SIDX_BOX.equals(new String(data, offset + 4, 4, StandardCharsets.US_ASCII))) {
                return offset;
            }
        }
        throw new SabrProtocolException("MP4 SIDX box not found");
    }

    private static int checkedRangeOffset(final long offset,
                                          final int length) throws SabrProtocolException {
        if (offset < 0 || offset >= length) {
            throw new SabrProtocolException("MP4 SIDX range outside init segment");
        }
        return (int) offset;
    }

    private static int checkedBoxEnd(final int offset,
                                     final long boxSize,
                                     final int rangeEnd) throws SabrProtocolException {
        if (boxSize < 8 || boxSize > Integer.MAX_VALUE || offset + boxSize > rangeEnd) {
            throw new SabrProtocolException("Invalid MP4 SIDX box size");
        }
        return offset + (int) boxSize;
    }

    private static int readUint16(@Nonnull final byte[] data,
                                  final int offset) throws SabrProtocolException {
        checkAvailable(data, offset, 2);
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long readUint32(@Nonnull final byte[] data,
                                   final int offset) throws SabrProtocolException {
        checkAvailable(data, offset, 4);
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    private static long readUint64(@Nonnull final byte[] data,
                                   final int offset) throws SabrProtocolException {
        final long high = readUint32(data, offset);
        final long low = readUint32(data, offset + 4);
        return (high << 32) | low;
    }

    private static void checkAvailable(@Nonnull final byte[] data,
                                       final int offset,
                                       final int length) throws SabrProtocolException {
        if (offset < 0 || offset + length > data.length) {
            throw new SabrProtocolException("Unexpected EOF while reading MP4 SIDX");
        }
    }

    private static long scaleToMs(final long value, final long timescale) {
        return (value * 1000L + timescale / 2L) / timescale;
    }
}
