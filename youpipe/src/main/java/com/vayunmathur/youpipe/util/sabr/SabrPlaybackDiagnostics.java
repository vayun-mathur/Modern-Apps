package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;

import androidx.annotation.NonNull;

import java.util.Locale;

public final class SabrPlaybackDiagnostics {
    private static final String PREFS = "sabr_playback_diagnostics";
    private static final String KEY_LAST_SNAPSHOT = "last_snapshot";

    private SabrPlaybackDiagnostics() {
    }

    static void record(@NonNull final Context context,
                       @NonNull final SabrSessionStore.Holder holder,
                       @NonNull final String event) {
        final Runtime runtime = Runtime.getRuntime();
        final long maxHeap = runtime.maxMemory();
        final long totalHeap = runtime.totalMemory();
        final long freeHeap = runtime.freeMemory();
        final long usedHeap = totalHeap - freeHeap;
        final long pssKb = Debug.getPss();
        final String snapshot = String.format(Locale.US,
                "event=%s\n"
                        + "timeMs=%d\n"
                        + "videoId=%s\n"
                        + "playerTimeMs=%d\n"
                        + "readerHeadMs=%d\n"
                        + "readerTailMs=%d\n"
                        + "videoItag=%d\n"
                        + "videoHeight=%d\n"
                        + "videoBitrate=%d\n"
                        + "audioItag=%d\n"
                        + "audioBitrate=%d\n"
                        + "heapUsedBytes=%d\n"
                        + "heapFreeBytes=%d\n"
                        + "heapTotalBytes=%d\n"
                        + "heapMaxBytes=%d\n"
                        + "pssKb=%d\n"
                        + "sabr=%s\n",
                event,
                System.currentTimeMillis(),
                holder.videoId,
                holder.getPlayerTimeMs(),
                holder.getReaderHeadMs(),
                holder.getReaderTailMs(),
                holder.videoFormat.getItag(),
                holder.videoFormat.getHeight(),
                holder.videoFormat.getBitrate(),
                holder.audioFormat.getItag(),
                holder.audioFormat.getBitrate(),
                usedHeap,
                freeHeap,
                totalHeap,
                maxHeap,
                pssKb,
                holder.session.getMemoryDiagnosticSummary());
        preferences(context).edit().putString(KEY_LAST_SNAPSHOT, snapshot).apply();
    }

    @NonNull
    public static String getLastSnapshot(@NonNull final Context context) {
        return preferences(context).getString(KEY_LAST_SNAPSHOT, "");
    }

    private static SharedPreferences preferences(@NonNull final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
