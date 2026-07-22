package com.vayunmathur.youpipe.util.sabr;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;

public final class SabrSegmentDataSource implements DataSource {
    private static final String TAG = "SabrSegmentDataSource";

    private static final long WAIT_MS = 250;
    private static final long RECOVERY_AFTER_NO_PROGRESS_MS = 10_000;
    private static final long RECOVERY_RETRY_MS = 10_000;
    private static final long RECOVERY_FAILURE_MS = 30_000;
    private static final long FORWARD_SEEK_AHEAD_MS = 30_000;

    @Nullable
    private SabrSessionStore.Holder holder;
    @Nullable
    private final SabrSessionHandle sessionHandle;
    private final Object readerOwner;
    @Nullable
    private final YoutubeSabrFormat fixedFormat;
    private final Localization localization;
    private final boolean prependInit;

    @Nullable
    private Uri uri;
    @Nullable
    private byte[] data;
    @Nullable
    private InputStream dataStream;
    @Nullable
    private SabrMediaSegment progressiveSegment;
    private long progressiveReaderGeneration = -1;
    private int progressiveDataEndPosition = -1;
    private long bytesRemaining;
    private int pos;
    private boolean opened;
    private volatile boolean canceled;

    public SabrSegmentDataSource(final SabrSessionStore.Holder holder,
                                 final Object readerOwner,
                                 final YoutubeSabrFormat format,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.sessionHandle = null;
        this.readerOwner = readerOwner;
        this.fixedFormat = format;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    public SabrSegmentDataSource(final SabrSessionStore.Holder holder,
                                 final Object readerOwner,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.sessionHandle = null;
        this.readerOwner = readerOwner;
        this.fixedFormat = null;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    SabrSegmentDataSource(final SabrSessionHandle sessionHandle,
                          final Object readerOwner,
                          final Localization localization,
                          final boolean prependInit) {
        this.holder = null;
        this.sessionHandle = sessionHandle;
        this.readerOwner = readerOwner;
        this.fixedFormat = null;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
    }

    @Override
    public long open(final DataSpec dataSpec) throws IOException {
        if (holder == null) {
            if (sessionHandle == null) {
                throw new IOException("SABR data source has no session handle");
            }
            holder = sessionHandle.acquireHolder();
        }
        this.uri = dataSpec.uri;
        this.canceled = false;
        closeDataStream();
        this.data = null;
        this.progressiveSegment = null;
        this.progressiveReaderGeneration = -1;
        this.progressiveDataEndPosition = -1;
        this.pos = (int) Math.max(0, dataSpec.position);
        SabrSegmentRequest request = requestFromUri(dataSpec.uri);
        final YoutubeSabrFormat format = request.getFormat();
        final long availableRemaining;
        final int openedBytes;
        Log.d(TAG, "open video=" + holder.videoId
                + " itag=" + format.getItag()
                + " uri=" + dataSpec.uri
                + " prependInit=" + prependInit);
        if (request.isInitializationSegment()) {
            this.data = getInitializationData(format);
            availableRemaining = Math.max(0, data.length - pos);
            openedBytes = data.length;
        } else if (prependInit) {
            final byte[] init = getInitializationData(format);
            final SabrMediaSegment segment = awaitSegment(request);
            final byte[] media = segment == null ? new byte[0] : segment.getData();
            final byte[] both = new byte[init.length + media.length];
            System.arraycopy(init, 0, both, 0, init.length);
            System.arraycopy(media, 0, both, init.length, media.length);
            this.data = both;
            if (progressiveSegment != null) {
                progressiveDataEndPosition = both.length;
            }
            availableRemaining = Math.max(0, data.length - pos);
            openedBytes = data.length;
        } else {
            SabrMediaSegment segment = awaitSegment(request);
            if (segment != null) {
                try {
                    this.dataStream = segment.openStream();
                } catch (final FileNotFoundException e) {
                    Log.w(TAG, "Spool file vanished before open; refetching video="
                            + holder.videoId + " itag=" + format.getItag()
                            + " seq=" + request.getSequenceNumber());
                    holder.session.discardCachedSegment(request);
                    progressiveSegment = null;
                    segment = awaitSegment(request);
                    if (segment != null) {
                        this.dataStream = segment.openStream();
                    }
                }
            }
            if (segment == null) {
                this.data = new byte[0];
                availableRemaining = 0;
                openedBytes = 0;
            } else {
                if (progressiveSegment != null) {
                    progressiveDataEndPosition = segment.getLength();
                }
                final long skipped = skipFully(dataStream, Math.max(0, dataSpec.position));
                this.pos = (int) Math.min(Integer.MAX_VALUE, skipped);
                availableRemaining = Math.max(0, segment.getLength() - skipped);
                openedBytes = segment.getLength();
            }
        }
        this.opened = true;
        this.bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? availableRemaining : Math.min(dataSpec.length, availableRemaining);
        Log.d(TAG, "opened video=" + holder.videoId
                + " itag=" + format.getItag()
                + " bytes=" + openedBytes
                + " remaining=" + availableRemaining);
        return bytesRemaining;
    }

    private byte[] getInitializationData(final YoutubeSabrFormat format) throws IOException {
        final int itag = format.getItag();
        final byte[] cached = holder.getInitializationData(itag);
        if (cached != null) {
            return cached;
        }
        final SabrMediaSegment segment =
                holder.session.getCachedSegment(SabrSegmentRequest.initialization(format));
        if (segment != null) {
            final byte[] data = segment.getData();
            holder.setInitializationData(itag, data);
            return data;
        }
        final SabrMediaSegment loadedSegment =
                awaitSegment(SabrSegmentRequest.initialization(format));
        return loadedSegment == null ? new byte[0] : loadedSegment.getData();
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        if (data != null) {
            if (pos >= data.length) {
                return C.RESULT_END_OF_INPUT;
            }
            final int toCopy = (int) Math.min(Math.min(length, data.length - pos), bytesRemaining);
            System.arraycopy(data, pos, target, offset, toCopy);
            pos += toCopy;
            bytesRemaining -= toCopy;
            maybeAdvanceProgressiveReader();
            return toCopy;
        }
        if (dataStream == null) {
            return C.RESULT_END_OF_INPUT;
        }
        final int toRead = (int) Math.min(length, bytesRemaining);
        final int read = dataStream.read(target, offset, toRead);
        if (read < 0) {
            bytesRemaining = 0;
            return C.RESULT_END_OF_INPUT;
        }
        pos = (int) Math.min(Integer.MAX_VALUE, (long) pos + read);
        bytesRemaining -= read;
        maybeAdvanceProgressiveReader();
        return read;
    }

    private void maybeAdvanceProgressiveReader() {
        final SabrMediaSegment segment = progressiveSegment;
        if (segment == null || progressiveDataEndPosition < 0
                || pos < progressiveDataEndPosition || !segment.isComplete() || holder == null) {
            return;
        }
        final YoutubeSabrFormat format = segment.getHeader().getItag()
                == holder.videoFormat.getItag() ? holder.videoFormat : holder.audioFormat;
        holder.setReaderPositionMs(readerOwner, progressiveReaderGeneration, format.getItag(),
                segment.getHeader().getStartMs() + segment.getHeader().getDurationMs());
        progressiveSegment = null;
        progressiveReaderGeneration = -1;
        progressiveDataEndPosition = -1;
    }

    private SabrSegmentRequest requestFromUri(final Uri u) throws IOException {
        final YoutubeSabrFormat format = formatFromUri(u);
        final String seg = u.getLastPathSegment();
        if (seg == null) {
            throw new SabrLogicException("Bad SABR segment uri: " + u);
        }
        if ("init".equals(seg)) {
            return SabrSegmentRequest.initialization(format);
        }
        try {
            return SabrSegmentRequest.media(format, Integer.parseInt(seg));
        } catch (final NumberFormatException e) {
            throw new SabrLogicException("Bad SABR segment uri: " + u, e);
        }
    }

    private YoutubeSabrFormat formatFromUri(final Uri u) throws IOException {
        if (fixedFormat != null) {
            return fixedFormat;
        }
        final String host = u.getHost();
        if (host == null) {
            throw new SabrLogicException("Bad SABR segment uri without itag: " + u);
        }
        final int itag;
        try {
            itag = Integer.parseInt(host);
        } catch (final NumberFormatException e) {
            throw new SabrLogicException("Bad SABR segment itag in uri: " + u, e);
        }
        if (holder.videoFormat.getItag() == itag) {
            return holder.videoFormat;
        }
        if (holder.audioFormat.getItag() == itag) {
            return holder.audioFormat;
        }
        throw new SabrLogicException("Unknown SABR segment itag=" + itag + " uri=" + u);
    }

    @Nullable
    private SabrMediaSegment awaitSegment(final SabrSegmentRequest request) throws IOException {
        final YoutubeSabrFormat format = request.getFormat();
        holder.throwIfTerminal();
        if (holder.isInvalidated()) {
            throw invalidatedException(request.getFormat());
        }
        final SabrStreamPump pump = holder.getPump(localization);
        long readerGeneration = holder.getReaderGeneration(readerOwner);
        final long waitStart = System.currentTimeMillis();
        long noProgressSinceMs = waitStart;
        long mediaProgressVersion = holder.session.getMediaProgressVersion();
        long recoveryAtMs = -1;
        long lastRecoveryAtMs = -1;
        boolean loggedWait = false;
        try {
            while (true) {
            if (canceled) {
                throw new IOException("SABR segment read canceled");
            }
            if (!request.isInitializationSegment()) {
                final long currentReaderGeneration = holder.getReaderGeneration(readerOwner);
                if (readerGeneration < 0 && currentReaderGeneration >= 0) {
                    readerGeneration = currentReaderGeneration;
                    noProgressSinceMs = System.currentTimeMillis();
                    mediaProgressVersion = holder.session.getMediaProgressVersion();
                } else if (readerGeneration >= 0
                        && currentReaderGeneration != readerGeneration) {
                    throw new InterruptedIOException("SABR reader demand superseded for itag="
                            + format.getItag() + ", seq=" + request.getSequenceNumber());
                }
            }
            holder.throwIfTerminal();
            if (holder.isInvalidated()) {
                throw invalidatedException(request.getFormat());
            }
            if (holder.session.isBeyondEnd(request)) {
                Log.d(TAG, "beyond end video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                holder.session.addDiagnosticEvent("beyond_end itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                return null;
            }
            final IOException demandFailure = !request.isInitializationSegment()
                    && readerGeneration >= 0
                    ? pump.takeDemandFailure(request, readerOwner, readerGeneration) : null;
            if (demandFailure != null) {
                throw demandFailure;
            }
            final IOException networkFailure = pump.takeNetworkFailure();
            if (networkFailure != null) {
                throw networkFailure;
            }
            if (request.isInitializationSegment()) {
                pump.requestInitialization(format);
            } else {
                pump.ensureStarted();
            }
            final SabrMediaSegment segment;
            if (request.isInitializationSegment()) {
                segment = pump.getCached(request);
            } else {
                try {
                    segment = holder.session.awaitReadableSegment(request, WAIT_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for SABR segment", e);
                }
            }
            if (segment != null) {
                Log.d(TAG, "cache hit video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " bytes=" + segment.getLength()
                        + " disk=" + segment.isDiskBacked());
                if (!segment.getHeader().isInitSegment()) {
                    if (segment.isComplete()) {
                        holder.setReaderPositionMs(readerOwner, readerGeneration, format.getItag(),
                                segment.getHeader().getStartMs()
                                        + segment.getHeader().getDurationMs());
                    } else {
                        progressiveSegment = segment;
                        progressiveReaderGeneration = readerGeneration;
                    }
                }
                return segment;
            }
            if (holder.session.isBeyondEnd(request)) {
                Log.d(TAG, "beyond end video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                holder.session.addDiagnosticEvent("beyond_end itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                return null;
            }
            if (!request.isInitializationSegment() && readerGeneration >= 0) {
                pump.requestSegmentDemand(request, readerOwner, readerGeneration);
            }
            if (!loggedWait && System.currentTimeMillis() - waitStart > 1000) {
                loggedWait = true;
                holder.session.addDiagnosticEvent("wait itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " pump=" + pump.getStateName()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs()
                        + " readerHeadMs=" + holder.getReaderHeadMs()
                        + " readerTailMs=" + holder.getReaderTailMs()
                        + " cachedBytes=" + holder.session.getCachedBytes());
                Log.d(TAG, "waiting video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs()
                        + " readerHeadMs=" + holder.getReaderHeadMs());
            }
            final long now = System.currentTimeMillis();
            final long currentMediaProgressVersion = holder.session.getMediaProgressVersion();
            if (currentMediaProgressVersion != mediaProgressVersion) {
                mediaProgressVersion = currentMediaProgressVersion;
                noProgressSinceMs = now;
                recoveryAtMs = -1;
                lastRecoveryAtMs = -1;
            }
            if (holder.session.getDemandBackoffRemainingMs() > 0) {
                // Server-directed pacing is not a playback stall. Keep polling so cancellation and
                // reader replacement remain responsive, but do not let the local recovery watchdog
                // reposition the session and attempt another request before the server deadline.
                noProgressSinceMs = now;
                recoveryAtMs = -1;
                lastRecoveryAtMs = -1;
            }
            if (now - noProgressSinceMs > RECOVERY_AFTER_NO_PROGRESS_MS
                    && (lastRecoveryAtMs < 0
                            || now - lastRecoveryAtMs > RECOVERY_RETRY_MS)
                    && pump.canRecover()
                    && (request.isInitializationSegment() || readerGeneration >= 0)) {
                String recovery;
                if (request.isInitializationSegment()) {
                    recovery = "init";
                    pump.requestInitialization(format);
                } else {
                    final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
                    final long segStartMs = holder.session.getStreamState()
                            .getSegmentStartMs(format, request.getSequenceNumber());
                    if (segStartMs < edgeMs) {
                        recovery = "rewind";
                        holder.setReaderPositionMs(readerOwner, readerGeneration, format.getItag(),
                                segStartMs);
                        pump.requestRefetchFrom(request);
                    } else if (segStartMs > edgeMs + FORWARD_SEEK_AHEAD_MS) {
                        recovery = "forward";
                        holder.setReaderPositionMs(readerOwner, readerGeneration, format.getItag(),
                                segStartMs);
                        pump.requestForwardSeekTo(request);
                    } else {
                        recovery = "near_edge_refetch";
                        holder.setReaderPositionMs(readerOwner, readerGeneration,
                                format.getItag(), segStartMs);
                        pump.requestRefetchFrom(request);
                    }
                }
                holder.session.addDiagnosticEvent("recovery type=" + recovery
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " pump=" + pump.getStateName()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs());
                if (recoveryAtMs < 0) {
                    recoveryAtMs = now;
                }
                lastRecoveryAtMs = now;
            }
            if (recoveryAtMs >= 0 && now - recoveryAtMs > RECOVERY_FAILURE_MS
                    && pump.canRecover()) {
                final SabrLogicException failure = new SabrLogicException(
                        "SABR made no progress after recovery for itag=" + format.getItag()
                                + ", init=" + request.isInitializationSegment()
                                + ", seq=" + request.getSequenceNumber()
                                + ", waitMs=" + (now - waitStart)
                                + ", pump=" + pump.getStateName()
                                + ", edgeMs="
                                + holder.session.getStreamState().getMinBufferedEndMs()
                                + ", readerHeadMs=" + holder.getReaderHeadMs()
                                + ", readerTailMs=" + holder.getReaderTailMs()
                                + ", cachedBytes=" + holder.session.getCachedBytes()
                                + ", trace=" + holder.session.getDiagnosticTrace());
                holder.failTerminal(failure);
                throw failure;
            }
            if (request.isInitializationSegment()) {
                try {
                    Thread.sleep(WAIT_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted awaiting SABR initialization", e);
                }
            }
        }
        } finally {
            if (!request.isInitializationSegment()) {
                pump.clearSegmentDemand(request, readerOwner, readerGeneration);
            }
        }
    }

    private SabrLogicException invalidatedException(final YoutubeSabrFormat format) {
        return new SabrLogicException("SABR session invalidated for video=" + holder.videoId
                + ", itag=" + format.getItag() + ", " + holder.getInvalidationDetails());
    }

    private static long skipFully(final InputStream input, final long requested) throws IOException {
        long remaining = requested;
        final byte[] buffer = new byte[8192];
        while (remaining > 0) {
            final long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            remaining -= read;
        }
        return requested - remaining;
    }

    private void closeDataStream() throws IOException {
        if (dataStream != null) {
            dataStream.close();
            dataStream = null;
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        canceled = true;
        data = null;
        try {
            closeDataStream();
        } catch (final IOException e) {
            Log.w(TAG, "Could not close SABR segment stream", e);
        }
        opened = false;
    }
}
