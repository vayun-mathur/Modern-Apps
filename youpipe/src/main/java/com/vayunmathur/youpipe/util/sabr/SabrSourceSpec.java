package com.vayunmathur.youpipe.util.sabr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable metadata needed to construct a SABR MediaSource without owning a live session. */
public final class SabrSourceSpec {
    private static final AtomicLong NEXT_SOURCE_ID = new AtomicLong();

    private final long sourceId;
    @NonNull private final String videoId;
    @NonNull private final YoutubeSabrInfo info;
    @NonNull private final YoutubeSabrFormat audioFormat;
    @NonNull private final YoutubeSabrFormat videoFormat;
    @NonNull private final Localization localization;
    @NonNull private final byte[] audioInitializationData;
    @NonNull private final byte[] videoInitializationData;
    @NonNull private final AtomicReference<YoutubeSabrSession> preparedSession;

    public SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData) {
        this(videoId, info, audioFormat, videoFormat, localization,
                audioInitializationData, videoInitializationData, null);
    }

    SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData,
                   @Nullable final YoutubeSabrSession preparedSession) {
        this.sourceId = NEXT_SOURCE_ID.incrementAndGet();
        this.videoId = videoId;
        this.info = info;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
        this.localization = localization;
        this.audioInitializationData = audioInitializationData.clone();
        this.videoInitializationData = videoInitializationData.clone();
        this.preparedSession = new AtomicReference<>(preparedSession);
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    long getSourceId() {
        return sourceId;
    }

    @NonNull
    public YoutubeSabrInfo getInfo() {
        return info;
    }

    @NonNull
    public YoutubeSabrFormat getAudioFormat() {
        return audioFormat;
    }

    @NonNull
    public YoutubeSabrFormat getVideoFormat() {
        return videoFormat;
    }

    @NonNull
    Localization getLocalization() {
        return localization;
    }

    @Nullable
    byte[] getInitializationData(final int itag) {
        if (itag == audioFormat.getItag()) {
            return audioInitializationData.clone();
        }
        if (itag == videoFormat.getItag()) {
            return videoInitializationData.clone();
        }
        return null;
    }

    long getDurationMs() {
        return Math.max(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs());
    }

    @NonNull
    YoutubeSabrStreamState newStreamState() {
        final YoutubeSabrStreamState state = new YoutubeSabrStreamState(audioFormat, videoFormat);
        state.ingestInitializationData(audioFormat, audioInitializationData);
        state.ingestInitializationData(videoFormat, videoInitializationData);
        return state;
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
