package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public final class YoutubeSabrInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Nonnull
    private final YoutubeSabrClientProfile profile;
    @Nonnull
    private final String videoId;
    @Nonnull
    private final String cpn;
    @Nonnull
    private final String clientVersion;
    @Nullable
    private final String visitorData;
    @Nullable
    private final String serverAbrStreamingUrl;
    @Nullable
    private final String videoPlaybackUstreamerConfig;
    @Nonnull
    private final List<YoutubeSabrFormat> formats;

    YoutubeSabrInfo(@Nonnull final YoutubeSabrClientProfile profile,
                    @Nonnull final String videoId,
                    @Nonnull final String cpn,
                    @Nonnull final String clientVersion,
                    @Nullable final String visitorData,
                    @Nullable final String serverAbrStreamingUrl,
                    @Nullable final String videoPlaybackUstreamerConfig,
                    @Nonnull final List<YoutubeSabrFormat> formats) {
        this.profile = profile;
        this.videoId = videoId;
        this.cpn = cpn;
        this.clientVersion = clientVersion;
        this.visitorData = visitorData;
        this.serverAbrStreamingUrl = serverAbrStreamingUrl;
        this.videoPlaybackUstreamerConfig = videoPlaybackUstreamerConfig;
        this.formats = formats;
    }

    @Nonnull
    public YoutubeSabrClientProfile getProfile() {
        return profile;
    }

    @Nonnull
    public String getVideoId() {
        return videoId;
    }

    @Nonnull
    public String getCpn() {
        return cpn;
    }

    @Nonnull
    public String getClientVersion() {
        return clientVersion;
    }

    @Nullable
    public String getVisitorData() {
        return visitorData;
    }

    @Nullable
    public String getServerAbrStreamingUrl() {
        return serverAbrStreamingUrl;
    }

    @Nullable
    public String getVideoPlaybackUstreamerConfig() {
        return videoPlaybackUstreamerConfig;
    }

    @Nonnull
    public List<YoutubeSabrFormat> getFormats() {
        return Collections.unmodifiableList(formats);
    }

    @Nullable
    public YoutubeSabrFormat findBestAudioFormat() {
        YoutubeSabrFormat best = null;
        for (final YoutubeSabrFormat format : formats) {
            if (!format.isAudio()) {
                continue;
            }
            if (best == null) {
                best = format;
                continue;
            }
            // Prefer the original-language track over auto-dubs, then the highest bitrate. Keeps the
            // current behaviour (highest bitrate) when there is no original-marked track.
            final boolean preferForTrack = format.isOriginalAudio() && !best.isOriginalAudio();
            final boolean preferForBitrate = format.isOriginalAudio() == best.isOriginalAudio()
                    && format.getBitrate() > best.getBitrate();
            if (preferForTrack || preferForBitrate) {
                best = format;
            }
        }
        return best;
    }

    @Nullable
    public YoutubeSabrFormat findLowestVideoFormat() {
        YoutubeSabrFormat lowest = null;
        for (final YoutubeSabrFormat format : formats) {
            if (format.isVideo() && (lowest == null || format.getHeight() < lowest.getHeight())) {
                lowest = format;
            }
        }
        return lowest;
    }

    @Nullable
    public YoutubeSabrFormat findFormatByItag(final int itag) {
        for (final YoutubeSabrFormat format : formats) {
            if (format.getItag() == itag) {
                return format;
            }
        }
        return null;
    }
}
