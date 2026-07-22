package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming counterpart of {@link SabrResponseDecoder#decode(byte[])}: parse the UMP envelope from a
 * stream one part at a time, assembling MEDIA segments on the fly (via
 * {@link SabrMediaSegmentCollector.Incremental}) so the big MEDIA payloads are never all held at
 * once. Only the small control parts (everything except the MEDIA payloads) are kept and decoded
 * into a {@link SabrDecodedResponse}. This is what fixes the 4K OOM: peak transient drops from the
 * whole response body (50-150MB) to a single in-flight segment.
 */
public final class SabrStreamingResponseReader {
    private static final int MAX_CONTROL_PARTS = 512;
    private static final long MAX_CONTROL_PAYLOAD_BYTES = 512 * 1024L;

    private SabrStreamingResponseReader() {
    }

    @Nonnull
    public static Result read(@Nonnull final InputStream in)
            throws SabrProtocolException, IOException {
        return read(in, null);
    }

    @FunctionalInterface
    public interface SegmentConsumer {
        void accept(@Nonnull SabrMediaSegment segment) throws SabrProtocolException;
    }

    @FunctionalInterface
    public interface StoppableSegmentConsumer {
        boolean accept(@Nonnull SabrMediaSegment segment) throws SabrProtocolException;
    }

    /**
     * Streams completed segments directly to {@code segmentConsumer}. When a consumer is supplied,
     * completed segments are not retained by the result, bounding the response reader to one open
     * segment instead of the sum of every segment in the response.
     */
    @Nonnull
    public static Result read(@Nonnull final InputStream in,
                              final SegmentConsumer segmentConsumer)
            throws SabrProtocolException, IOException {
        return readUntil(in, segmentConsumer == null ? null : segment -> {
            segmentConsumer.accept(segment);
            return true;
        });
    }

    @Nonnull
    public static Result readUntil(@Nonnull final InputStream in,
                                   final StoppableSegmentConsumer segmentConsumer)
            throws SabrProtocolException, IOException {
        return readUntil(in, segmentConsumer, null);
    }

    @Nonnull
    public static Result readUntil(@Nonnull final InputStream in,
                                   final StoppableSegmentConsumer segmentConsumer,
                                   @Nullable final File spoolDirectory)
            throws SabrProtocolException, IOException {
        return readUntil(in, segmentConsumer, null, spoolDirectory);
    }

    @Nonnull
    public static Result readUntil(@Nonnull final InputStream in,
                                   final StoppableSegmentConsumer segmentConsumer,
                                   @Nullable final SegmentConsumer segmentStartConsumer,
                                   @Nullable final File spoolDirectory)
            throws SabrProtocolException, IOException {
        return readUntil(in, segmentConsumer, segmentStartConsumer, spoolDirectory,
                SabrMediaProtocol.builtin());
    }

    @Nonnull
    public static Result readUntil(@Nonnull final InputStream in,
                                   final StoppableSegmentConsumer segmentConsumer,
                                   @Nullable final SegmentConsumer segmentStartConsumer,
                                   @Nullable final File spoolDirectory,
                                   @Nonnull final SabrMediaProtocol mediaProtocol)
            throws SabrProtocolException, IOException {
        final List<UmpPart> controlParts = new ArrayList<>();
        final List<String> partSummaries = new ArrayList<>();
        final List<SabrMediaSegment> segments = new ArrayList<>();
        final int[] segmentCount = {0};
        final long[] mediaPayloadBytes = {0};
        final long[] mediaPartPayloadBytes = {0};
        final long[] controlPayloadBytes = {0};
        final long[] totalPayloadBytes = {0};
        final long[] maxPartBytes = {0};
        final long[] maxMediaPartPayloadBytes = {0};
        final long[] maxSegmentBytes = {0};
        // headerId -> total media bytes seen, so the decoded response passes the same integrity check
        // (getIntegrityIssues -> "missing-media") as the buffered path WITHOUT retaining the bytes.
        final Map<Integer, Long> mediaBytesByHeaderId = new HashMap<>();
        final SabrMediaSegmentCollector.Incremental collector =
                new SabrMediaSegmentCollector.Incremental(spoolDirectory, mediaProtocol);
        try {
            UmpReader.readPayloadsUntil(in, (type, size, payloadStream) -> {
                SabrDecodedResponse.addPartSummary(partSummaries, type, size);
                maxPartBytes[0] = Math.max(maxPartBytes[0], size);
                totalPayloadBytes[0] += size;
                if (type != mediaProtocol.getMediaPartType()
                        && (controlParts.size() >= MAX_CONTROL_PARTS
                        || controlPayloadBytes[0] + size > MAX_CONTROL_PAYLOAD_BYTES)) {
                    throw new SabrProtocolException("SABR control response exceeded Host limit");
                }
                if (type == mediaProtocol.getHeaderPartType()) {
                    final byte[] payload = readPayloadBytes(payloadStream, size);
                    controlPayloadBytes[0] += payload.length;
                    // small (just the header) -> keep so the decoder records it (observeHeader).
                    controlParts.add(new UmpPart(type, payload.length, payload));
                    try {
                        final SabrMediaSegment started = collector.onMediaHeader(payload);
                        if (started != null && segmentStartConsumer != null) {
                            segmentStartConsumer.accept(started);
                        }
                    } catch (final SabrProtocolException ignored) {
                        if (!isMalformedMediaHeader(payload, mediaProtocol)) {
                            throw ignored;
                        }
                        // decodeParts records the malformed header. Following MEDIA is deliberately
                        // left without an open header so integrity recovery requests a clean batch.
                    }
                } else if (type == mediaProtocol.getMediaPartType()) {
                    mediaPartPayloadBytes[0] += size;
                    if (size > 0) {
                        final int headerId = payloadStream.read();
                        if (headerId < 0) {
                            throw new SabrRecoverableException(
                                    "Unexpected EOF while reading SABR media header id");
                        }
                        final int mediaBytes = size - 1;
                        // big payload -> assemble into the open segment, do NOT retain.
                        collector.onMedia(headerId, payloadStream, mediaBytes);
                        maxMediaPartPayloadBytes[0] =
                                Math.max(maxMediaPartPayloadBytes[0], mediaBytes);
                        mediaPayloadBytes[0] += mediaBytes;
                        mediaBytesByHeaderId.put(headerId,
                                mediaBytesByHeaderId.getOrDefault(headerId, 0L)
                                        + mediaBytes);
                    }
                } else if (type == mediaProtocol.getEndPartType()) {
                    final byte[] payload = readPayloadBytes(payloadStream, size);
                    controlPayloadBytes[0] += payload.length;
                    final SabrMediaSegment segment = collector.onMediaEnd(payload);
                    controlParts.add(new UmpPart(type, payload.length, payload));
                    if (segment != null) {
                        segmentCount[0]++;
                        maxSegmentBytes[0] = Math.max(maxSegmentBytes[0], segment.getLength());
                        if (segmentConsumer == null) {
                            segments.add(segment);
                        } else {
                            return segmentConsumer.accept(segment);
                        }
                    }
                } else {
                    final byte[] payload = readPayloadBytes(payloadStream, size);
                    controlPayloadBytes[0] += payload.length;
                    controlParts.add(new UmpPart(type, payload.length, payload));
                }
                return true;
            });
        } finally {
            // Any header left open after EOF, cancellation or failure must wake a growing-file
            // reader. Completed segments have already been removed from the collector.
            collector.abort();
        }
        final SabrDecodedResponse decoded = SabrResponseDecoder.decodeParts(
                controlParts, mediaProtocol);
        decoded.setPartSummaries(partSummaries);
        for (final Map.Entry<Integer, Long> entry : mediaBytesByHeaderId.entrySet()) {
            decoded.addMediaBytes(entry.getKey(), entry.getValue());
        }
        return new Result(decoded, segments, segmentCount[0], mediaPayloadBytes[0],
                mediaPartPayloadBytes[0], controlPayloadBytes[0], totalPayloadBytes[0],
                maxPartBytes[0], maxMediaPartPayloadBytes[0], maxSegmentBytes[0]);
    }

    @Nonnull
    private static byte[] readPayloadBytes(@Nonnull final InputStream input, final int size)
            throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        final byte[] buffer = new byte[8192];
        int remaining = size;
        while (remaining > 0) {
            final int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading UMP part data");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private static boolean isMalformedMediaHeader(@Nonnull final byte[] payload,
                                                  @Nonnull final SabrMediaProtocol mediaProtocol) {
        try {
            mediaProtocol.decodeHeader(payload);
            return false;
        } catch (final SabrProtocolException e) {
            return true;
        }
    }

    /** The decoded control response plus the segments assembled while streaming. */
    public static final class Result {
        @Nonnull
        private final SabrDecodedResponse decodedResponse;
        @Nonnull
        private final List<SabrMediaSegment> segments;
        private final int segmentCount;
        private final long mediaPayloadBytes;
        private final long mediaPartPayloadBytes;
        private final long controlPayloadBytes;
        private final long totalPayloadBytes;
        private final long maxPartBytes;
        private final long maxMediaPartPayloadBytes;
        private final long maxSegmentBytes;

        Result(@Nonnull final SabrDecodedResponse decodedResponse,
               @Nonnull final List<SabrMediaSegment> segments,
               final int segmentCount,
               final long mediaPayloadBytes,
               final long mediaPartPayloadBytes,
               final long controlPayloadBytes,
               final long totalPayloadBytes,
               final long maxPartBytes,
               final long maxMediaPartPayloadBytes,
               final long maxSegmentBytes) {
            this.decodedResponse = decodedResponse;
            this.segments = segments;
            this.segmentCount = segmentCount;
            this.mediaPayloadBytes = mediaPayloadBytes;
            this.mediaPartPayloadBytes = mediaPartPayloadBytes;
            this.controlPayloadBytes = controlPayloadBytes;
            this.totalPayloadBytes = totalPayloadBytes;
            this.maxPartBytes = maxPartBytes;
            this.maxMediaPartPayloadBytes = maxMediaPartPayloadBytes;
            this.maxSegmentBytes = maxSegmentBytes;
        }

        @Nonnull
        public SabrDecodedResponse getDecodedResponse() {
            return decodedResponse;
        }

        @Nonnull
        public List<SabrMediaSegment> getSegments() {
            return segments;
        }

        public int getSegmentCount() {
            return segmentCount;
        }

        public long getMediaPayloadBytes() {
            return mediaPayloadBytes;
        }

        public long getMediaPartPayloadBytes() {
            return mediaPartPayloadBytes;
        }

        public long getControlPayloadBytes() {
            return controlPayloadBytes;
        }

        public long getTotalPayloadBytes() {
            return totalPayloadBytes;
        }

        public long getMaxPartBytes() {
            return maxPartBytes;
        }

        public long getMaxMediaPartPayloadBytes() {
            return maxMediaPartPayloadBytes;
        }

        public long getMaxSegmentBytes() {
            return maxSegmentBytes;
        }
    }
}
