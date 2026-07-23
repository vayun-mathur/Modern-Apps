package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vayunmathur.youpipe.YouPipeApplication;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SabrSessionStore {

    private static final String TAG = "SabrSessionStore";

    private static final Map<SessionKey, Holder> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> PREFERRED_AUDIO = new ConcurrentHashMap<>();
    // Extractor-derived SABR metadata handed off from the ViewModel to the PlaybackService by
    // video id. Populated when the loaded streams are SABR; consumed by createSourceSpec.
    private static final Map<String, YoutubeSabrInfo> EXTRACTOR_INFO = new ConcurrentHashMap<>();
    // Active MediaPeriods own leases. MediaSources outside the playback window are lightweight and
    // therefore do not prevent old sessions from being trimmed.
    // Mutated only under the class lock.
    private static final int MAX_SESSIONS = 3;
    private static final int MAX_BOOTSTRAP_CACHE_ENTRIES = 32;
    private static final java.util.Deque<SessionKey> ORDER = new java.util.ArrayDeque<>();
    private static final ExecutorService BOOTSTRAP_EXECUTOR = Executors.newFixedThreadPool(2,
            runnable -> {
                final Thread thread = new Thread(runnable, "SabrNativeBootstrap");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService INITIALIZATION_EXECUTOR = Executors.newFixedThreadPool(2,
            runnable -> {
                final Thread thread = new Thread(runnable, "SabrAdaptiveInitialization");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService TOKEN_EXECUTOR = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "SabrTokenPrewarm");
                thread.setDaemon(true);
                return thread;
            });
    private static final Map<String, Future<BootstrapResult>> BOOTSTRAP_IN_FLIGHT =
            new ConcurrentHashMap<>();
    private static final Map<String, BootstrapBackoffState> BOOTSTRAP_BACKOFFS =
            new ConcurrentHashMap<>();
    private static final Map<String, BootstrapResult> BOOTSTRAP_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, BootstrapResult>(
                    MAX_BOOTSTRAP_CACHE_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, BootstrapResult> eldest) {
                    if (size() > MAX_BOOTSTRAP_CACHE_ENTRIES) {
                        eldest.getValue().discardPreparedSession();
                        return true;
                    }
                    return false;
                }
            });
    private static final Map<String, Future<byte[]>> TOKEN_IN_FLIGHT =
            new ConcurrentHashMap<>();
    private static volatile LocalDomPoTokenProvider sharedProvider;

    private SabrSessionStore() {
    }

    /** Hands the extractor-derived SABR metadata for a video id to the playback path. */
    public static void putExtractorInfo(@NonNull final String videoId,
                                        @NonNull final YoutubeSabrInfo info) {
        EXTRACTOR_INFO.put(videoId, info);
    }

    @Nullable
    public static YoutubeSabrInfo getExtractorInfo(@NonNull final String videoId) {
        return EXTRACTOR_INFO.get(videoId);
    }

    /**
     * PipePipe-style detail-page prewarm: as soon as a SABR stream is selected, warm BOTH the
     * content PoToken and the SABR bootstrap for the selected video itag, using the same format
     * selection and cache key that {@link #createSourceSpec} will use at play time. This makes the
     * first play effectively instant (the bootstrap is served from the cache / in-flight future
     * rather than being started when the user presses play).
     *
     * <p>Mirrors PipePipe's {@code SabrSessionStore.prewarm(...)} which calls both
     * {@code startTokenWarmup} and {@code startBootstrap}. Warming only the token (without the
     * bootstrap) is NOT enough and previously regressed playback. Non-blocking: the work runs on
     * background executors and is deduplicated per video id / bootstrap key.</p>
     *
     * @param selectedVideoItag the itag of the SABR video stream the user is about to play; must
     *                          match what {@link #createSourceSpec} is later called with so the
     *                          bootstrap cache key lines up
     */
    public static void prewarm(@NonNull final Context context,
                               @NonNull final String videoId,
                               final int selectedVideoItag) {
        final YoutubeSabrInfo info = EXTRACTOR_INFO.get(videoId);
        if (info == null || !isUsableExtractorInfo(info, videoId)) {
            return;
        }
        final YoutubeSabrFormat audioFormat = pickAudioFormat(info, PREFERRED_AUDIO.get(videoId));
        final YoutubeSabrFormat videoFormat = pickVideoFormat(info, selectedVideoItag);
        if (audioFormat == null || videoFormat == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Localization localization = new Localization("en", "US");
        startTokenWarmup(appContext, info, audioFormat, videoFormat);
        startBootstrap(appContext, info, audioFormat, videoFormat, localization);
    }

    private static final class SessionKey {
        @NonNull private final String videoId;
        private final long sourceId;
        private final int videoItag;
        private final int audioItag;
        @NonNull private final String audioTrackId;
        @NonNull private final YoutubeSabrClientProfile profile;

        SessionKey(final long sourceId,
                   @NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat) {
            this.videoId = videoId;
            this.sourceId = sourceId;
            this.videoItag = videoFormat.getItag();
            this.audioItag = audioFormat.getItag();
            this.audioTrackId = Objects.toString(audioFormat.getAudioTrackId(), "");
            this.profile = info.getProfile();
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SessionKey)) {
                return false;
            }
            final SessionKey that = (SessionKey) other;
            return sourceId == that.sourceId
                    && videoItag == that.videoItag
                    && audioItag == that.audioItag
                    && videoId.equals(that.videoId)
                    && audioTrackId.equals(that.audioTrackId)
                    && profile == that.profile;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceId, videoId, videoItag, audioItag, audioTrackId, profile);
        }
    }

    private static final class BootstrapResult {
        @NonNull private final byte[] audioInitialization;
        @NonNull private final byte[] videoInitialization;
        @NonNull private final AtomicReference<YoutubeSabrSession> preparedSession;

        BootstrapResult(@NonNull final byte[] audioInitialization,
                        @NonNull final byte[] videoInitialization,
                        @Nullable final YoutubeSabrSession preparedSession) {
            this.audioInitialization = audioInitialization.clone();
            this.videoInitialization = videoInitialization.clone();
            this.preparedSession = new AtomicReference<>(preparedSession);
        }

        @Nullable
        YoutubeSabrSession takePreparedSession() {
            return preparedSession.getAndSet(null);
        }

        void discardPreparedSession() {
            final YoutubeSabrSession session = preparedSession.getAndSet(null);
            if (session != null) {
                session.clearCache();
            }
        }
    }

    private static final class BootstrapBackoffState
            implements YoutubeSabrSession.BackoffListener {
        @NonNull private final Context appContext;
        @NonNull private final String videoId;
        private long deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE;
        private int waiters;

        BootstrapBackoffState(@NonNull final Context context,
                              @NonNull final String videoId) {
            this.appContext = context.getApplicationContext();
            this.videoId = videoId;
        }

        @Override
        public synchronized void onBackoffStarted(final int durationMs) {
            deadlineElapsedMs = SystemClock.elapsedRealtime() + durationMs;
            Log.i(TAG, "bootstrap_backoff_start video=" + videoId
                    + " durationMs=" + durationMs + " waiters=" + waiters);
            if (waiters > 0) {
                SabrBackoffCoordinator.getInstance().beginPlaybackWait(
                        appContext, this, deadlineElapsedMs);
            }
        }

        @Override
        public synchronized void onBackoffFinished() {
            Log.i(TAG, "bootstrap_backoff_finish video=" + videoId
                    + " waiters=" + waiters);
            deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE;
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
        }

        synchronized void beginWaiting() {
            waiters++;
            if (deadlineElapsedMs > SystemClock.elapsedRealtime()) {
                SabrBackoffCoordinator.getInstance().beginPlaybackWait(
                        appContext, this, deadlineElapsedMs);
            }
        }

        synchronized void endWaiting() {
            waiters = Math.max(0, waiters - 1);
            if (waiters == 0) {
                SabrBackoffCoordinator.getInstance().clear(appContext, this);
            }
        }

        synchronized void cancel() {
            waiters = 0;
            deadlineElapsedMs = SabrBackoffCoordinator.NO_DEADLINE;
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
        }
    }

    public static final class Lease implements AutoCloseable {
        @NonNull private final SessionKey key;
        @NonNull private final Holder holder;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(@NonNull final SessionKey key, @NonNull final Holder holder) {
            this.key = key;
            this.holder = holder;
        }

        @NonNull
        Holder getHolder() {
            return holder;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseLease(key, holder);
            }
        }
    }

    @NonNull
    private static LocalDomPoTokenProvider provider(@NonNull final Context context) {
        LocalDomPoTokenProvider p = sharedProvider;
        if (p == null) {
            synchronized (SabrSessionStore.class) {
                p = sharedProvider;
                if (p == null) {
                    p = LocalDomPoTokenProvider.shared(context.getApplicationContext());
                    sharedProvider = p;
                }
            }
        }
        return p;
    }

    public static final class Holder {
        @NonNull private final SessionKey key;
        @NonNull private final Context appContext;
        @NonNull public final String videoId;
        @NonNull public final YoutubeSabrInfo info;
        @NonNull public final YoutubeSabrSession session;
        @NonNull public final YoutubeSabrFormat audioFormat;
        @NonNull public final YoutubeSabrFormat videoFormat;

        // Playback position is only a hint. Pump and eviction use reader positions.
        private volatile long playerTimeMs;
        private final Map<Integer, Long> readerPositions = new ConcurrentHashMap<>();
        private final Map<Object, Integer> activeTrackModes = new IdentityHashMap<>();
        private final Map<Integer, byte[]> initializationData = new ConcurrentHashMap<>();
        private final Map<Integer, byte[]> bootstrapInitializationData = new ConcurrentHashMap<>();
        // Tracks currently selected by ExoPlayer. Background/audio-only playback disables the video
        // renderer, so requiring a video reader position there pins the SABR cache at the beginning.
        private final Set<Integer> activeReaderItags =
                Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
        private final AtomicInteger leaseReferences = new AtomicInteger();
        private Object readerOwner;
        private long readerGeneration;
        private volatile SabrStreamPump pump;
        private volatile boolean invalidated;
        private volatile String stopReason;
        private volatile SabrLogicException terminalFailure;
        private long lastDiagnosticsAtMs;
        private long lastDiagnosticsPeakCachedBytes;

        Holder(@NonNull final Context appContext,
               @NonNull final String videoId,
               @NonNull final YoutubeSabrInfo info,
               @NonNull final YoutubeSabrSession session,
               @NonNull final YoutubeSabrFormat audioFormat,
               @NonNull final YoutubeSabrFormat videoFormat) {
            this.key = new SessionKey(0, videoId, info, audioFormat, videoFormat);
            this.appContext = appContext.getApplicationContext();
            this.videoId = videoId;
            this.info = info;
            this.session = session;
            this.audioFormat = audioFormat;
            this.videoFormat = videoFormat;
            attachBackoffListener();
        }

        Holder(@NonNull final Context appContext,
               @NonNull final SabrSourceSpec spec,
               @NonNull final YoutubeSabrSession session) {
            this.key = new SessionKey(spec.getSourceId(), spec.getVideoId(), spec.getInfo(),
                    spec.getAudioFormat(), spec.getVideoFormat());
            this.appContext = appContext.getApplicationContext();
            this.videoId = spec.getVideoId();
            this.info = spec.getInfo();
            this.session = session;
            this.audioFormat = spec.getAudioFormat();
            this.videoFormat = spec.getVideoFormat();
            retainBootstrapInitialization(spec, audioFormat);
            retainBootstrapInitialization(spec, videoFormat);
            attachBackoffListener();
        }

        private void attachBackoffListener() {
            session.setBackoffListener(new YoutubeSabrSession.BackoffListener() {
                @Override
                public void onBackoffStarted(final int durationMs) {
                    Log.i(TAG, "backoff_start video=" + videoId
                            + " durationMs=" + durationMs);
                    SabrBackoffCoordinator.getInstance().begin(appContext, Holder.this,
                            SystemClock.elapsedRealtime() + durationMs);
                }

                @Override
                public void onBackoffFinished() {
                    Log.i(TAG, "backoff_finish video=" + videoId);
                    SabrBackoffCoordinator.getInstance().clear(appContext, Holder.this);
                }
            });
        }

        public long getPlayerTimeMs() {
            return playerTimeMs;
        }

        @NonNull
        Context getApplicationContext() {
            return appContext;
        }

        void setPlayerTimeMs(final long playerTimeMs) {
            this.playerTimeMs = playerTimeMs;
        }

        /** A data source reports how far it has read (last served segment end, ms). */
        public synchronized void setReaderPositionMs(@NonNull final Object owner,
                                                     final long generation,
                                                     final int itag,
                                                     final long ms) {
            if (readerOwner == owner && readerGeneration == generation) {
                readerPositions.put(itag, ms);
            }
        }

        void setActiveTracks(@NonNull final Object owner,
                             final boolean videoActive,
                             final boolean audioActive) {
            final boolean trim;
            synchronized (this) {
                final int mode = (videoActive ? 1 : 0) | (audioActive ? 2 : 0);
                if (mode == 0) {
                    activeTrackModes.remove(owner);
                    if (readerOwner == owner) {
                        readerOwner = activeTrackModes.isEmpty() ? null
                                : activeTrackModes.keySet().iterator().next();
                        readerGeneration++;
                        readerPositions.clear();
                    }
                } else {
                    activeTrackModes.put(owner, mode);
                    if (readerOwner != owner) {
                        readerOwner = owner;
                        readerGeneration++;
                        readerPositions.clear();
                    }
                }
                applyActiveTracks();
                trim = activeTrackModes.isEmpty();
            }
            if (trim) {
                trimSessions(null);
            }
        }

        void releaseTracks(@NonNull final Object owner) {
            synchronized (this) {
                activeTrackModes.remove(owner);
                if (readerOwner == owner) {
                    readerOwner = activeTrackModes.isEmpty() ? null
                            : activeTrackModes.keySet().iterator().next();
                    readerGeneration++;
                    readerPositions.clear();
                }
                applyActiveTracks();
            }
            trimSessions(null);
        }

        synchronized void advanceReaderGeneration(@NonNull final Object owner) {
            if (readerOwner == owner) {
                readerGeneration++;
                readerPositions.clear();
            }
        }

        synchronized long getReaderGeneration(@NonNull final Object owner) {
            return readerOwner == owner ? readerGeneration : -1;
        }

        synchronized boolean isReaderGenerationActive(@NonNull final Object owner,
                                                      final long generation) {
            return readerOwner == owner && readerGeneration == generation;
        }

        private synchronized void anchorReaderPositionMs(final long positionMs) {
            if (readerOwner == null || activeReaderItags.isEmpty()) {
                return;
            }
            for (final int itag : activeReaderItags) {
                readerPositions.put(itag, positionMs);
            }
        }

        void requestSeek(final long positionMs, @NonNull final Localization localization) {
            final long previousPlayerTimeMs = playerTimeMs;
            final boolean backward = positionMs < previousPlayerTimeMs;
            setPlayerTimeMs(positionMs);
            recordDiagnostics("seek positionMs=" + positionMs + " backward=" + backward);
            anchorReaderPositionMs(positionMs);
            session.getStreamState().setSelectVideoFormatBeforeAudio(positionMs > 1_000);
            if (positionMs <= 1_000 && previousPlayerTimeMs <= 1_000) {
                return;
            }
            // Media3 may seek within its sample queue; still reposition the SABR session when the
            // target audio/video segments are not cached.
            final YoutubeSabrFormat targetFormat = videoFormat;
            final int sequence = session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(targetFormat, positionMs);
            final SabrSegmentRequest request = SabrSegmentRequest.media(targetFormat, sequence);
            final int audioSequence = session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(audioFormat, positionMs);
            final SabrSegmentRequest audioRequest = SabrSegmentRequest.media(
                    audioFormat, audioSequence);
            if (session.getCachedSegment(request) == null
                    || session.getCachedSegment(audioRequest) == null) {
                getPump(localization).requestSeekTo(request, backward, positionMs);
            } else {
                getPump(localization).noteSeekWithinCache();
            }
        }

        private synchronized boolean hasActiveTracks() {
            return !activeTrackModes.isEmpty();
        }

        byte[] getInitializationData(final int itag) {
            final byte[] cached = initializationData.get(itag);
            if (cached != null) {
                return cached;
            }
            final byte[] bootstrap = bootstrapInitializationData.get(itag);
            if (bootstrap != null) {
                initializationData.put(itag, bootstrap);
                session.addDiagnosticEvent("bootstrap_init_restore itag=" + itag);
            }
            return bootstrap;
        }

        void setInitializationData(final int itag, @NonNull final byte[] data) {
            initializationData.put(itag, data);
        }

        private void retainBootstrapInitialization(@NonNull final SabrSourceSpec spec,
                                                   @NonNull final YoutubeSabrFormat format) {
            final byte[] data = spec.getInitializationData(format.getItag());
            if (data != null) {
                bootstrapInitializationData.put(format.getItag(), data);
            }
        }

        private void retainLease() {
            leaseReferences.incrementAndGet();
        }

        private boolean hasLeaseReferences() {
            return leaseReferences.get() > 0;
        }

        private void applyActiveTracks() {
            boolean videoActive = false;
            boolean audioActive = false;
            for (final int mode : activeTrackModes.values()) {
                videoActive |= (mode & 1) != 0;
                audioActive |= (mode & 2) != 0;
            }
            setTrackActive(videoFormat.getItag(), videoActive);
            setTrackActive(audioFormat.getItag(), audioActive);
            if (videoActive || audioActive) {
                session.getStreamState().setActiveTrackTypes(videoActive, audioActive);
            }
        }

        private void setTrackActive(final int itag, final boolean active) {
            if (active) {
                activeReaderItags.add(itag);
                return;
            }
            activeReaderItags.remove(itag);
            readerPositions.remove(itag);
        }

        public long getReaderHeadMs() {
            long head = 0;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position != null) {
                    head = Math.max(head, position);
                }
            }
            return head;
        }

        /** Zero until every selected track has read something, otherwise eviction can drop unread data. */
        public long getReaderTailMs() {
            if (activeReaderItags.isEmpty()) {
                return 0;
            }
            long tail = Long.MAX_VALUE;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position == null) {
                    return 0;
                }
                tail = Math.min(tail, position);
            }
            return tail == Long.MAX_VALUE ? 0 : tail;
        }

        public boolean hasUnstartedActiveReader() {
            if (activeReaderItags.isEmpty()) {
                return false;
            }
            for (final int itag : activeReaderItags) {
                if (!readerPositions.containsKey(itag)) {
                    return true;
                }
            }
            return false;
        }

        synchronized SabrStreamPump getPump(@NonNull final Localization localization) {
            if (pump == null) {
                pump = new SabrStreamPump(session, this, localization);
            }
            return pump;
        }

        boolean isInvalidated() {
            return invalidated;
        }

        String getInvalidationDetails() {
            return "reason=" + stopReason
                    + ", leases=" + leaseReferences.get()
                    + ", trace=" + session.getDiagnosticTrace();
        }

        void failTerminal(@NonNull final SabrLogicException failure) {
            terminalFailure = failure;
            recordDiagnostics("terminal_failure message=" + failure.getMessage());
            evict(key, this, "terminal_failure message=" + failure.getMessage(), false);
        }

        void throwIfTerminal() throws SabrLogicException {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
        }

        void stop(@NonNull final String reason) {
            SabrBackoffCoordinator.getInstance().clear(appContext, this);
            session.setBackoffListener(null);
            Log.w(TAG, "stop video=" + videoId + " reason=" + reason
                    + " leases=" + leaseReferences.get() + " activeTracks=" + hasActiveTracks()
                    + " pump=" + (pump == null ? "none" : pump.getStateName()));
            recordDiagnostics("stop reason=" + reason);
            stopReason = reason;
            session.addDiagnosticEvent("session_stop reason=" + reason
                    + " leases=" + leaseReferences.get() + " activeTracks=" + hasActiveTracks());
            invalidated = true;
            synchronized (this) {
                activeTrackModes.clear();
                readerOwner = null;
                readerGeneration++;
                readerPositions.clear();
                applyActiveTracks();
            }
            final SabrStreamPump streamPump = pump;
            pump = null;
            if (streamPump != null) {
                streamPump.stop();
            } else {
                session.clearCache();
            }
        }

        boolean isBeyondEnd(@NonNull final SabrSegmentRequest request) {
            return session.isBeyondEnd(request);
        }

        void recordDiagnostics(@NonNull final String event) {
            SabrPlaybackDiagnostics.record(appContext, this, event);
            lastDiagnosticsAtMs = System.currentTimeMillis();
            lastDiagnosticsPeakCachedBytes = session.getPeakCachedBytes();
        }

        void recordDiagnosticsThrottled(@NonNull final String event) {
            final long now = System.currentTimeMillis();
            final long peakCachedBytes = session.getPeakCachedBytes();
            if (now - lastDiagnosticsAtMs >= 5_000
                    || peakCachedBytes != lastDiagnosticsPeakCachedBytes) {
                recordDiagnostics(event);
            }
        }
    }

    public static void updatePlayerTime(@NonNull final String videoId, final long playerTimeMs) {
        if (playerTimeMs < 0) {
            return;
        }
        for (final Map.Entry<SessionKey, Holder> entry : SESSIONS.entrySet()) {
            if (entry.getKey().videoId.equals(videoId) && entry.getValue().hasLeaseReferences()) {
                entry.getValue().setPlayerTimeMs(playerTimeMs);
                entry.getValue().recordDiagnosticsThrottled("progress");
            }
        }
    }

    public static void updatePlaybackRate(@NonNull final String videoId, final float playbackRate) {
        for (final Map.Entry<SessionKey, Holder> entry : SESSIONS.entrySet()) {
            if (entry.getKey().videoId.equals(videoId) && entry.getValue().hasLeaseReferences()) {
                entry.getValue().session.getStreamState().setPlaybackRate(playbackRate);
            }
        }
    }

    public static void setPreferredAudioTrack(@NonNull final String videoId,
                                              @Nullable final String audioTrackId) {
        if (audioTrackId == null) {
            PREFERRED_AUDIO.remove(videoId);
        } else {
            PREFERRED_AUDIO.put(videoId, audioTrackId);
        }
    }

    @NonNull
    public static SabrSourceSpec createSourceSpec(@NonNull final String videoId,
                                                  final int preferredVideoItag,
                                                  @Nullable final YoutubeSabrInfo extractorInfo)
            throws IOException, ExtractionException {
        return createSourceSpec(videoId, preferredVideoItag, 0, null, extractorInfo);
    }

    @NonNull
    public static SabrSourceSpec createSourceSpec(@NonNull final String videoId,
                                                  final int preferredVideoItag,
                                                  final int preferredAudioItag,
                                                  @Nullable final String preferredAudioTrackIdOverride,
                                                  @Nullable final YoutubeSabrInfo extractorInfo)
            throws IOException, ExtractionException {
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_started");
        final Context appContext = YouPipeApplication.getAppContext();
        final String preferredAudioTrackId = preferredAudioTrackIdOverride != null
                ? preferredAudioTrackIdOverride
                : PREFERRED_AUDIO.get(videoId);
        final Localization localization = new Localization("en", "US");
        final ContentCountry contentCountry = new ContentCountry("US");
        final YoutubeSabrInfo info = isUsableExtractorInfo(extractorInfo, videoId)
                ? extractorInfo
                : YoutubeSabrProbeFetch(videoId, localization, contentCountry);
        final YoutubeSabrFormat audioFormat = pickAudioFormat(info, preferredAudioTrackId, preferredAudioItag);
        final YoutubeSabrFormat videoFormat = pickVideoFormat(info, preferredVideoItag);
        if (audioFormat == null || videoFormat == null) {
            throw new IOException("SABR: could not select audio/video formats for " + videoId);
        }
        // A detail-page prewarm may already have completed the canonical SABR bootstrap. Never
        // publish a DASH manifest until both exact indexes have been parsed from SABR init data.
        startTokenWarmup(appContext, info, audioFormat, videoFormat);
        final String bootstrapKey = bootstrapKey(info, audioFormat, videoFormat);
        final Future<BootstrapResult> bootstrapFuture = startBootstrap(appContext, info,
                audioFormat, videoFormat, localization);
        final BootstrapResult bootstrap = awaitBootstrap(bootstrapKey, bootstrapFuture, videoId);
        PlaybackStartupTrace.markForVideoId(videoId, "sabr_source_spec_ready");
        return new SabrSourceSpec(videoId, info, audioFormat, videoFormat, localization,
                bootstrap.audioInitialization, bootstrap.videoInitialization,
                bootstrap.takePreparedSession());
    }

    @NonNull
    private static Future<BootstrapResult> startBootstrap(@NonNull final Context context,
                                                           @NonNull final YoutubeSabrInfo info,
                                                           @NonNull final YoutubeSabrFormat audioFormat,
                                                           @NonNull final YoutubeSabrFormat videoFormat,
                                                           @NonNull final Localization localization) {
        final String key = bootstrapKey(info, audioFormat, videoFormat);
        final BootstrapResult cached = BOOTSTRAP_CACHE.get(key);
        if (cached != null) {
            PlaybackStartupTrace.markForVideoId(info.getVideoId(), "sabr_audio_init_ready");
            PlaybackStartupTrace.markForVideoId(info.getVideoId(), "sabr_video_init_ready");
            final FutureTask<BootstrapResult> completed = new FutureTask<>(() -> cached);
            completed.run();
            return completed;
        }
        final BootstrapBackoffState backoffState = new BootstrapBackoffState(
                context, info.getVideoId());
        final FutureTask<BootstrapResult> created = new FutureTask<BootstrapResult>(() ->
                cacheBootstrap(key, createPreparation(context, info, audioFormat, videoFormat,
                        localization, backoffState))) {
            @Override
            protected void done() {
                PlaybackStartupTrace.markForVideoId(info.getVideoId(), "sabr_audio_init_ready");
                PlaybackStartupTrace.markForVideoId(info.getVideoId(), "sabr_video_init_ready");
            }
        };
        final Future<BootstrapResult> existing = BOOTSTRAP_IN_FLIGHT.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        BOOTSTRAP_BACKOFFS.put(key, backoffState);
        PlaybackStartupTrace.markForVideoId(info.getVideoId(), "sabr_bootstrap_started");
        BOOTSTRAP_EXECUTOR.execute(created);
        return created;
    }

    @NonNull
    private static BootstrapResult createBootstrap(@NonNull final Context context,
                                                   @NonNull final YoutubeSabrInfo info,
                                                   @NonNull final YoutubeSabrFormat audioFormat,
                                                   @NonNull final YoutubeSabrFormat videoFormat,
                                                   @NonNull final Localization localization,
                                                   @NonNull final BootstrapBackoffState backoffState)
            throws IOException, ExtractionException {
        final LocalDomPoTokenProvider sessionProvider = provider(context);
        final File spoolDirectory = new File(context.getApplicationContext().getCacheDir(),
                "sabr-bootstrap/" + info.getVideoId() + '-' + System.nanoTime());
        // Policy host construction boundary. With blank config, createSessionHost() returns the
        // builtin BuiltinSabrSessionPolicy host, so runtime behavior is unchanged.
        final SabrSessionPolicyHost sessionPolicyHost = SabrPolicyRuntime.createSessionHost();
        final YoutubeSabrSession session = new YoutubeSabrSession(info, audioFormat, videoFormat,
                sessionProvider, spoolDirectory, sessionPolicyHost);
        session.setBackoffListener(backoffState);
        boolean handedOff = false;
        try {
            attachPoToken(info.getVideoId(), info, sessionProvider, session);
            try {
                session.bootstrapInitialization(localization);
            } catch (final IOException firstFailure) {
                attachPoToken(info.getVideoId(), info, sessionProvider, session);
                session.bootstrapInitialization(localization);
            }
            final SabrMediaSegment audio = session.getCachedSegment(
                    SabrSegmentRequest.initialization(audioFormat));
            final SabrMediaSegment video = session.getCachedSegment(
                    SabrSegmentRequest.initialization(videoFormat));
            if (audio == null || video == null) {
                throw new SabrLogicException("SABR bootstrap completed without cached init segments"
                        + " video=" + info.getVideoId());
            }
            handedOff = true;
            return new BootstrapResult(audio.getData(), video.getData(), session);
        } finally {
            session.setBackoffListener(null);
            if (!handedOff) {
                session.clearCache();
            }
        }
    }

    @NonNull
    private static BootstrapResult createPreparation(@NonNull final Context context,
                                                     @NonNull final YoutubeSabrInfo info,
                                                     @NonNull final YoutubeSabrFormat audioFormat,
                                                     @NonNull final YoutubeSabrFormat videoFormat,
                                                     @NonNull final Localization localization,
                                                     @NonNull final BootstrapBackoffState backoffState)
            throws IOException, ExtractionException {
        final LocalDomPoTokenProvider tokenProvider = provider(context);
        final byte[] poToken = awaitWarmedToken(info.getVideoId(), info, tokenProvider,
                new YoutubeSabrStreamState(audioFormat, videoFormat));
        if (poToken == null || poToken.length == 0) {
            throw new SabrLogicException("SABR PO token provider returned no token for video="
                    + info.getVideoId());
        }
        try {
            final BootstrapResult result = createAdaptiveInitialization(info, audioFormat,
                    videoFormat, localization, poToken);
            Log.i(TAG, "adaptive initialization ready video=" + info.getVideoId()
                    + " audioItag=" + audioFormat.getItag()
                    + " videoItag=" + videoFormat.getItag());
            return result;
        } catch (final IOException adaptiveFailure) {
            Log.i(TAG, "adaptive initialization unavailable video=" + info.getVideoId()
                    + ", falling back to native SABR: " + adaptiveFailure.getMessage());
            return createBootstrap(context, info, audioFormat, videoFormat,
                    localization, backoffState);
        }
    }

    @NonNull
    private static BootstrapResult createAdaptiveInitialization(
            @NonNull final YoutubeSabrInfo info,
            @NonNull final YoutubeSabrFormat audioFormat,
            @NonNull final YoutubeSabrFormat videoFormat,
            @NonNull final Localization localization,
            @NonNull final byte[] poToken)
            throws IOException, ExtractionException {
        // Policy host construction boundary; blank config ⇒ builtin host (see createBootstrap).
        final YoutubeSabrSession session = new YoutubeSabrSession(info, audioFormat, videoFormat,
                null, null, SabrPolicyRuntime.createSessionHost());
        final Future<byte[]> audio = INITIALIZATION_EXECUTOR.submit(() ->
                session.fetchInitializationData(audioFormat, localization, 2_000, poToken));
        final Future<byte[]> video = INITIALIZATION_EXECUTOR.submit(() ->
                session.fetchInitializationData(videoFormat, localization, 2_000, poToken));
        try {
            final byte[] audioData = audio.get();
            final byte[] videoData = video.get();
            return new BootstrapResult(audioData, videoData, null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching adaptive SABR initialization", e);
        } catch (final ExecutionException e) {
            audio.cancel(true);
            video.cancel(true);
            final Throwable cause = e.getCause();
            if (cause instanceof ExtractionException) {
                throw (ExtractionException) cause;
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Could not fetch adaptive SABR initialization", cause);
        }
    }

    @NonNull
    private static BootstrapResult awaitBootstrap(@NonNull final String key,
                                                   @NonNull final Future<BootstrapResult> future,
                                                   @NonNull final String videoId)
            throws IOException, ExtractionException {
        final BootstrapBackoffState backoffState = BOOTSTRAP_BACKOFFS.get(key);
        if (backoffState != null) {
            backoffState.beginWaiting();
        }
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted awaiting SABR bootstrap for " + videoId, e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof ExtractionException) {
                throw (ExtractionException) cause;
            }
            throw new IOException("Could not bootstrap SABR for " + videoId, cause);
        } finally {
            if (backoffState != null) {
                backoffState.endWaiting();
                BOOTSTRAP_BACKOFFS.remove(key, backoffState);
            }
            BOOTSTRAP_IN_FLIGHT.remove(key, future);
        }
    }

    @NonNull
    private static String bootstrapKey(@NonNull final YoutubeSabrInfo info,
                                       @NonNull final YoutubeSabrFormat audioFormat,
                                       @NonNull final YoutubeSabrFormat videoFormat) {
        return info.getVideoId() + '#' + info.getProfile() + '#'
                + audioFormat.getItag() + ':' + audioFormat.getLastModified() + '#'
                + videoFormat.getItag() + ':' + videoFormat.getLastModified();
    }

    @NonNull
    private static BootstrapResult cacheBootstrap(@NonNull final String key,
                                                   @NonNull final BootstrapResult result) {
        BOOTSTRAP_CACHE.put(key, result);
        return result;
    }

    private static void startTokenWarmup(@NonNull final Context context,
                                         @NonNull final YoutubeSabrInfo info,
                                         @NonNull final YoutubeSabrFormat audioFormat,
                                         @NonNull final YoutubeSabrFormat videoFormat) {
        final String videoId = info.getVideoId();
        final FutureTask<byte[]> created = new FutureTask<byte[]>(() -> provider(context).getPoToken(
                info, new YoutubeSabrStreamState(audioFormat, videoFormat))) {
            @Override
            protected void done() {
                TOKEN_IN_FLIGHT.remove(videoId, this);
            }
        };
        if (TOKEN_IN_FLIGHT.putIfAbsent(videoId, created) == null) {
            TOKEN_EXECUTOR.execute(created);
        }
    }

    @NonNull
    static Lease acquire(@NonNull final Context context, @NonNull final SabrSourceSpec spec)
            throws IOException, ExtractionException {
        final SessionKey key = new SessionKey(spec.getSourceId(), spec.getVideoId(), spec.getInfo(),
                spec.getAudioFormat(), spec.getVideoFormat());
        // Resolve the shared provider before taking the session-store monitor. A token prewarm may
        // be initializing the same provider and acquire() must never wait for it while holding the
        // monitor that provider() itself needs.
        final LocalDomPoTokenProvider sessionProvider = provider(context);
        synchronized (SabrSessionStore.class) {
            final Holder current = SESSIONS.get(key);
            if (current != null) {
                current.retainLease();
                current.recordDiagnosticsThrottled("session_reuse");
                return new Lease(key, current);
            }
            final File spoolDirectory = new File(context.getApplicationContext().getCacheDir(),
                    "sabr-segments/" + spec.getVideoId() + '-' + System.nanoTime());
            final YoutubeSabrSession preparedSession = spec.takePreparedSession();
            final YoutubeSabrSession session;
            if (preparedSession != null) {
                session = preparedSession;
                session.addDiagnosticEvent("bootstrap_session_handoff");
            } else {
                // Policy host construction boundary; blank config ⇒ builtin host.
                session = new YoutubeSabrSession(spec.getInfo(), spec.getAudioFormat(),
                        spec.getVideoFormat(), sessionProvider, spoolDirectory,
                        SabrPolicyRuntime.createSessionHost());
                attachPoToken(spec.getVideoId(), spec.getInfo(), sessionProvider, session);
            }
            final Holder holder = new Holder(context, spec, session);
            seedInitializationData(holder, spec, spec.getAudioFormat());
            seedInitializationData(holder, spec, spec.getVideoFormat());
            SESSIONS.put(key, holder);
            ORDER.remove(key);
            ORDER.addLast(key);
            holder.retainLease();
            trimSessions(key);
            holder.recordDiagnostics("session_create");
            return new Lease(key, holder);
        }
    }

    private static void seedInitializationData(@NonNull final Holder holder,
                                               @NonNull final SabrSourceSpec spec,
                                               @NonNull final YoutubeSabrFormat format) {
        final byte[] data = spec.getInitializationData(format.getItag());
        if (data != null) {
            holder.setInitializationData(format.getItag(), data);
            holder.session.getStreamState().ingestInitializationData(format, data);
        }
    }

    private static void releaseLease(@NonNull final SessionKey key,
                                     @NonNull final Holder holder) {
        final int references = holder.leaseReferences.decrementAndGet();
        if (references <= 0) {
            evict(key, holder, "leases_released count=" + references, true);
        }
    }

    private static void attachPoToken(@NonNull final String videoId,
                                      @NonNull final YoutubeSabrInfo info,
                                      @NonNull final SabrPoTokenProvider provider,
                                      @NonNull final YoutubeSabrSession session)
            throws IOException, ExtractionException {
        try {
            final byte[] token = awaitWarmedToken(videoId, info, provider,
                    session.getStreamState());
            if (token == null || token.length == 0) {
                throw new SabrLogicException("SABR PO token provider returned no token for video="
                        + videoId);
            }
            session.getStreamState().setPoToken(token);
            session.addDiagnosticEvent("token_attach bytes="
                    + token.length);
        } catch (final IOException | ExtractionException e) {
            Log.w(TAG, "PO token attach failed video=" + videoId, e);
            session.addDiagnosticEvent("token_attach_failed type="
                    + e.getClass().getSimpleName() + " message=" + e.getMessage());
            throw e;
        } catch (final RuntimeException e) {
            Log.w(TAG, "PO token attach failed video=" + videoId, e);
            session.addDiagnosticEvent("token_attach_failed type="
                    + e.getClass().getSimpleName() + " message=" + e.getMessage());
            throw new SabrLogicException("SABR PO token attach failed for video=" + videoId, e);
        }
    }

    @Nullable
    private static byte[] awaitWarmedToken(@NonNull final String videoId,
                                           @NonNull final YoutubeSabrInfo info,
                                           @NonNull final SabrPoTokenProvider provider,
                                           @NonNull final org.schabi.newpipe.extractor.services
                                                   .youtube.sabr.YoutubeSabrStreamState state)
            throws IOException, ExtractionException {
        final Future<byte[]> future = TOKEN_IN_FLIGHT.get(videoId);
        if (future == null) {
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_mint_started");
            final byte[] token = provider.getPoToken(info, state);
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_ready");
            return token;
        }
        try {
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_wait_started");
            final byte[] token = future.get();
            PlaybackStartupTrace.markForVideoId(videoId, "sabr_token_ready");
            return token;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted awaiting SABR token for " + videoId, e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof ExtractionException) {
                throw (ExtractionException) cause;
            }
            throw new IOException("Could not prewarm SABR token for " + videoId, cause);
        } finally {
            TOKEN_IN_FLIGHT.remove(videoId, future);
        }
    }

    private static boolean isUsableExtractorInfo(@Nullable final YoutubeSabrInfo info,
                                                 @NonNull final String videoId) {
        return info != null
                && videoId.equals(info.getVideoId())
                && info.getServerAbrStreamingUrl() != null
                && !info.getServerAbrStreamingUrl().isEmpty()
                && !info.getFormats().isEmpty();
    }

    @NonNull
    private static YoutubeSabrInfo YoutubeSabrProbeFetch(@NonNull final String videoId,
                                                        @NonNull final Localization localization,
                                                        @NonNull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        return org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe.fetchSabrInfo(
                videoId, YoutubeSabrClientProfile.WEB, localization, contentCountry);
    }

    private static YoutubeSabrFormat pickAudioFormat(@NonNull final YoutubeSabrInfo info,
                                                     @Nullable final String preferredTrackId) {
        return pickAudioFormat(info, preferredTrackId, 0);
    }

    private static YoutubeSabrFormat pickAudioFormat(@NonNull final YoutubeSabrInfo info,
                                                     @Nullable final String preferredTrackId,
                                                     final int preferredAudioItag) {
        // Direct itag selection (from UI: sabr://videoId?a=itag) takes precedence.
        if (preferredAudioItag > 0) {
            for (final YoutubeSabrFormat f : info.getFormats()) {
                if (f.isAudio() && f.getItag() == preferredAudioItag) {
                    return f;
                }
            }
        }
        if (preferredTrackId == null) {
            return info.findBestAudioFormat();
        }
        YoutubeSabrFormat best = null;
        for (final YoutubeSabrFormat f : info.getFormats()) {
            if (!f.isAudio()) {
                continue;
            }
            if (!preferredTrackId.equals(f.getAudioTrackId())) {
                continue;
            }
            if (best == null || f.getBitrate() > best.getBitrate()) {
                best = f;
            }
        }
        return best != null ? best : info.findBestAudioFormat();
    }

    private static YoutubeSabrFormat pickVideoFormat(@NonNull final YoutubeSabrInfo info,
                                                     final int preferredItag) {
        if (preferredItag > 0) {
            for (final YoutubeSabrFormat f : info.getFormats()) {
                if (f.isVideo() && f.getItag() == preferredItag) {
                    return f;
                }
            }
        }
        return info.findLowestVideoFormat();
    }

    public static void evict(@NonNull final String videoId) {
        final List<Holder> holders = new ArrayList<>();
        synchronized (SabrSessionStore.class) {
            final java.util.Iterator<Map.Entry<SessionKey, Holder>> iterator =
                    SESSIONS.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<SessionKey, Holder> entry = iterator.next();
                if (entry.getKey().videoId.equals(videoId)) {
                    holders.add(entry.getValue());
                    ORDER.remove(entry.getKey());
                    iterator.remove();
                }
            }
        }
        for (final Holder holder : holders) {
            holder.stop("explicit");
        }
    }

    /** Reset SABR-only caches before a cold benchmark trial. Not used by playback code. */
    public static void clearBenchmarkCaches(@NonNull final Context context,
                                            @NonNull final String videoId) {
        evict(videoId);
        for (final Map.Entry<String, Future<BootstrapResult>> entry
                : BOOTSTRAP_IN_FLIGHT.entrySet()) {
            if (entry.getKey().startsWith(videoId + '#')) {
                entry.getValue().cancel(true);
                BOOTSTRAP_IN_FLIGHT.remove(entry.getKey(), entry.getValue());
                final BootstrapBackoffState backoffState =
                        BOOTSTRAP_BACKOFFS.remove(entry.getKey());
                if (backoffState != null) {
                    backoffState.cancel();
                }
            }
        }
        synchronized (BOOTSTRAP_CACHE) {
            final java.util.Iterator<Map.Entry<String, BootstrapResult>> iterator =
                    BOOTSTRAP_CACHE.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<String, BootstrapResult> entry = iterator.next();
                if (entry.getKey().startsWith(videoId + '#')) {
                    entry.getValue().discardPreparedSession();
                    iterator.remove();
                }
            }
        }
        final Future<byte[]> tokenFuture = TOKEN_IN_FLIGHT.remove(videoId);
        if (tokenFuture != null) {
            tokenFuture.cancel(true);
        }
        provider(context).clearCachedToken(videoId);
    }

    private static void trimSessions(@Nullable final SessionKey protectedKey) {
        while (true) {
            final Holder holder;
            synchronized (SabrSessionStore.class) {
                if (ORDER.size() <= MAX_SESSIONS) {
                    return;
                }
                SessionKey candidate = null;
                for (final SessionKey key : ORDER) {
                    final Holder current = SESSIONS.get(key);
                    if (!key.equals(protectedKey)
                            && current != null
                            && !current.hasActiveTracks()
                            && !current.hasLeaseReferences()) {
                        candidate = key;
                        break;
                    }
                }
                if (candidate == null) {
                    return;
                }
                holder = SESSIONS.remove(candidate);
                ORDER.remove(candidate);
            }
            if (holder != null) {
                holder.stop("session_trim protectedVideo="
                        + (protectedKey == null ? null : protectedKey.videoId));
            }
        }
    }

    private static void evict(@NonNull final SessionKey key,
                              @Nullable final Holder expectedHolder,
                              @NonNull final String reason,
                              final boolean requireNoLeaseReferences) {
        final Holder holder;
        synchronized (SabrSessionStore.class) {
            holder = SESSIONS.get(key);
            if (holder == null
                    || (expectedHolder != null && holder != expectedHolder)
                    || (requireNoLeaseReferences && holder.hasLeaseReferences())) {
                return;
            }
            SESSIONS.remove(key);
            ORDER.remove(key);
        }
        if (holder != null) {
            holder.stop(reason);
        }
    }
}
