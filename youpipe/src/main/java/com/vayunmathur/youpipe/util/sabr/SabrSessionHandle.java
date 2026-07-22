package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Coordinates one lazy SABR session lease across overlapping MediaPeriods and loader threads. */
final class SabrSessionHandle {
    @NonNull private final Context appContext;
    @NonNull private final SabrSourceSpec spec;
    private final Map<Object, Integer> trackModes = new IdentityHashMap<>();

    @Nullable private SabrSessionStore.Lease lease;
    @Nullable private FutureTask<SabrSessionStore.Lease> acquisition;
    private int activePeriods;
    private long periodGeneration;
    private long playerTimeMs;
    private long pendingSeekMs = -1;

    SabrSessionHandle(@NonNull final Context context, @NonNull final SabrSourceSpec spec) {
        this.appContext = context.getApplicationContext();
        this.spec = spec;
    }

    synchronized void onPeriodCreated(final long startPositionMs) {
        if (activePeriods == 0) {
            periodGeneration++;
        }
        activePeriods++;
        if (startPositionMs > 0) {
            playerTimeMs = startPositionMs;
            pendingSeekMs = startPositionMs;
        }
    }

    void onPeriodReleased() {
        final SabrSessionStore.Lease leaseToClose;
        synchronized (this) {
            if (activePeriods > 0) {
                activePeriods--;
            }
            if (activePeriods != 0) {
                return;
            }
            periodGeneration++;
            trackModes.clear();
            pendingSeekMs = -1;
            acquisition = null;
            leaseToClose = lease;
            lease = null;
        }
        if (leaseToClose != null) {
            leaseToClose.close();
        }
    }

    @NonNull
    SabrSessionStore.Holder acquireHolder() throws IOException {
        final FutureTask<SabrSessionStore.Lease> future;
        final long generation;
        final boolean create;
        synchronized (this) {
            if (lease != null) {
                return lease.getHolder();
            }
            if (activePeriods <= 0) {
                throw new IOException("SABR period is no longer active for " + spec.getVideoId());
            }
            generation = periodGeneration;
            if (acquisition == null) {
                acquisition = new FutureTask<>(
                        () -> SabrSessionStore.acquire(appContext, spec));
                create = true;
            } else {
                create = false;
            }
            future = acquisition;
        }

        if (create) {
            future.run();
        }

        final SabrSessionStore.Lease acquired = await(future);
        synchronized (this) {
            if (activePeriods <= 0 || generation != periodGeneration) {
                if (lease != acquired) {
                    acquired.close();
                }
                throw new IOException("SABR period was released while acquiring "
                        + spec.getVideoId());
            }
            if (lease != null) {
                if (lease != acquired) {
                    acquired.close();
                }
                return lease.getHolder();
            }
            lease = acquired;
            if (acquisition == future) {
                acquisition = null;
            }
            applyPendingState(acquired.getHolder());
            return acquired.getHolder();
        }
    }

    private SabrSessionStore.Lease await(
            @NonNull final FutureTask<SabrSessionStore.Lease> future) throws IOException {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted acquiring SABR session for " + spec.getVideoId(), e);
        } catch (final ExecutionException e) {
            synchronized (this) {
                if (acquisition == future) {
                    acquisition = null;
                }
            }
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Could not acquire SABR session for " + spec.getVideoId(), cause);
        }
    }

    private void applyPendingState(@NonNull final SabrSessionStore.Holder holder) {
        holder.setPlayerTimeMs(playerTimeMs);
        for (final Map.Entry<Object, Integer> entry : trackModes.entrySet()) {
            final int mode = entry.getValue();
            holder.setActiveTracks(entry.getKey(), (mode & 1) != 0, (mode & 2) != 0);
        }
        if (pendingSeekMs >= 0) {
            holder.requestSeek(pendingSeekMs, spec.getLocalization());
        }
    }

    void setActiveTracks(@NonNull final Object owner,
                         final boolean videoActive,
                         final boolean audioActive) {
        final SabrSessionStore.Holder holder;
        synchronized (this) {
            final int mode = (videoActive ? 1 : 0) | (audioActive ? 2 : 0);
            if (mode == 0) {
                trackModes.remove(owner);
            } else {
                trackModes.put(owner, mode);
            }
            holder = lease == null ? null : lease.getHolder();
        }
        if (holder != null) {
            holder.setActiveTracks(owner, videoActive, audioActive);
        }
    }

    void releaseTracks(@NonNull final Object owner) {
        final SabrSessionStore.Holder holder;
        synchronized (this) {
            trackModes.remove(owner);
            holder = lease == null ? null : lease.getHolder();
        }
        if (holder != null) {
            holder.releaseTracks(owner);
        }
    }

    void advanceReaderGeneration(@NonNull final Object owner) {
        final SabrSessionStore.Holder holder = getHolder();
        if (holder != null) {
            holder.advanceReaderGeneration(owner);
        }
    }

    void requestSeek(final long positionMs) {
        final SabrSessionStore.Holder holder;
        synchronized (this) {
            playerTimeMs = Math.max(0, positionMs);
            pendingSeekMs = playerTimeMs;
            holder = lease == null ? null : lease.getHolder();
        }
        if (holder != null) {
            holder.requestSeek(playerTimeMs, spec.getLocalization());
        }
    }

    synchronized void setPlayerTimeMs(final long positionMs) {
        playerTimeMs = Math.max(0, positionMs);
        if (lease != null) {
            lease.getHolder().setPlayerTimeMs(playerTimeMs);
        }
    }

    @Nullable
    synchronized SabrSessionStore.Holder getHolder() {
        return lease == null ? null : lease.getHolder();
    }

    void close() {
        final SabrSessionStore.Lease leaseToClose;
        synchronized (this) {
            activePeriods = 0;
            periodGeneration++;
            trackModes.clear();
            pendingSeekMs = -1;
            acquisition = null;
            leaseToClose = lease;
            lease = null;
        }
        if (leaseToClose != null) {
            leaseToClose.close();
        }
        spec.discardPreparedSession();
    }
}
