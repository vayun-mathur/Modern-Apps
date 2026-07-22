package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;

/**
 * Tracks the SABR server-wait state so the pump and session store can coordinate backoff.
 *
 * <p>The upstream PipePipe implementation also published a status notification (via MainActivity
 * and app string/drawable resources). That UI is intentionally omitted in the youpipe port; only
 * the state-tracking API used by the SABR playback code is preserved.
 */
public final class SabrBackoffCoordinator {
    public static final long NO_DEADLINE = -1L;
    private static final SabrBackoffCoordinator INSTANCE = new SabrBackoffCoordinator();

    private Object owner;
    private long deadlineElapsedMs = NO_DEADLINE;
    private boolean playbackBlockedBeforeBuffering;

    private SabrBackoffCoordinator() {
    }

    @NonNull
    public static SabrBackoffCoordinator getInstance() {
        return INSTANCE;
    }

    public synchronized void begin(@NonNull final Context context,
                                   @NonNull final Object sourceOwner,
                                   final long deadlineMs) {
        begin(context, sourceOwner, deadlineMs, false);
    }

    public synchronized void beginPlaybackWait(@NonNull final Context context,
                                               @NonNull final Object sourceOwner,
                                               final long deadlineMs) {
        begin(context, sourceOwner, deadlineMs, true);
    }

    private synchronized void begin(@NonNull final Context context,
                                    @NonNull final Object sourceOwner,
                                    final long deadlineMs,
                                    final boolean blocksPlaybackBeforeBuffering) {
        if (deadlineMs <= SystemClock.elapsedRealtime()) {
            clear(context, sourceOwner);
            return;
        }
        if (owner != sourceOwner) {
            owner = sourceOwner;
            deadlineElapsedMs = deadlineMs;
            playbackBlockedBeforeBuffering = blocksPlaybackBeforeBuffering;
        } else {
            deadlineElapsedMs = Math.max(deadlineElapsedMs, deadlineMs);
            playbackBlockedBeforeBuffering |= blocksPlaybackBeforeBuffering;
        }
    }

    public synchronized void clear(@NonNull final Context context,
                                   @NonNull final Object sourceOwner) {
        if (owner != sourceOwner) {
            return;
        }
        owner = null;
        deadlineElapsedMs = NO_DEADLINE;
        playbackBlockedBeforeBuffering = false;
    }

    public synchronized void setPlayerBuffering(@NonNull final Context context,
                                                final boolean buffering) {
        // Notification UI omitted in the youpipe port; nothing to update.
    }

    public synchronized long getRemainingMs() {
        return deadlineElapsedMs == NO_DEADLINE
                ? 0L : Math.max(0L, deadlineElapsedMs - SystemClock.elapsedRealtime());
    }

    public synchronized boolean isWaiting() {
        return getRemainingMs() > 0L;
    }
}
