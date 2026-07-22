package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

final class SabrWebmSegmentIndexParser {
    private static final long SEGMENT_ID = 0x18538067L;
    private static final long INFO_ID = 0x1549a966L;
    private static final long TIMECODE_SCALE_ID = 0x2ad7b1L;
    private static final long CUES_ID = 0x1c53bb6bL;
    private static final long CUE_POINT_ID = 0xbbL;
    private static final long CUE_TIME_ID = 0xb3L;
    private static final long DEFAULT_TIMECODE_SCALE_NANOS = 1000000L;

    private SabrWebmSegmentIndexParser() {
    }

    @Nonnull
    static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                  @Nonnull final SabrFormatInitializationMetadata metadata)
            throws SabrProtocolException {
        return parse(initData, metadata, metadata.getDurationUnits() > 0
                && metadata.getDurationTimescale() > 0
                ? scaleToMs(metadata.getDurationUnits(), metadata.getDurationTimescale())
                : -1);
    }

    @Nonnull
    static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                  @Nonnull final YoutubeSabrFormat format)
            throws SabrProtocolException {
        return parse(initData, null, format.getApproxDurationMs());
    }

    @Nonnull
    private static SabrSegmentIndex parse(@Nonnull final byte[] initData,
                                          final SabrFormatInitializationMetadata metadata,
                                          final long totalDurationMs)
            throws SabrProtocolException {
        final Element segment = findElement(initData, 0, initData.length, SEGMENT_ID);
        final long timecodeScaleNanos = readTimecodeScale(initData, segment);
        final Element cues = metadata == null
                ? findElement(initData, segment.contentStart, segment.contentEnd, CUES_ID)
                : findElement(initData,
                        checkedRangeOffset(metadata.getIndexRangeStart(), initData.length),
                        checkedRangeEnd(metadata.getIndexRangeEnd(), initData.length), CUES_ID);
        final List<Long> cueTimes = readCueTimes(initData, cues, timecodeScaleNanos);
        if (cueTimes.isEmpty()) {
            throw new SabrProtocolException("WebM cues contain no cue times");
        }

        final List<SabrSegmentIndex.Entry> entries = new ArrayList<>(cueTimes.size());
        for (int i = 0; i < cueTimes.size(); i++) {
            final long startMs = cueTimes.get(i);
            final long endMs;
            if (i + 1 < cueTimes.size()) {
                endMs = cueTimes.get(i + 1);
            } else if (totalDurationMs > startMs) {
                endMs = totalDurationMs;
            } else if (i > 0) {
                endMs = startMs + Math.max(1, startMs - cueTimes.get(i - 1));
            } else {
                endMs = startMs + 1;
            }
            entries.add(new SabrSegmentIndex.Entry(i + 1, startMs, Math.max(1, endMs - startMs)));
        }
        return new SabrSegmentIndex(entries);
    }

    private static long readTimecodeScale(@Nonnull final byte[] data,
                                          @Nonnull final Element segment)
            throws SabrProtocolException {
        final Element info = findElement(data, segment.contentStart, segment.contentEnd, INFO_ID);
        int offset = info.contentStart;
        while (offset < info.contentEnd) {
            final Element element = readElement(data, offset, info.contentEnd);
            if (element.id == TIMECODE_SCALE_ID) {
                return readUnsignedInteger(data, element.contentStart,
                        element.contentEnd - element.contentStart);
            }
            offset = element.contentEnd;
        }
        return DEFAULT_TIMECODE_SCALE_NANOS;
    }

    @Nonnull
    private static List<Long> readCueTimes(@Nonnull final byte[] data,
                                           @Nonnull final Element cues,
                                           final long timecodeScaleNanos)
            throws SabrProtocolException {
        final List<Long> cueTimes = new ArrayList<>();
        int offset = cues.contentStart;
        while (offset < cues.contentEnd) {
            final Element cuePoint = readElement(data, offset, cues.contentEnd);
            if (cuePoint.id == CUE_POINT_ID) {
                final long cueTime = readCueTime(data, cuePoint);
                if (cueTime >= 0) {
                    cueTimes.add(scaleWebmTimeToMs(cueTime, timecodeScaleNanos));
                }
            }
            offset = cuePoint.contentEnd;
        }
        return cueTimes;
    }

    private static long readCueTime(@Nonnull final byte[] data,
                                    @Nonnull final Element cuePoint)
            throws SabrProtocolException {
        int offset = cuePoint.contentStart;
        while (offset < cuePoint.contentEnd) {
            final Element element = readElement(data, offset, cuePoint.contentEnd);
            if (element.id == CUE_TIME_ID) {
                return readUnsignedInteger(data, element.contentStart,
                        element.contentEnd - element.contentStart);
            }
            offset = element.contentEnd;
        }
        return -1;
    }

    @Nonnull
    private static Element findElement(@Nonnull final byte[] data,
                                       final int start,
                                       final int end,
                                       final long id) throws SabrProtocolException {
        int offset = start;
        while (offset < end) {
            final Element element = readElement(data, offset, end);
            if (element.id == id) {
                return element;
            }
            offset = element.contentEnd;
        }
        throw new SabrProtocolException("WebM element not found: " + Long.toHexString(id));
    }

    @Nonnull
    private static Element readElement(@Nonnull final byte[] data,
                                       final int offset,
                                       final int containerEnd) throws SabrProtocolException {
        final Varint id = readElementId(data, offset, containerEnd);
        final Varint size = readElementSize(data, offset + id.length, containerEnd);
        final int contentStart = offset + id.length + size.length;
        final int contentEnd;
        if (size.unknown || size.value > Integer.MAX_VALUE
                || contentStart + size.value > containerEnd) {
            // Segment (master) in a DASH/SABR init declares its full media size, way past the init
            // buffer we actually have. We only parse within the buffer (cues are found via the index
            // range), so clamp instead of failing. Without it itag 303 (vp9/webm) never gets a
            // segment index -> uniform-tiling fallback -> drift -> periodic video freeze.
            contentEnd = containerEnd;
        } else {
            contentEnd = contentStart + (int) size.value;
        }
        return new Element(id.value, contentStart, contentEnd);
    }

    @Nonnull
    private static Varint readElementId(@Nonnull final byte[] data,
                                        final int offset,
                                        final int end) throws SabrProtocolException {
        final int length = readVintLength(data, offset, end);
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return new Varint(value, length, false);
    }

    @Nonnull
    private static Varint readElementSize(@Nonnull final byte[] data,
                                          final int offset,
                                          final int end) throws SabrProtocolException {
        final int length = readVintLength(data, offset, end);
        long value = data[offset] & (0xffL >> length);
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        final long unknownValue = (1L << (7 * length)) - 1L;
        return new Varint(value, length, value == unknownValue);
    }

    private static int readVintLength(@Nonnull final byte[] data,
                                      final int offset,
                                      final int end) throws SabrProtocolException {
        if (offset >= end) {
            throw new SabrProtocolException("Unexpected EOF while reading WebM vint");
        }
        final int first = data[offset] & 0xff;
        for (int length = 1; length <= 8; length++) {
            if ((first & (0x80 >> (length - 1))) != 0) {
                if (offset + length > end) {
                    throw new SabrProtocolException("Truncated WebM vint");
                }
                return length;
            }
        }
        throw new SabrProtocolException("Invalid WebM vint");
    }

    private static int checkedRangeOffset(final long offset,
                                          final int length) throws SabrProtocolException {
        if (offset < 0 || offset >= length) {
            throw new SabrProtocolException("WebM index range outside init segment");
        }
        return (int) offset;
    }

    private static int checkedRangeEnd(final long inclusiveEnd,
                                       final int length) throws SabrProtocolException {
        if (inclusiveEnd < 0 || inclusiveEnd >= length) {
            throw new SabrProtocolException("WebM index range outside init segment");
        }
        return (int) inclusiveEnd + 1;
    }

    private static long readUnsignedInteger(@Nonnull final byte[] data,
                                            final int offset,
                                            final int length) throws SabrProtocolException {
        if (length <= 0 || length > 8 || offset < 0 || offset + length > data.length) {
            throw new SabrProtocolException("Invalid WebM unsigned integer length");
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }

    private static long scaleWebmTimeToMs(final long cueTime,
                                          final long timecodeScaleNanos) {
        return (cueTime * timecodeScaleNanos + 500000L) / 1000000L;
    }

    private static long scaleToMs(final long value, final long timescale) {
        return (value * 1000L + timescale / 2L) / timescale;
    }

    private static final class Element {
        private final long id;
        private final int contentStart;
        private final int contentEnd;

        private Element(final long id, final int contentStart, final int contentEnd) {
            this.id = id;
            this.contentStart = contentStart;
            this.contentEnd = contentEnd;
        }
    }

    private static final class Varint {
        private final long value;
        private final int length;
        private final boolean unknown;

        private Varint(final long value, final int length, final boolean unknown) {
            this.value = value;
            this.length = length;
            this.unknown = unknown;
        }
    }
}
