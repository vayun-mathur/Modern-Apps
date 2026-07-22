package com.vayunmathur.youpipe.util.sabr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrNextRequestPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrRecoverableException;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

final class SabrStreamPump {
    enum State {
        IDLE,
        REQUESTING,
        REPOSITIONING,
        THROTTLED,
        NETWORK_FAILED,
        TERMINAL,
        STOPPED
    }

    private static final String TAG = "SabrStreamPump";
    private static final long IDLE_POLL_MS = 100;     // server paced us / nothing new this round
    private static final long ERROR_RETRY_MS = 1000;  // transient network error
    private static final int MAX_CONSECUTIVE_IO_ERRORS = 5;
    // Must stay above the readahead cushion because Media3 stops reading while its buffer is full.
    private static final long IDLE_STOP_MS = 90_000;
    private static final long READAHEAD_CUSHION_MS = 10_000;
    private static final long STARTUP_READAHEAD_CUSHION_MS = 6_000;
    private static final long STARTUP_BURST_READAHEAD_CUSHION_MS = 25_000;
    // Startup bursts need to fill enough media for exact seeks, but YouTube SABR starts returning
    // policy-only responses when the reported server-side readahead gets too large. Cap only the
    // request-time player timestamp so local throttling and eviction still use the actual playhead.
    private static final long STARTUP_BURST_SERVER_AHEAD_MS = 16_000;
    private static final long STARTUP_BURST_MS = 25_000;
    private static final long SEEK_READAHEAD_CUSHION_MS = 5_000;
    private static final long SEEK_MODE_MS = 8_000;
    private static final long MIN_SERVER_READAHEAD_CUSHION_MS = 3_000;
    // Use the session's cache ceiling as the single source of truth. A lower pump threshold leaves a
    // byte range where the pump is throttled but the session cannot evict, forcing demand-time fetches.
    private static final long MAX_AHEAD_BYTES = YoutubeSabrSession.getMaxCacheBytes();
    // Keep a short rewind cushion in cache; deeper rewinds are refetched by repositioning the session.
    private static final long BACK_BUFFER_MS = 12_000;
    // Shrink the back-buffer when over budget so eviction can free enough data to keep fetching.
    private static final long MIN_BACK_BUFFER_MS = 2_000;
    private static final long BACK_BUFFER_BYTES = 4L * 1024 * 1024;

    private final YoutubeSabrSession session;
    private final SabrSessionStore.Holder holder;
    private final Localization localization;

    private volatile boolean started;
    private volatile boolean stopped;
    private volatile boolean clearCacheOnStop;
    private volatile State state = State.IDLE;
    private volatile IOException networkFailure;
    private volatile long lastReadMs;
    private volatile long lastRequestMs;
    private volatile SabrSegmentRequest pendingRefetch;
    private volatile long pendingRefetchPositionMs = -1;
    private volatile SabrSegmentRequest pendingForwardSeek;
    private volatile long pendingForwardSeekPositionMs = -1;
    private final Map<DemandKey, SegmentDemand> activeDemands = new ConcurrentHashMap<>();
    private final Map<DemandKey, IOException> demandFailures = new ConcurrentHashMap<>();
    private volatile YoutubeSabrFormat pendingInitialization;
    private volatile long seekModeUntilMs;
    private volatile long startedAtMs;
    private Thread thread;

    SabrStreamPump(@NonNull final YoutubeSabrSession session,
                   @NonNull final SabrSessionStore.Holder holder,
                   @NonNull final Localization localization) {
        this.session = session;
        this.holder = holder;
        this.localization = localization;
    }

    void ensureStarted() {
        lastReadMs = System.currentTimeMillis();
        if (state == State.TERMINAL || (started && !stopped)) {
            return;
        }
        synchronized (this) {
            if (state == State.TERMINAL || (started && !stopped)) {
                return;
            }
            stopped = false;
            started = true;
            startedAtMs = System.currentTimeMillis();
            state = State.IDLE;
            thread = new Thread(this::loop, "SabrStreamPump");
            thread.setDaemon(true);
            thread.start();
        }
    }

    void stop() {
        synchronized (this) {
            stopped = true;
            clearCacheOnStop = true;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    @Nullable
    SabrMediaSegment getCached(@NonNull final SabrSegmentRequest request) {
        ensureStarted();
        return session.getCachedSegment(request);
    }

    @Nullable
    synchronized IOException takeNetworkFailure() {
        final IOException failure = networkFailure;
        networkFailure = null;
        return failure;
    }

    @Nullable
    IOException takeDemandFailure(@NonNull final SabrSegmentRequest request,
                                  @NonNull final Object readerOwner,
                                  final long readerGeneration) {
        return demandFailures.remove(DemandKey.from(request, readerOwner, readerGeneration));
    }

    boolean canRecover() {
        return state == State.IDLE || state == State.THROTTLED;
    }

    String getStateName() {
        return state.name();
    }

    void requestRefetchFrom(@NonNull final SabrSegmentRequest request) {
        activateSeekMode();
        pendingRefetch = request;
        pendingRefetchPositionMs = -1;
        ensureStarted();
        wake();
    }

    void requestForwardSeekTo(@NonNull final SabrSegmentRequest request) {
        activateSeekMode();
        pendingForwardSeek = request;
        pendingForwardSeekPositionMs = -1;
        ensureStarted();
        wake();
    }

    void requestSegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration) {
        if (request.isInitializationSegment()) {
            requestInitialization(request.getFormat());
            return;
        }
        if (session.getCachedSegment(request) != null) {
            clearSegmentDemand(request, readerOwner, readerGeneration);
            return;
        }
        final DemandKey key = DemandKey.from(request, readerOwner, readerGeneration);
        if (demandFailures.containsKey(key)) {
            wake();
            return;
        }
        final long nowMs = System.currentTimeMillis();
        final SegmentDemand created = new SegmentDemand(
                request, readerOwner, readerGeneration, nowMs);
        final long remainingBackoffMs = session.getDemandBackoffRemainingMs();
        if (remainingBackoffMs > 0) {
            created.pausePolicyClockForBackoff(nowMs, remainingBackoffMs);
        }
        final boolean added = activeDemands.putIfAbsent(key, created) == null;
        ensureStarted();
        if (added) {
            wake();
        }
    }

    void clearSegmentDemand(@NonNull final SabrSegmentRequest request,
                            @NonNull final Object readerOwner,
                            final long readerGeneration) {
        final SegmentDemand removed = activeDemands.remove(
                DemandKey.from(request, readerOwner, readerGeneration));
        demandFailures.remove(DemandKey.from(request, readerOwner, readerGeneration));
        if (removed != null) {
            // A server backoff can park the pump for many seconds. Wake it so cancellation or a
            // superseded reader is observed immediately without permitting an early request.
            wake();
        }
    }

    void requestSeekTo(@NonNull final SabrSegmentRequest request, final boolean backward) {
        requestSeekTo(request, backward, -1);
    }

    void requestSeekTo(@NonNull final SabrSegmentRequest request, final boolean backward,
                       final long positionMs) {
        activateSeekMode();
        if (backward) {
            pendingForwardSeek = null;
            pendingForwardSeekPositionMs = -1;
            pendingRefetch = request;
            pendingRefetchPositionMs = positionMs;
        } else {
            pendingRefetch = null;
            pendingRefetchPositionMs = -1;
            pendingForwardSeek = request;
            pendingForwardSeekPositionMs = positionMs;
        }
        ensureStarted();
        wake();
    }

    void noteSeekWithinCache() {
        activateSeekMode();
        ensureStarted();
        wake();
    }

    void requestInitialization(@NonNull final YoutubeSabrFormat format) {
        pendingInitialization = format;
        ensureStarted();
        wake();
    }

    private void loop() {
        int consecutiveIoErrors = 0;
        state = State.IDLE;
        try {
            while (!stopped) {
                if (pendingRefetch == null && pendingForwardSeek == null
                        && activeDemands.isEmpty() && pendingInitialization == null
                        && (System.currentTimeMillis() - lastReadMs > IDLE_STOP_MS
                                || session.isComplete())) {
                    break;
                }
                try {
                    final long readerHeadMs = holder.getReaderHeadMs();
                    final long backBufferMs = session.getCachedBytes() > MAX_AHEAD_BYTES
                            ? MIN_BACK_BUFFER_MS : targetBackBufferMs();
                    session.setPlayHeadMs(Math.max(0, holder.getReaderTailMs() - backBufferMs));
                    session.evictPlayed();
                    final long edgeMs = session.getStreamState().getMinBufferedEndMs();
                    final long remainingBackoffMs = session.getDemandBackoffRemainingMs();
                    if (remainingBackoffMs > 0) {
                        state = State.IDLE;
                        awaitWake(remainingBackoffMs);
                        continue;
                    }
                    final YoutubeSabrFormat initialization = pendingInitialization;
                    if (initialization != null) {
                        pendingInitialization = null;
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_initialization itag="
                                + initialization.getItag());
                        prepareInitialRequestPosition();
                        session.prepareForInitialization(initialization);
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    final SabrSegmentRequest refetch = pendingRefetch;
                    if (refetch != null) {
                        final long refetchPositionMs = pendingRefetchPositionMs;
                        pendingRefetch = null;
                        pendingRefetchPositionMs = -1;
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_rewind itag="
                                + refetch.getFormat().getItag()
                                + " seq=" + refetch.getSequenceNumber());
                        if (refetchPositionMs >= 0) {
                            session.prepareForRewind(refetch, refetchPositionMs);
                        } else {
                            session.prepareForRewind(refetch);
                        }
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    final SabrSegmentRequest forwardSeek = pendingForwardSeek;
                    if (forwardSeek != null) {
                        final long forwardSeekPositionMs = pendingForwardSeekPositionMs;
                        pendingForwardSeek = null;
                        pendingForwardSeekPositionMs = -1;
                        if (isSeekTargetCached(forwardSeek, forwardSeekPositionMs)) {
                            session.addDiagnosticEvent("pump_forward_cached itag="
                                    + forwardSeek.getFormat().getItag()
                                    + " seq=" + forwardSeek.getSequenceNumber()
                                    + " positionMs=" + forwardSeekPositionMs);
                            state = State.IDLE;
                            continue;
                        }
                        state = State.REPOSITIONING;
                        session.addDiagnosticEvent("pump_forward itag="
                                + forwardSeek.getFormat().getItag()
                                + " init=" + forwardSeek.isInitializationSegment()
                                + " seq=" + forwardSeek.getSequenceNumber());
                        if (forwardSeekPositionMs >= 0) {
                            session.prepareForForwardJump(forwardSeek, forwardSeekPositionMs);
                        } else {
                            session.prepareForForwardJump(forwardSeek);
                        }
                        pumpOnceStreaming();
                        state = State.IDLE;
                        consecutiveIoErrors = 0;
                        continue;
                    }
                    final SegmentDemand demand = selectDemand(edgeMs);
                    if (demand != null) {
                        if (session.getCachedSegment(demand.request) != null) {
                            clearDemand(demand);
                        } else {
                            final long demandStartMs = session.getStreamState()
                                    .getSegmentStartMs(demand.request.getFormat(),
                                            demand.request.getSequenceNumber());
                            final SabrSessionPolicy.DemandRoute route =
                                    session.evaluateDemandRoute(demand.routeEvent(
                                            demandStartMs, edgeMs, System.currentTimeMillis()));
                            if (route == SabrSessionPolicy.DemandRoute.RECOVER_REWIND
                                    || route == SabrSessionPolicy.DemandRoute.RECOVER_FORWARD
                                    || route == SabrSessionPolicy.DemandRoute.RECOVER_MISSING) {
                                state = State.REPOSITIONING;
                                demand.recoveryCount++;
                                session.addDiagnosticEvent("pump_demand_reposition itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs
                                        + " omissions="
                                        + demand.responsesWithoutDemandedSegment
                                        + " recovery=" + demand.recoveryCount
                                        + " route=" + route);
                                if (route == SabrSessionPolicy.DemandRoute.RECOVER_REWIND) {
                                    session.prepareForRewind(demand.request);
                                } else if (route
                                        == SabrSessionPolicy.DemandRoute.RECOVER_FORWARD) {
                                    session.prepareForForwardJump(demand.request);
                                } else {
                                    session.prepareForMissingSegment(demand.request);
                                }
                                final YoutubeSabrSession.DemandResponseResult result =
                                        pumpOnceStreamingUntilCached(
                                        demand.request);
                                final boolean demandCompleted = finishDemandAttempt(demand, result);
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand);
                                }
                                continue;
                            } else if (route == SabrSessionPolicy.DemandRoute.REWIND) {
                                state = State.REPOSITIONING;
                                session.addDiagnosticEvent("pump_demand_rewind itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs);
                                session.prepareForRewind(demand.request);
                                final YoutubeSabrSession.DemandResponseResult result =
                                        pumpOnceStreamingUntilCached(
                                        demand.request);
                                final boolean demandCompleted = finishDemandAttempt(demand, result);
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand);
                                }
                                continue;
                            } else if (route == SabrSessionPolicy.DemandRoute.FORWARD) {
                                state = State.REPOSITIONING;
                                session.addDiagnosticEvent("pump_demand_forward itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs);
                                session.prepareForForwardJump(demand.request);
                                final YoutubeSabrSession.DemandResponseResult result =
                                        pumpOnceStreamingUntilCached(
                                        demand.request);
                                final boolean demandCompleted = finishDemandAttempt(demand, result);
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand);
                                }
                                continue;
                            } else if (route == SabrSessionPolicy.DemandRoute.STREAM) {
                                state = State.REQUESTING;
                                session.addDiagnosticEvent("pump_demand itag="
                                        + demand.request.getFormat().getItag()
                                        + " seq=" + demand.request.getSequenceNumber()
                                        + " startMs=" + demandStartMs
                                        + " edgeMs=" + edgeMs
                                        + " sinceMs=" + Math.max(0,
                                                System.currentTimeMillis()
                                                        - demand.createdAtMs));
                                final long playerTimeMs = holder.getPlayerTimeMs();
                                final long requestPlayerTimeMs = cappedServerAheadPlayerTimeMs(
                                        playerTimeMs, edgeMs);
                                session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs);
                                final YoutubeSabrSession.DemandResponseResult result =
                                        pumpOnceStreamingUntilCached(
                                        demand.request);
                                final boolean demandCompleted = finishDemandAttempt(demand, result);
                                state = State.IDLE;
                                consecutiveIoErrors = 0;
                                if (!demandCompleted) {
                                    awaitDemandRetry(demand);
                                }
                                continue;
                            }
                            throw new IllegalStateException("Unhandled SABR demand route " + route);
                        }
                    }
                    final long readaheadCushionMs = targetReadaheadCushionMs();
                    final long playerTimeMs = holder.getPlayerTimeMs();
                    final long aheadMs = Math.max(0, edgeMs - playerTimeMs);
                    final boolean heartbeatDue = isHeartbeatDue();
                    final boolean throttled = (aheadMs >= readaheadCushionMs && !heartbeatDue)
                            || session.getCachedBytes() > MAX_AHEAD_BYTES;
                    if (throttled) {
                        if (state != State.THROTTLED) {
                            session.addDiagnosticEvent("pump_throttled cushionMs="
                                    + readaheadCushionMs
                                    + " unstartedReader=" + holder.hasUnstartedActiveReader()
                                    + " edgeMs=" + edgeMs
                                    + " playerTimeMs=" + playerTimeMs
                                    + " aheadMs=" + aheadMs
                                    + " readerHeadMs=" + readerHeadMs
                                    + " readerTailMs=" + holder.getReaderTailMs()
                                    + " cachedBytes=" + session.getCachedBytes()
                                    + " requestNumber=" + session.getRequestNumber());
                        }
                        state = State.THROTTLED;
                        awaitWake(IDLE_POLL_MS);
                        continue;
                    }
                    final boolean startupWait = holder.hasUnstartedActiveReader();
                    final long startupBackoffMs = startupWait
                            ? session.getDemandBackoffRemainingMs() : 0;
                    if (startupBackoffMs > 0) {
                        SabrBackoffCoordinator.getInstance().begin(
                                holder.getApplicationContext(), holder,
                                android.os.SystemClock.elapsedRealtime() + startupBackoffMs);
                        awaitWake(Math.max(startupBackoffMs, IDLE_POLL_MS));
                        continue;
                    }
                    state = State.REQUESTING;
                    final long requestPlayerTimeMs = startupRequestPlayerTimeMs(playerTimeMs,
                            edgeMs);
                    session.getStreamState().setPlayerTimeMs(requestPlayerTimeMs);
                    final int segmentCount = holder.hasUnstartedActiveReader()
                            ? pumpOnceStreamingForStartup() : pumpOnceStreaming();
                    state = State.IDLE;
                    consecutiveIoErrors = 0;
                    if (segmentCount == 0) {
                        awaitWake(IDLE_POLL_MS);
                    }
                } catch (final IOException e) {
                    if (stopped || holder.isInvalidated()) {
                        session.addDiagnosticEvent("pump_canceled invalidated="
                                + holder.isInvalidated() + " message=" + e.getMessage());
                        break;
                    }
                    if (isInterruptedRead(e)) {
                        networkFailure = e;
                        state = State.NETWORK_FAILED;
                        break;
                    }
                    consecutiveIoErrors++;
                    if (consecutiveIoErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                        Log.w(TAG, "SABR pump network failure "
                                + holder.videoId, e);
                        networkFailure = e;
                        state = State.NETWORK_FAILED;
                        break;
                    }
                    sleepQuietly(ERROR_RETRY_MS);
                } catch (final SabrRecoverableException e) {
                    Log.i(TAG, "SABR media failure: " + e.getMessage());
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR media failure", e));
                    break;
                } catch (final ExtractionException e) {
                    if (Thread.currentThread().isInterrupted() || holder.isInvalidated()) {
                        Log.i(TAG, "SABR pump canceled video=" + holder.videoId
                                + " invalidated=" + holder.isInvalidated()
                                + " message=" + e.getMessage());
                        holder.session.addDiagnosticEvent("pump_canceled invalidated="
                                + holder.isInvalidated() + " message=" + e.getMessage());
                        break;
                    }
                    Log.i(TAG, "SABR pump fatal: " + e.getMessage());
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR logic failure", e));
                    break;
                } catch (final Exception e) {
                    // OkHttp's Kotlin internals can propagate a checked InterruptedException via
                    // a sneaky throw while an in-flight connect is canceled. Java does not include
                    // it in the declared downloader signature, so handle it at the pump boundary.
                    if (stopped || holder.isInvalidated()
                            || Thread.currentThread().isInterrupted()) {
                        Log.i(TAG, "SABR pump canceled video=" + holder.videoId
                                + " invalidated=" + holder.isInvalidated()
                                + " type=" + e.getClass().getSimpleName());
                        break;
                    }
                    Log.e(TAG, "SABR pump unexpected failure " + holder.videoId, e);
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException(
                            "SABR unexpected pump failure", e));
                    break;
                } catch (final OutOfMemoryError e) {
                    Log.e(TAG, "SABR pump OOM; evicting session " + holder.videoId, e);
                    state = State.TERMINAL;
                    holder.failTerminal(new SabrLogicException("SABR memory failure", e));
                    break;
                }
            }
        } finally {
            if (clearCacheOnStop) {
                session.clearCache();
            }
            synchronized (this) {
                stopped = true;
                if (state != State.TERMINAL && state != State.NETWORK_FAILED) {
                    state = State.STOPPED;
                }
            }
        }
    }

    private int pumpOnceStreaming() throws IOException, ExtractionException {
        try {
            final int segmentCount = session.pumpOnceStreaming(localization);
            holder.recordDiagnosticsThrottled("pump segments=" + segmentCount);
            return segmentCount;
        } finally {
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private int pumpOnceStreamingForStartup() throws IOException, ExtractionException {
        try {
            final int segmentCount = session.pumpOnceStreamingForStartup(localization);
            final long remainingBackoffMs = session.getDemandBackoffRemainingMs();
            if (remainingBackoffMs > 0) {
                SabrBackoffCoordinator.getInstance().begin(
                        holder.getApplicationContext(), holder,
                        android.os.SystemClock.elapsedRealtime() + remainingBackoffMs);
            }
            holder.recordDiagnosticsThrottled("pump_startup segments=" + segmentCount);
            return segmentCount;
        } finally {
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private YoutubeSabrSession.DemandResponseResult pumpOnceStreamingUntilCached(
            @NonNull final SabrSegmentRequest request)
            throws IOException, ExtractionException {
        final YoutubeSabrSession.DemandResponseResult result;
        try {
            result = session.pumpOnceStreamingForDemand(localization, request);
            final long remainingBackoffMs = session.getDemandBackoffRemainingMs();
            if (remainingBackoffMs > 0) {
                pauseDemandPolicyClocksForBackoff(remainingBackoffMs);
            }
            holder.recordDiagnosticsThrottled("pump_until_cached itag="
                    + request.getFormat().getItag()
                    + " seq=" + request.getSequenceNumber()
                    + " segments=" + result.getSegmentCount()
                    + " targetTrackSegments=" + result.getTargetTrackSegmentCount());
        } finally {
            lastRequestMs = System.currentTimeMillis();
        }
        return result;
    }

    private void awaitDemandRetry(@NonNull final SegmentDemand demand) {
        final long remainingBackoffMs = session.getDemandBackoffRemainingMs();
        if (remainingBackoffMs > 0L) {
            SabrBackoffCoordinator.getInstance().begin(holder.getApplicationContext(), holder,
                    android.os.SystemClock.elapsedRealtime() + remainingBackoffMs);
        }
        awaitWake(Math.max(remainingBackoffMs,
                demand.retryDelayMs > 0 ? demand.retryDelayMs : IDLE_POLL_MS));
    }

    private void pauseDemandPolicyClocksForBackoff(final long remainingBackoffMs) {
        final long nowMs = System.currentTimeMillis();
        for (final SegmentDemand activeDemand : activeDemands.values()) {
            activeDemand.pausePolicyClockForBackoff(nowMs, remainingBackoffMs);
        }
    }

    private long targetReadaheadCushionMs() {
        if (isSeekMode()) {
            return SEEK_READAHEAD_CUSHION_MS;
        }
        if (isStartupBurst()) {
            return STARTUP_BURST_READAHEAD_CUSHION_MS;
        }
        if (holder.hasUnstartedActiveReader()) {
            return STARTUP_READAHEAD_CUSHION_MS;
        }
        final SabrNextRequestPolicy policy = session.getStreamState().getNextRequestPolicy();
        if (policy == null) {
            return READAHEAD_CUSHION_MS;
        }
        final int serverTargetMs = Math.max(policy.getTargetAudioReadaheadMs(),
                policy.getTargetVideoReadaheadMs());
        if (serverTargetMs <= 0) {
            return READAHEAD_CUSHION_MS;
        }
        return Math.max(MIN_SERVER_READAHEAD_CUSHION_MS,
                Math.min(READAHEAD_CUSHION_MS, serverTargetMs));
    }

    private long startupRequestPlayerTimeMs(final long playerTimeMs, final long edgeMs) {
        if (!isStartupBurst()) {
            return playerTimeMs;
        }
        return cappedServerAheadPlayerTimeMs(playerTimeMs, edgeMs);
    }

    private long cappedServerAheadPlayerTimeMs(final long playerTimeMs, final long edgeMs) {
        return Math.max(playerTimeMs, edgeMs - STARTUP_BURST_SERVER_AHEAD_MS);
    }

    private boolean isStartupBurst() {
        return startedAtMs > 0 && System.currentTimeMillis() - startedAtMs < STARTUP_BURST_MS;
    }

    private boolean isHeartbeatDue() {
        final SabrNextRequestPolicy policy = session.getStreamState().getNextRequestPolicy();
        final int maximumMs = policy == null ? -1 : policy.getMaxTimeSinceLastRequestMs();
        return maximumMs > 0 && lastRequestMs > 0
                && System.currentTimeMillis() - lastRequestMs >= maximumMs;
    }

    private long targetBackBufferMs() {
        if (isSeekMode()) {
            return MIN_BACK_BUFFER_MS;
        }
        final long bitsPerSec = (long) holder.videoFormat.getBitrate()
                + Math.max(0, holder.audioFormat.getBitrate());
        if (bitsPerSec <= 0) {
            return BACK_BUFFER_MS;
        }
        final long bytesPerMs = Math.max(1, bitsPerSec / 8 / 1000);
        return Math.max(MIN_BACK_BUFFER_MS,
                Math.min(BACK_BUFFER_MS, BACK_BUFFER_BYTES / bytesPerMs));
    }

    private void activateSeekMode() {
        seekModeUntilMs = System.currentTimeMillis() + SEEK_MODE_MS;
    }

    private void prepareInitialRequestPosition() {
        if (session.getRequestNumber() != 0) {
            return;
        }
        final long playerTimeMs = holder.getPlayerTimeMs();
        if (playerTimeMs <= 1_000) {
            return;
        }
        session.addDiagnosticEvent("pump_initialization_target itag="
                + holder.videoFormat.getItag()
                + " playerTimeMs=" + playerTimeMs);
        session.getStreamState().setPlayerTimeMs(playerTimeMs);
        session.getStreamState().setSelectVideoFormatBeforeAudio(true);
    }

    private boolean isSeekMode() {
        return System.currentTimeMillis() < seekModeUntilMs;
    }

    private boolean isSeekTargetCached(@NonNull final SabrSegmentRequest request,
                                       final long positionMs) {
        if (session.getCachedSegment(request) == null) {
            return false;
        }
        if (request.isInitializationSegment()) {
            return true;
        }
        final YoutubeSabrFormat targetFormat = request.getFormat();
        final YoutubeSabrFormat companionFormat;
        if (targetFormat.getItag() == holder.videoFormat.getItag()) {
            companionFormat = holder.audioFormat;
        } else if (targetFormat.getItag() == holder.audioFormat.getItag()) {
            companionFormat = holder.videoFormat;
        } else {
            return true;
        }
        final long companionTimeMs = positionMs >= 0 ? positionMs
                : session.getStreamState().getSegmentStartMs(targetFormat,
                        request.getSequenceNumber());
        final int companionSequence = session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(companionFormat, companionTimeMs);
        return session.getCachedSegment(SabrSegmentRequest.media(companionFormat,
                companionSequence)) != null;
    }

    @Nullable
    private SegmentDemand selectDemand(final long edgeMs) {
        SegmentDemand selected = null;
        long selectedStartMs = Long.MAX_VALUE;
        for (final SegmentDemand demand : activeDemands.values()) {
            if (!holder.isReaderGenerationActive(demand.readerOwner, demand.readerGeneration)
                    || session.getCachedSegment(demand.request) != null) {
                clearDemand(demand);
                continue;
            }
            final long startMs = session.getStreamState().getSegmentStartMs(
                    demand.request.getFormat(), demand.request.getSequenceNumber());
            if (selected == null || startMs < selectedStartMs
                    || (startMs == selectedStartMs
                    && demand.createdAtMs < selected.createdAtMs)) {
                selected = demand;
                selectedStartMs = startMs;
            }
        }
        return selected;
    }

    private void clearDemand(@NonNull final SegmentDemand demand) {
        activeDemands.remove(DemandKey.from(demand.request, demand.readerOwner,
                demand.readerGeneration));
        if (activeDemands.isEmpty()) {
            SabrBackoffCoordinator.getInstance().clear(holder.getApplicationContext(), holder);
        }
    }

    private boolean finishDemandAttempt(
            @NonNull final SegmentDemand demand,
            @NonNull final YoutubeSabrSession.DemandResponseResult result)
            throws ExtractionException {
        if (!isDemandActive(demand)) {
            return true;
        }
        if (!result.wasRequestPerformed()) {
            demand.retryDelayMs = 0;
            return false;
        }
        if (session.getCachedSegment(demand.request) != null) {
            clearDemand(demand);
            return true;
        }
        // A control-only response is pacing/protocol state, not evidence that the server omitted
        // a demanded media segment. The ordinary response policy has already handled it; keeping
        // the demand counters unchanged also preserves the server backoff.
        if (result.getSegmentCount() == 0 && result.getReturnedSegments().isEmpty()) {
            demand.retryDelayMs = 0;
            session.addDiagnosticEvent("pump_demand_no_media itag="
                    + demand.request.getFormat().getItag()
                    + " seq=" + demand.request.getSequenceNumber()
                    + " backoffMs=" + session.getDemandBackoffRemainingMs());
            return false;
        }
        final long nowMs = System.currentTimeMillis();
        demand.responsesWithoutDemandedSegment++;
        final long targetStartMs = session.getStreamState().getSegmentStartMs(
                demand.request.getFormat(), demand.request.getSequenceNumber());
        final long edgeMs = session.getStreamState().getMinBufferedEndMs();
        final SabrSessionPolicy.DemandResponseDecision decision =
                session.evaluateDemandResponse(new SabrSessionPolicy.DemandResponseEvent(
                        demand.request.getFormat().getItag(),
                        demand.request.getSequenceNumber(), targetStartMs, edgeMs,
                        demand.policyState(nowMs), result.getSegmentCount(),
                        result.getTargetTrackSegmentCount(), result.getReturnedSegments(),
                        result.areReturnedSegmentsTruncated()));
        demand.retryDelayMs = decision.getRetryDelayMs();
        final long elapsedMs = demand.getPolicyElapsedMs(nowMs);
        session.addDiagnosticEvent("pump_demand_omission itag="
                + demand.request.getFormat().getItag()
                + " seq=" + demand.request.getSequenceNumber()
                + " omissions=" + demand.responsesWithoutDemandedSegment
                + " targetTrackSegments=" + result.getTargetTrackSegmentCount()
                + " segments=" + result.getSegmentCount()
                + " returned=" + summarizeReturnedSegments(result)
                + " elapsedMs=" + elapsedMs
                + " outcome=" + decision.getOutcome()
                + " retryDelayMs=" + decision.getRetryDelayMs());
        if (decision.getOutcome()
                == SabrSessionPolicy.DemandOutcome.FAIL_REPEATED_TARGET_OMISSION) {
            failDemand(demand, new IOException(
                    "SABR response repeatedly omitted demanded segment itag="
                            + demand.request.getFormat().getItag()
                            + ", seq=" + demand.request.getSequenceNumber()
                            + ", responses=" + demand.responsesWithoutDemandedSegment
                            + ", elapsedMs=" + elapsedMs));
            return true;
        }
        if (decision.getOutcome() == SabrSessionPolicy.DemandOutcome.FAIL_NO_TARGET_MEDIA) {
            failDemand(demand, new IOException("SABR demand timed out without target-track media"
                    + " itag=" + demand.request.getFormat().getItag()
                    + ", seq=" + demand.request.getSequenceNumber()
                    + ", elapsedMs=" + elapsedMs));
            return true;
        }
        if (decision.getOutcome() != SabrSessionPolicy.DemandOutcome.CONTINUE) {
            throw new IllegalStateException("Unhandled SABR demand outcome "
                    + decision.getOutcome());
        }
        return false;
    }

    @NonNull
    private static String summarizeReturnedSegments(
            @NonNull final YoutubeSabrSession.DemandResponseResult result) {
        final StringBuilder summary = new StringBuilder("[");
        for (final SabrSessionPolicy.DemandReturnedSegment segment
                : result.getReturnedSegments()) {
            if (summary.length() > 1) {
                summary.append(',');
            }
            summary.append(segment.getItag()).append(':').append(segment.getSequenceNumber());
        }
        if (result.areReturnedSegmentsTruncated()) {
            summary.append(",...");
        }
        return summary.append(']').toString();
    }

    private boolean isDemandActive(@NonNull final SegmentDemand demand) {
        final DemandKey key = DemandKey.from(demand.request, demand.readerOwner,
                demand.readerGeneration);
        return activeDemands.get(key) == demand
                && holder.isReaderGenerationActive(demand.readerOwner, demand.readerGeneration);
    }

    private void failDemand(@NonNull final SegmentDemand demand,
                            @NonNull final IOException failure) {
        final DemandKey key = DemandKey.from(demand.request, demand.readerOwner,
                demand.readerGeneration);
        if (activeDemands.remove(key, demand)) {
            demandFailures.put(key, failure);
            session.addDiagnosticEvent("pump_demand_failed itag="
                    + demand.request.getFormat().getItag()
                    + " seq=" + demand.request.getSequenceNumber()
                    + " message=" + failure.getMessage());
        }
    }

    private static final class SegmentDemand {
        @NonNull
        private final SabrSegmentRequest request;
        @NonNull
        private final Object readerOwner;
        private final long readerGeneration;
        private final long createdAtMs;
        private int responsesWithoutDemandedSegment;
        private int recoveryCount;
        private int retryDelayMs;
        private long policyCreatedAtMs;
        private long policyBackoffUntilMs;

        private SegmentDemand(@NonNull final SabrSegmentRequest request,
                              @NonNull final Object readerOwner,
                              final long readerGeneration,
                              final long sinceMs) {
            this.request = request;
            this.readerOwner = readerOwner;
            this.readerGeneration = readerGeneration;
            this.createdAtMs = sinceMs;
            this.policyCreatedAtMs = sinceMs;
        }

        @NonNull
        private SabrSessionPolicy.DemandState policyState(final long nowMs) {
            return new SabrSessionPolicy.DemandState(policyCreatedAtMs, nowMs,
                    responsesWithoutDemandedSegment, recoveryCount);
        }

        private void pausePolicyClockForBackoff(final long nowMs, final long remainingBackoffMs) {
            final long backoffUntilMs = nowMs + remainingBackoffMs;
            final long unaccountedBackoffMs = backoffUntilMs
                    - Math.max(nowMs, policyBackoffUntilMs);
            if (unaccountedBackoffMs > 0) {
                policyCreatedAtMs += unaccountedBackoffMs;
                policyBackoffUntilMs = backoffUntilMs;
            }
        }

        private long getPolicyElapsedMs(final long nowMs) {
            return Math.max(0, nowMs - policyCreatedAtMs);
        }

        @NonNull
        private SabrSessionPolicy.DemandRouteEvent routeEvent(final long targetStartMs,
                                                               final long bufferedEdgeMs,
                                                               final long nowMs) {
            return new SabrSessionPolicy.DemandRouteEvent(request.getFormat().getItag(),
                    request.getSequenceNumber(), targetStartMs, bufferedEdgeMs,
                    policyState(nowMs));
        }
    }

    private static final class DemandKey {
        private final int itag;
        private final int sequenceNumber;
        private final Object readerOwner;
        private final long readerGeneration;
        private final int ownerHash;

        private DemandKey(final int itag,
                          final int sequenceNumber,
                          @NonNull final Object readerOwner,
                          final long readerGeneration) {
            this.itag = itag;
            this.sequenceNumber = sequenceNumber;
            this.readerOwner = readerOwner;
            this.readerGeneration = readerGeneration;
            this.ownerHash = System.identityHashCode(readerOwner);
        }

        private static DemandKey from(@NonNull final SabrSegmentRequest request,
                                      @NonNull final Object readerOwner,
                                      final long readerGeneration) {
            return new DemandKey(request.getFormat().getItag(), request.getSequenceNumber(),
                    readerOwner, readerGeneration);
        }

        @Override
        public boolean equals(@Nullable final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DemandKey)) {
                return false;
            }
            final DemandKey key = (DemandKey) other;
            return itag == key.itag
                    && sequenceNumber == key.sequenceNumber
                    && readerOwner == key.readerOwner
                    && readerGeneration == key.readerGeneration;
        }

        @Override
        public int hashCode() {
            int result = itag;
            result = 31 * result + sequenceNumber;
            result = 31 * result + ownerHash;
            result = 31 * result + (int) (readerGeneration ^ (readerGeneration >>> 32));
            return result;
        }
    }

    private static void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isInterruptedRead(@NonNull final IOException error) {
        if (!(error instanceof InterruptedIOException)) {
            return false;
        }
        final String message = error.getMessage();
        return Thread.currentThread().isInterrupted()
                || message != null && message.startsWith("Interrupted");
    }

    private void wake() {
        final Thread pumpThread = thread;
        if (pumpThread != null) {
            LockSupport.unpark(pumpThread);
        }
    }

    private void awaitWake(final long timeoutMs) {
        LockSupport.parkNanos(timeoutMs * 1_000_000L);
    }
}
