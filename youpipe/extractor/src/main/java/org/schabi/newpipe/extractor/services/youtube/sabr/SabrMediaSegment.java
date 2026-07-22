package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

public final class SabrMediaSegment {
    private static final int COPY_BUFFER_SIZE = 8192;

    @Nonnull
    private final SabrMediaHeader header;
    @Nullable
    private final byte[] data;
    @Nullable
    private final File file;
    private final int length;
    @Nullable
    private final ProgressiveFileState progressiveState;

    SabrMediaSegment(@Nonnull final SabrMediaHeader header, @Nonnull final byte[] data) {
        this.header = header;
        // No defensive copy: the collector hands over a freshly built array it does not retain.
        // Media segments reach several MB (4K), so cloning here doubled peak memory and caused OOM
        // under rapid switching. The array is treated as immutable from here on.
        this.data = data;
        this.file = null;
        this.length = data.length;
        this.progressiveState = null;
    }

    SabrMediaSegment(@Nonnull final SabrMediaHeader header,
                     @Nonnull final File file,
                     final int length) {
        this.header = header;
        this.data = null;
        this.file = file;
        this.length = length;
        this.progressiveState = null;
    }

    private SabrMediaSegment(@Nonnull final SabrMediaHeader header,
                             @Nonnull final File file,
                             final int length,
                             @Nonnull final ProgressiveFileState progressiveState) {
        this.header = header;
        this.data = null;
        this.file = file;
        this.length = length;
        this.progressiveState = progressiveState;
    }

    @Nonnull
    static SabrMediaSegment progressive(@Nonnull final SabrMediaHeader header,
                                        @Nonnull final File file,
                                        final int length) {
        return new SabrMediaSegment(header, file, length, new ProgressiveFileState(length));
    }

    @Nonnull
    public SabrMediaHeader getHeader() {
        return header;
    }

    /**
     * Read-only: callers must not mutate the returned array. Disk-backed segments are loaded only
     * for legacy callers; playback should use {@link #openStream()} to avoid pulling large media
     * segments back onto the Java heap.
     */
    @Nonnull
    public byte[] getData() {
        if (data != null) {
            return data;
        }
        try (InputStream input = openStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(length)) {
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read disk-backed SABR media segment", e);
        }
    }

    @Nonnull
    public InputStream openStream() throws IOException {
        if (data != null) {
            return new ByteArrayInputStream(data);
        }
        if (file == null) {
            throw new IOException("SABR media segment has no backing data");
        }
        if (progressiveState != null) {
            progressiveState.throwIfFailed();
            try {
                return new ProgressiveFileInputStream(file, progressiveState);
            } catch (final IOException e) {
                progressiveState.throwIfFailed();
                throw e;
            }
        }
        return new FileInputStream(file);
    }

    public boolean isDiskBacked() {
        return file != null;
    }

    public boolean isComplete() {
        return progressiveState == null || progressiveState.isComplete();
    }

    public boolean hasFailed() {
        return progressiveState != null && progressiveState.hasFailed();
    }

    void onBytesWritten(final int count) {
        if (progressiveState != null) {
            progressiveState.onBytesWritten(count);
        }
    }

    void completeProgressive() {
        if (progressiveState != null) {
            progressiveState.complete();
        }
    }

    void failProgressive(@Nonnull final IOException failure) {
        if (progressiveState != null) {
            progressiveState.fail(failure);
        }
    }

    public void delete() {
        failProgressive(new IOException("SABR media segment was discarded"));
        if (file != null && !file.delete() && file.exists()) {
            file.deleteOnExit();
        }
    }

    public int getLength() {
        return length;
    }

    @Nullable
    File getFile() {
        return file;
    }

    private static final class ProgressiveFileState {
        private final int expectedLength;
        private int bytesWritten;
        private boolean complete;
        @Nullable
        private IOException failure;

        private ProgressiveFileState(final int expectedLength) {
            this.expectedLength = expectedLength;
        }

        private synchronized void onBytesWritten(final int count) {
            if (count <= 0 || complete || failure != null) {
                return;
            }
            bytesWritten += count;
            notifyAll();
        }

        private synchronized void complete() {
            if (failure == null) {
                complete = true;
            }
            notifyAll();
        }

        private synchronized void fail(@Nonnull final IOException exception) {
            if (!complete && failure == null) {
                failure = exception;
            }
            notifyAll();
        }

        private synchronized boolean isComplete() {
            return complete;
        }

        private synchronized boolean hasFailed() {
            return failure != null;
        }

        private synchronized int awaitAvailable(
                final long position,
                @Nonnull final ProgressiveFileInputStream reader) throws IOException {
            int readable = readableBytes(position);
            while (readable <= 0 && !complete && failure == null && !reader.closed) {
                try {
                    wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    final InterruptedIOException interrupted = new InterruptedIOException(
                            "Interrupted waiting for SABR media bytes");
                    interrupted.initCause(e);
                    throw interrupted;
                }
                readable = readableBytes(position);
            }
            if (reader.closed) {
                throw new InterruptedIOException("SABR media stream was closed");
            }
            if (failure != null) {
                throw failure;
            }
            return complete ? (int) Math.max(0, bytesWritten - position) : readable;
        }

        private synchronized int available(final long position) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return complete ? (int) Math.max(0, bytesWritten - position)
                    : readableBytes(position);
        }

        private int readableBytes(final long position) {
            final int available = (int) Math.max(0, bytesWritten - position);
            if (!complete && bytesWritten >= expectedLength
                    && position + available >= expectedLength) {
                // Keep one byte unavailable until MEDIA_END validates the segment. Media3 knows
                // the declared DataSource length and may never issue a separate EOF read.
                return Math.max(0, available - 1);
            }
            return available;
        }

        private synchronized void signalReaders() {
            notifyAll();
        }

        private synchronized void throwIfFailed() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ProgressiveFileInputStream extends InputStream {
        @Nonnull
        private final RandomAccessFile input;
        @Nonnull
        private final ProgressiveFileState state;
        private long position;
        private volatile boolean closed;

        private ProgressiveFileInputStream(@Nonnull final File file,
                                           @Nonnull final ProgressiveFileState state)
                throws IOException {
            this.input = new RandomAccessFile(file, "r");
            this.state = state;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(@Nonnull final byte[] bytes, final int offset, final int count)
                throws IOException {
            if (closed) {
                throw new IOException("SABR media stream is closed");
            }
            if (count == 0) {
                return 0;
            }
            while (true) {
                final int available = state.awaitAvailable(position, this);
                if (available <= 0) {
                    return -1;
                }
                input.seek(position);
                final int read = input.read(bytes, offset, Math.min(count, available));
                if (read > 0) {
                    position += read;
                    return read;
                }
            }
        }

        @Override
        public long skip(final long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            final int available = state.awaitAvailable(position, this);
            if (available <= 0) {
                return 0;
            }
            final long skipped = Math.min(count, available);
            position += skipped;
            input.seek(position);
            return skipped;
        }

        @Override
        public int available() throws IOException {
            if (closed) {
                return 0;
            }
            return state.available(position);
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                state.signalReaders();
                input.close();
            }
        }
    }
}
