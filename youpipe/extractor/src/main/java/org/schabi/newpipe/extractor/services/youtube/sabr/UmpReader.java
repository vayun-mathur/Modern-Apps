package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for YouTube's UMP envelope. UMP uses its own compact integer format, not protobuf varints.
 */
public final class UmpReader {
    private UmpReader() {
    }

    /** Receives one UMP part at a time (used by {@link #readStreaming}). */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(int type, @Nonnull byte[] payload) throws SabrProtocolException;
    }

    /** Receives one UMP part and returns false when the caller has enough data. */
    @FunctionalInterface
    public interface StoppablePartConsumer {
        boolean accept(int type, @Nonnull byte[] payload) throws SabrProtocolException;
    }

    /** Receives one UMP part payload as a bounded stream. The consumer may stop at part boundary. */
    @FunctionalInterface
    public interface StoppablePayloadConsumer {
        boolean accept(int type, int size, @Nonnull InputStream payload)
                throws SabrProtocolException, IOException;
    }

    /**
     * Stream the UMP envelope: read one part (type, size, payload) at a time from {@code in} and
     * hand it to {@code consumer}, so the whole response body is never held in memory at once. Peak
     * transient is a single part's payload instead of the entire body (50-150MB at 4K). The stream
     * is consumed but NOT closed (caller owns it).
     */
    public static void readStreaming(@Nonnull final InputStream in,
                                     @Nonnull final PartConsumer consumer)
            throws SabrProtocolException, IOException {
        readStreamingUntil(in, (type, payload) -> {
            consumer.accept(type, payload);
            return true;
        });
    }

    /**
     * Like {@link #readStreaming(InputStream, PartConsumer)}, but stops at a part boundary when
     * {@code consumer} returns false. The caller owns and closes the stream.
     */
    public static void readStreamingUntil(@Nonnull final InputStream in,
                                          @Nonnull final StoppablePartConsumer consumer)
            throws SabrProtocolException, IOException {
        readPayloadsUntil(in, (type, size, payload) -> consumer.accept(type,
                readExactly(payload, size)));
    }

    /**
     * Stream the UMP envelope while exposing each payload as a bounded stream. This lets callers
     * consume large MEDIA parts without allocating one byte[] for the whole part.
     */
    public static void readPayloadsUntil(@Nonnull final InputStream in,
                                         @Nonnull final StoppablePayloadConsumer consumer)
            throws SabrProtocolException, IOException {
        while (true) {
            throwIfInterrupted();
            final int first = in.read();
            if (first < 0) {
                return; // clean EOF at a part boundary -> done
            }
            final int type = readUmpInt(in, first);
            final int size = readUmpInt(in, readByteOrThrow(in));
            if (type < 0 || size < 0) {
                throw new SabrProtocolException("Invalid UMP part header");
            }
            final BoundedInputStream payload = new BoundedInputStream(in, size);
            final boolean keepGoing = consumer.accept(type, size, payload);
            payload.drain();
            if (!keepGoing) {
                return;
            }
        }
    }

    // UMP compact int, given the already-read first byte. Mirrors Cursor.readUmpInt.
    private static int readUmpInt(@Nonnull final InputStream in, final int first)
            throws SabrProtocolException, IOException {
        if (first < 0) {
            throw new EOFException("Unexpected EOF in UMP integer");
        }
        if (first < 128) {
            return first;
        }
        if (first < 192) {
            return (first & 0x3f) + 64 * readByteOrThrow(in);
        }
        if (first < 224) {
            return (first & 0x1f) + 32 * (readByteOrThrow(in) + 256 * readByteOrThrow(in));
        }
        if (first < 240) {
            return (first & 0x0f) + 16 * (readByteOrThrow(in)
                    + 256 * (readByteOrThrow(in) + 256 * readByteOrThrow(in)));
        }
        return readByteOrThrow(in) + 256 * (readByteOrThrow(in)
                + 256 * (readByteOrThrow(in) + 256 * readByteOrThrow(in)));
    }

    private static int readByteOrThrow(@Nonnull final InputStream in)
            throws SabrProtocolException, IOException {
        throwIfInterrupted();
        final int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected EOF in UMP integer");
        }
        return b;
    }

    @Nonnull
    private static byte[] readExactly(@Nonnull final InputStream in, final int length)
            throws SabrProtocolException, IOException {
        if (length < 0) {
            throw new SabrProtocolException("Invalid UMP part length");
        }
        throwIfInterrupted();
        final byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            throwIfInterrupted();
            final int read = in.read(result, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading UMP part data");
            }
            offset += read;
        }
        return result;
    }

    private static void throwIfInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while reading UMP stream");
        }
    }

    private static final class BoundedInputStream extends InputStream {
        @Nonnull
        private final InputStream source;
        private int remaining;

        private BoundedInputStream(@Nonnull final InputStream source, final int size) {
            this.source = source;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            throwIfInterrupted();
            if (remaining <= 0) {
                return -1;
            }
            final int value = source.read();
            if (value < 0) {
                throw new EOFException("Unexpected EOF while reading UMP part data");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(@Nonnull final byte[] buffer, final int offset, final int length)
                throws IOException {
            throwIfInterrupted();
            if (remaining <= 0) {
                return -1;
            }
            final int read = source.read(buffer, offset, Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading UMP part data");
            }
            remaining -= read;
            return read;
        }

        private void drain() throws IOException {
            final byte[] buffer = new byte[8192];
            while (remaining > 0) {
                read(buffer, 0, Math.min(buffer.length, remaining));
            }
        }
    }

    @Nonnull
    public static List<UmpPart> readAll(@Nonnull final byte[] data) throws SabrProtocolException {
        final Cursor cursor = new Cursor(data);
        final List<UmpPart> parts = new ArrayList<>();
        while (!cursor.isDone()) {
            final int type = cursor.readUmpInt();
            final int size = cursor.readUmpInt();
            if (type < 0 || size < 0) {
                throw new SabrProtocolException("Invalid UMP part header");
            }
            parts.add(new UmpPart(type, size, cursor.readBytes(size)));
        }
        return parts;
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        private Cursor(@Nonnull final byte[] data) {
            this.data = data;
        }

        boolean isDone() {
            return offset >= data.length;
        }

        int readUmpInt() throws SabrProtocolException {
            final int first = readUnsignedByte();
            if (first < 128) {
                return first;
            }
            if (first < 192) {
                return (first & 0x3f) + 64 * readUnsignedByte();
            }
            if (first < 224) {
                return (first & 0x1f) + 32 * (readUnsignedByte()
                        + 256 * readUnsignedByte());
            }
            if (first < 240) {
                return (first & 0x0f) + 16 * (readUnsignedByte()
                        + 256 * (readUnsignedByte() + 256 * readUnsignedByte()));
            }
            return readUnsignedByte()
                    + 256 * (readUnsignedByte()
                    + 256 * (readUnsignedByte() + 256 * readUnsignedByte()));
        }

        @Nonnull
        byte[] readBytes(final int length) throws SabrProtocolException {
            if (length < 0 || offset + length > data.length) {
                throw new SabrProtocolException("Unexpected EOF while reading UMP part data");
            }
            final byte[] result = new byte[length];
            System.arraycopy(data, offset, result, 0, length);
            offset += length;
            return result;
        }

        private int readUnsignedByte() throws SabrProtocolException {
            if (offset >= data.length) {
                throw new SabrProtocolException("Unexpected EOF in UMP integer");
            }
            return data[offset++] & 0xff;
        }
    }
}
