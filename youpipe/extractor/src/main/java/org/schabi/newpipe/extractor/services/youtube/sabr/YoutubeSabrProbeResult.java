package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.List;

public final class YoutubeSabrProbeResult {
    @Nonnull
    private final YoutubeSabrInfo info;
    @Nonnull
    private final SabrDecodedResponse decodedResponse;
    @Nonnull
    private final List<SabrMediaSegment> segments;
    private final int segmentCount;
    private final int responseCode;
    @Nonnull
    private final String contentType;
    private final long responseBytes;
    private final long mediaPayloadBytes;
    private final long mediaPartPayloadBytes;
    private final long controlPayloadBytes;
    private final long totalPayloadBytes;
    private final long maxPartBytes;
    private final long maxMediaPartPayloadBytes;
    private final long maxSegmentBytes;
    private final long requestElapsedMs;
    private final long firstSegmentElapsedMs;

    YoutubeSabrProbeResult(@Nonnull final YoutubeSabrInfo info,
                           @Nonnull final SabrDecodedResponse decodedResponse,
                           @Nonnull final List<SabrMediaSegment> segments,
                           final int segmentCount,
                           final int responseCode,
                           @Nonnull final String contentType,
                           final long responseBytes,
                           final long mediaPayloadBytes,
                           final long mediaPartPayloadBytes,
                           final long controlPayloadBytes,
                           final long totalPayloadBytes,
                           final long maxPartBytes,
                           final long maxMediaPartPayloadBytes,
                           final long maxSegmentBytes,
                           final long requestElapsedMs,
                           final long firstSegmentElapsedMs) {
        this.info = info;
        this.decodedResponse = decodedResponse;
        this.segments = segments;
        this.segmentCount = segmentCount;
        this.responseCode = responseCode;
        this.contentType = contentType;
        this.responseBytes = responseBytes;
        this.mediaPayloadBytes = mediaPayloadBytes;
        this.mediaPartPayloadBytes = mediaPartPayloadBytes;
        this.controlPayloadBytes = controlPayloadBytes;
        this.totalPayloadBytes = totalPayloadBytes;
        this.maxPartBytes = maxPartBytes;
        this.maxMediaPartPayloadBytes = maxMediaPartPayloadBytes;
        this.maxSegmentBytes = maxSegmentBytes;
        this.requestElapsedMs = requestElapsedMs;
        this.firstSegmentElapsedMs = firstSegmentElapsedMs;
    }

    @Nonnull
    public YoutubeSabrInfo getInfo() {
        return info;
    }

    @Nonnull
    public SabrDecodedResponse getDecodedResponse() {
        return decodedResponse;
    }

    /** Segments assembled while streaming the response (the buffered path collected these later). */
    @Nonnull
    public List<SabrMediaSegment> getSegments() {
        return segments;
    }

    /** Number delivered, including segments streamed to a consumer and therefore not retained. */
    public int getSegmentCount() {
        return segmentCount;
    }

    public int getResponseCode() {
        return responseCode;
    }

    @Nonnull
    public String getContentType() {
        return contentType;
    }

    /** Raw UMP response bytes consumed from the HTTP body, including protocol overhead. */
    public long getResponseBytes() {
        return responseBytes;
    }

    /** Raw media bytes excluding the one-byte MEDIA header id in each UMP MEDIA part. */
    public long getMediaPayloadBytes() {
        return mediaPayloadBytes;
    }

    /** UMP MEDIA part payload bytes, including the per-part header id byte. */
    public long getMediaPartPayloadBytes() {
        return mediaPartPayloadBytes;
    }

    /** Non-MEDIA UMP payload bytes, including media headers and media-end markers. */
    public long getControlPayloadBytes() {
        return controlPayloadBytes;
    }

    /** Sum of all UMP part payload bytes, excluding UMP integer framing overhead. */
    public long getTotalPayloadBytes() {
        return totalPayloadBytes;
    }

    /** Largest single UMP part payload in this response. */
    public long getMaxPartBytes() {
        return maxPartBytes;
    }

    /** Largest single UMP MEDIA payload in this response, excluding the header id byte. */
    public long getMaxMediaPartPayloadBytes() {
        return maxMediaPartPayloadBytes;
    }

    /** Largest completed media segment produced while reading this response. */
    public long getMaxSegmentBytes() {
        return maxSegmentBytes;
    }

    /** Wall-clock time to POST and consume the full SABR response body. */
    public long getRequestElapsedMs() {
        return requestElapsedMs;
    }

    /** Wall-clock time until the first completed media segment was available, or -1 if none. */
    public long getFirstSegmentElapsedMs() {
        return firstSegmentElapsedMs;
    }
}
