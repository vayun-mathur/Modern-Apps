package org.schabi.newpipe.extractor.services.youtube.sabr;

import org.brotli.dec.BrotliInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class SabrMediaSegmentCollector {
    private static final int MIN_PROGRESSIVE_SEGMENT_BYTES = 64 * 1024;

    private SabrMediaSegmentCollector() {
    }

    @Nonnull
    public static List<SabrMediaSegment> collect(@Nonnull final SabrDecodedResponse response)
            throws SabrProtocolException {
        final List<SabrMediaSegment> segments = new ArrayList<>();
        final Map<Integer, OpenSegment> openSegments = new HashMap<>();
        for (final UmpPart part : response.getParts()) {
            final byte[] partData = part.getRawData();
            switch (part.getType()) {
                case SabrResponseDecoder.MEDIA_HEADER:
                    final SabrMediaHeader header = SabrMediaHeader.decode(partData);
                    openSegments.put(header.getHeaderId(), new OpenSegment(header));
                    break;
                case SabrResponseDecoder.MEDIA:
                    if (partData.length > 0) {
                        final OpenSegment openSegment = openSegments.get(partData[0] & 0xff);
                        if (openSegment != null) {
                            openSegment.write(partData, 1, partData.length - 1);
                        }
                    }
                    break;
                case SabrResponseDecoder.MEDIA_END:
                    if (partData.length > 0) {
                        final OpenSegment openSegment = openSegments.remove(partData[0] & 0xff);
                        if (openSegment != null) {
                            segments.add(openSegment.toSegment());
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return segments;
    }

    @Nullable
    public static SabrMediaSegment find(@Nonnull final SabrDecodedResponse response,
                                        @Nonnull final SabrSegmentRequest request)
            throws SabrProtocolException {
        for (final SabrMediaSegment segment : collect(response)) {
            if (request.matches(segment.getHeader())) {
                return segment;
            }
        }
        return null;
    }

    /**
     * Incremental collector for the streaming path: feed MEDIA_HEADER / MEDIA / MEDIA_END parts as
     * they arrive and get each completed segment back from {@link #onMediaEnd}, so the caller never
     * has to retain all the MEDIA parts at once (that whole-body buffering was the 4K OOM).
     */
    public static final class Incremental {
        private final Map<Integer, OpenSegment> openSegments = new HashMap<>();
        @Nullable
        private final File spoolDirectory;
        @Nonnull
        private final SabrMediaProtocol mediaProtocol;

        public Incremental() {
            this(null, SabrMediaProtocol.builtin());
        }

        public Incremental(@Nullable final File spoolDirectory) {
            this(spoolDirectory, SabrMediaProtocol.builtin());
        }

        public Incremental(@Nullable final File spoolDirectory,
                           @Nonnull final SabrMediaProtocol mediaProtocol) {
            this.spoolDirectory = spoolDirectory;
            this.mediaProtocol = mediaProtocol;
        }

        @Nullable
        public SabrMediaSegment onMediaHeader(@Nonnull final byte[] partData)
                throws SabrProtocolException {
            final SabrMediaHeader header = mediaProtocol.decodeHeader(partData);
            final OpenSegment current = new OpenSegment(header, spoolDirectory);
            final OpenSegment previous = openSegments.put(header.getHeaderId(), current);
            if (previous != null) {
                previous.abort();
            }
            return current.getProgressiveSegment();
        }

        public void onMedia(@Nonnull final byte[] partData) throws SabrProtocolException {
            if (partData.length > 0) {
                final OpenSegment openSegment = openSegments.get(partData[0] & 0xff);
                if (openSegment != null) {
                    openSegment.write(partData, 1, partData.length - 1);
                }
            }
        }

        public void onMedia(final int headerId,
                            @Nonnull final InputStream input,
                            final int count) throws SabrProtocolException {
            final OpenSegment openSegment = openSegments.get(headerId);
            if (openSegment != null) {
                openSegment.write(input, count);
            } else {
                drain(input, count);
            }
        }

        @Nullable
        public SabrMediaSegment onMediaEnd(@Nonnull final byte[] partData)
                throws SabrProtocolException {
            if (partData.length > 0) {
                final OpenSegment openSegment = openSegments.remove(partData[0] & 0xff);
                if (openSegment != null) {
                    try {
                        return openSegment.toSegment();
                    } catch (final SabrProtocolException e) {
                        openSegment.abort();
                        throw e;
                    }
                }
            }
            return null;
        }

        public void abort() {
            for (final OpenSegment segment : openSegments.values()) {
                segment.abort();
            }
            openSegments.clear();
        }
    }

    private static final class OpenSegment {
        @Nonnull
        private final SabrMediaHeader header;
        @Nullable
        private final byte[] fixedData;
        @Nullable
        private final ByteArrayOutputStream dynamicData;
        @Nullable
        private final File file;
        @Nullable
        private final OutputStream fileOutput;
        @Nullable
        private final SabrMediaSegment progressiveSegment;
        private int length;
        private boolean fileOutputClosed;

        private OpenSegment(@Nonnull final SabrMediaHeader header) throws SabrProtocolException {
            this(header, null);
        }

        private OpenSegment(@Nonnull final SabrMediaHeader header,
                            @Nullable final File spoolDirectory) throws SabrProtocolException {
            this.header = header;
            final long contentLength = header.getContentLength();
            if (spoolDirectory != null
                    && header.getCompressionAlgorithm() <= 0
                    && !header.isInitSegment()) {
                if (contentLength > Integer.MAX_VALUE) {
                    throw new SabrProtocolException("SABR media segment too large: headerId="
                            + header.getHeaderId() + ", length=" + contentLength);
                }
                if (!spoolDirectory.exists() && !spoolDirectory.mkdirs()) {
                    throw new SabrRecoverableException("Could not create SABR spool directory: "
                            + spoolDirectory);
                }
                try {
                    file = File.createTempFile("sabr-" + header.getItag() + '-'
                            + header.getSequenceNumber() + '-', ".seg", spoolDirectory);
                    fileOutput = new FileOutputStream(file);
                    progressiveSegment = contentLength < MIN_PROGRESSIVE_SEGMENT_BYTES ? null
                            : SabrMediaSegment.progressive(header, file, (int) contentLength);
                } catch (final IOException e) {
                    throw new SabrRecoverableException("Could not open SABR spool file", e);
                }
                fixedData = null;
                dynamicData = null;
            } else if (contentLength >= 0) {
                if (contentLength > Integer.MAX_VALUE) {
                    throw new SabrProtocolException("SABR media segment too large: headerId="
                            + header.getHeaderId() + ", length=" + contentLength);
                }
                fixedData = new byte[(int) contentLength];
                dynamicData = null;
                file = null;
                fileOutput = null;
                progressiveSegment = null;
            } else {
                fixedData = null;
                dynamicData = new ByteArrayOutputStream();
                file = null;
                fileOutput = null;
                progressiveSegment = null;
            }
        }

        @Nullable
        private SabrMediaSegment getProgressiveSegment() {
            return progressiveSegment;
        }

        private void write(@Nonnull final byte[] bytes, final int offset, final int count)
                throws SabrProtocolException {
            if (count <= 0) {
                return;
            }
            ensureLengthFits(count);
            ensureExpectedLengthNotExceeded(count);
            if (fixedData != null) {
                if (length + count > fixedData.length) {
                    throw new SabrRecoverableException("SABR media length overflow: headerId="
                            + header.getHeaderId()
                            + ", expected=" + fixedData.length
                            + ", actual>=" + (length + count));
                }
                System.arraycopy(bytes, offset, fixedData, length, count);
            } else if (fileOutput != null) {
                try {
                    fileOutput.write(bytes, offset, count);
                    length += count;
                    if (progressiveSegment != null) {
                        progressiveSegment.onBytesWritten(count);
                    }
                } catch (final IOException e) {
                    throw new SabrRecoverableException("Could not write SABR spool file", e);
                }
            } else {
                dynamicData.write(bytes, offset, count);
            }
            if (fileOutput == null) {
                length += count;
            }
        }

        private void write(@Nonnull final InputStream input, final int count)
                throws SabrProtocolException {
            if (count <= 0) {
                return;
            }
            ensureLengthFits(count);
            if (isExpectedLengthExceeded(count)) {
                drain(input, count);
                throw lengthOverflowException(count);
            }
            if (fixedData != null) {
                if (length + count > fixedData.length) {
                    drain(input, count);
                    throw new SabrRecoverableException("SABR media length overflow: headerId="
                            + header.getHeaderId()
                            + ", expected=" + fixedData.length
                            + ", actual>=" + (length + count));
                }
                readFully(input, fixedData, length, count);
            } else {
                final byte[] buffer = new byte[8192];
                int remaining = count;
                while (remaining > 0) {
                    final int read;
                    try {
                        read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    } catch (final IOException e) {
                        throw new SabrRecoverableException("Could not read SABR media payload", e);
                    }
                    if (read < 0) {
                        throw new SabrRecoverableException("Unexpected EOF in SABR media payload");
                    }
                    if (fileOutput != null) {
                        try {
                            fileOutput.write(buffer, 0, read);
                            length += read;
                            if (progressiveSegment != null) {
                                progressiveSegment.onBytesWritten(read);
                            }
                        } catch (final IOException e) {
                            throw new SabrRecoverableException(
                                    "Could not write SABR spool file", e);
                        }
                    } else {
                        dynamicData.write(buffer, 0, read);
                    }
                    remaining -= read;
                }
            }
            if (fileOutput == null) {
                length += count;
            }
        }

        @Nonnull
        private SabrMediaSegment toSegment() throws SabrProtocolException {
            if (fileOutput != null) {
                closeFileOutput();
                if (header.getContentLength() >= 0 && length != header.getContentLength()) {
                    throw new SabrRecoverableException("SABR media length mismatch: headerId="
                            + header.getHeaderId()
                            + ", expected=" + header.getContentLength()
                            + ", actual=" + length);
                }
                if (progressiveSegment != null) {
                    progressiveSegment.completeProgressive();
                    return progressiveSegment;
                }
                return new SabrMediaSegment(header, file, length);
            }
            final byte[] rawBytes;
            if (fixedData != null) {
                rawBytes = fixedData;
            } else {
                rawBytes = dynamicData.toByteArray();
            }
            if (header.getContentLength() >= 0 && length != header.getContentLength()) {
                throw new SabrRecoverableException("SABR media length mismatch: headerId="
                        + header.getHeaderId()
                        + ", expected=" + header.getContentLength()
                        + ", actual=" + length);
            }
            return new SabrMediaSegment(header, maybeDecompress(header, rawBytes));
        }

        private void ensureLengthFits(final int count) throws SabrProtocolException {
            if (length > Integer.MAX_VALUE - count) {
                throw new SabrProtocolException("SABR media segment too large: headerId="
                        + header.getHeaderId() + ", length>" + Integer.MAX_VALUE);
            }
        }

        private void ensureExpectedLengthNotExceeded(final int count)
                throws SabrProtocolException {
            if (isExpectedLengthExceeded(count)) {
                throw lengthOverflowException(count);
            }
        }

        private boolean isExpectedLengthExceeded(final int count) {
            return header.getContentLength() >= 0 && length + (long) count
                    > header.getContentLength();
        }

        @Nonnull
        private SabrRecoverableException lengthOverflowException(final int count) {
            return new SabrRecoverableException("SABR media length overflow: headerId="
                    + header.getHeaderId()
                    + ", expected=" + header.getContentLength()
                    + ", actual>=" + (length + (long) count));
        }

        private void abort() {
            if (progressiveSegment != null) {
                progressiveSegment.failProgressive(
                        new IOException("SABR media segment ended before MEDIA_END"));
            }
            try {
                closeFileOutput();
            } catch (final SabrProtocolException ignored) {
                // Best-effort cleanup after an already-failing segment.
            }
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }

        private void closeFileOutput() throws SabrProtocolException {
            if (fileOutput == null || fileOutputClosed) {
                return;
            }
            try {
                fileOutput.close();
                fileOutputClosed = true;
            } catch (final IOException e) {
                throw new SabrRecoverableException("Could not close SABR spool file", e);
            }
        }

        @Nonnull
        private static byte[] maybeDecompress(@Nonnull final SabrMediaHeader header,
                                              @Nonnull final byte[] bytes)
                throws SabrProtocolException {
            final int compressionAlgorithm = header.getCompressionAlgorithm();
            if (compressionAlgorithm <= 0) {
                return bytes;
            }
            if (compressionAlgorithm == 1) {
                return gunzip(bytes);
            }
            if (compressionAlgorithm == 2) {
                return brotli(bytes);
            }
            throw new SabrProtocolException("Unsupported SABR media compression: "
                    + compressionAlgorithm);
        }

        @Nonnull
        private static byte[] gunzip(@Nonnull final byte[] bytes) throws SabrProtocolException {
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (final IOException e) {
                throw new SabrRecoverableException(
                        "Could not decompress gzip SABR media segment", e);
            }
        }

        @Nonnull
        private static byte[] brotli(@Nonnull final byte[] bytes) throws SabrProtocolException {
            try (BrotliInputStream input = new BrotliInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (final IOException e) {
                throw new SabrRecoverableException(
                        "Could not decompress brotli SABR media segment", e);
            }
        }
    }

    private static void readFully(@Nonnull final InputStream input,
                                  @Nonnull final byte[] target,
                                  final int offset,
                                  final int count) throws SabrProtocolException {
        int current = offset;
        int remaining = count;
        while (remaining > 0) {
            final int read;
            try {
                read = input.read(target, current, remaining);
            } catch (final IOException e) {
                throw new SabrRecoverableException("Could not read SABR media payload", e);
            }
            if (read < 0) {
                throw new SabrRecoverableException("Unexpected EOF in SABR media payload");
            }
            current += read;
            remaining -= read;
        }
    }

    private static void drain(@Nonnull final InputStream input, final int count)
            throws SabrProtocolException {
        final byte[] buffer = new byte[8192];
        int remaining = count;
        while (remaining > 0) {
            final int read;
            try {
                read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            } catch (final IOException e) {
                throw new SabrRecoverableException("Could not drain SABR media payload", e);
            }
            if (read < 0) {
                throw new SabrRecoverableException("Unexpected EOF in SABR media payload");
            }
            remaining -= read;
        }
    }
}
