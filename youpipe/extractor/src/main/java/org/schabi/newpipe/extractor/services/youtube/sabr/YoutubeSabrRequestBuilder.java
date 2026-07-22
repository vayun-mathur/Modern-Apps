package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Base64;
import java.util.List;

final class YoutubeSabrRequestBuilder {
    static final int ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO = 0;
    static final int ENABLED_TRACK_TYPES_AUDIO_ONLY = 1;
    static final int ENABLED_TRACK_TYPES_VIDEO_ONLY = 2;

    private YoutubeSabrRequestBuilder() {
    }

    @Nonnull
    static byte[] buildFirstMediaRequest(@Nonnull final YoutubeSabrInfo info,
                                          @Nonnull final YoutubeSabrFormat audioFormat,
                                          @Nonnull final YoutubeSabrFormat videoFormat)
            throws SabrProtocolException {
        return buildFirstMediaRequest(info, audioFormat, videoFormat, null);
    }

    @Nonnull
    static byte[] buildFirstMediaRequest(@Nonnull final YoutubeSabrInfo info,
                                         @Nonnull final YoutubeSabrFormat audioFormat,
                                         @Nonnull final YoutubeSabrFormat videoFormat,
                                         @Nullable final YoutubeSabrStreamState streamState)
            throws SabrProtocolException {
        if (streamState != null) {
            synchronized (streamState) {
                return buildFirstMediaRequestLocked(info, audioFormat, videoFormat, streamState);
            }
        }
        return buildFirstMediaRequestLocked(info, audioFormat, videoFormat, null);
    }

    @Nonnull
    private static byte[] buildFirstMediaRequestLocked(@Nonnull final YoutubeSabrInfo info,
                                                       @Nonnull final YoutubeSabrFormat audioFormat,
                                                       @Nonnull final YoutubeSabrFormat videoFormat,
                                                       @Nullable final YoutubeSabrStreamState streamState)
            throws SabrProtocolException {
        final String ustreamerConfig = info.getVideoPlaybackUstreamerConfig();
        if (ustreamerConfig == null || ustreamerConfig.isEmpty()) {
            throw new SabrProtocolException("Missing video playback ustreamer config");
        }

        final long playerTimeMs = streamState == null ? 0 : streamState.getRequestPlayerTimeMs();
        final List<SabrBufferedRange> bufferedRanges = streamState == null
                ? java.util.Collections.emptyList()
                : streamState.getBufferedRanges();
        final boolean includeInitialPlaybackState = playerTimeMs > 0 || !bufferedRanges.isEmpty();
        final SabrProto.Writer request = new SabrProto.Writer();
        request.writeMessage(1, buildClientAbrState(audioFormat, videoFormat, playerTimeMs,
                includeInitialPlaybackState,
                streamState == null
                        ? ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO
                        : streamState.getEnabledTrackTypesBitfield(),
                streamState));
        if (includeInitialPlaybackState) {
            if (streamState.shouldSelectVideoFormatBeforeAudio()
                    && streamState.shouldSelectVideoFormat()
                    && streamState.isInitialized(videoFormat)) {
                request.writeMessage(2, SabrProto.formatId(videoFormat));
            }
            if (streamState.shouldSelectAudioFormat() && streamState.isInitialized(audioFormat)) {
                request.writeMessage(2, SabrProto.formatId(audioFormat));
            }
            if (!streamState.shouldSelectVideoFormatBeforeAudio()
                    && streamState.shouldSelectVideoFormat()
                    && streamState.isInitialized(videoFormat)) {
                request.writeMessage(2, SabrProto.formatId(videoFormat));
            }
            for (final SabrBufferedRange range : bufferedRanges) {
                request.writeMessage(3, range.toProto(
                        streamState.shouldWriteBufferedRangeTimeRange()));
            }
            if (streamState.shouldWriteTopLevelPlayerTimeMs()) {
                request.writeUInt64(4, playerTimeMs);
            }
        }
        request.writeBytes(5, decodeBase64(ustreamerConfig));
        writePreferredFormats(request, info, audioFormat, videoFormat, streamState);
        request.writeMessage(19, streamState == null
                ? buildStreamerContext(info)
                : buildStreamerContext(info, streamState));
        return request.toByteArray();
    }

    @Nonnull
    static byte[] buildFollowUpMediaRequest(@Nonnull final YoutubeSabrInfo info,
                                            @Nonnull final YoutubeSabrFormat audioFormat,
                                            @Nonnull final YoutubeSabrFormat videoFormat,
                                            @Nonnull final YoutubeSabrStreamState streamState)
            throws SabrProtocolException {
        synchronized (streamState) {
            return buildFollowUpMediaRequestLocked(info, audioFormat, videoFormat, streamState);
        }
    }

    @Nonnull
    private static byte[] buildFollowUpMediaRequestLocked(@Nonnull final YoutubeSabrInfo info,
                                                          @Nonnull final YoutubeSabrFormat audioFormat,
                                                          @Nonnull final YoutubeSabrFormat videoFormat,
                                                          @Nonnull final YoutubeSabrStreamState streamState)
            throws SabrProtocolException {
        final String ustreamerConfig = info.getVideoPlaybackUstreamerConfig();
        if (ustreamerConfig == null || ustreamerConfig.isEmpty()) {
            throw new SabrProtocolException("Missing video playback ustreamer config");
        }

        final long playerTimeMs = streamState.getRequestPlayerTimeMs();
        final SabrProto.Writer request = new SabrProto.Writer();
        request.writeMessage(1, buildClientAbrState(audioFormat, videoFormat, playerTimeMs,
                true, streamState.getEnabledTrackTypesBitfield(), streamState));
        if (streamState.shouldSelectVideoFormatBeforeAudio()
                && streamState.shouldSelectVideoFormat()
                && streamState.isInitialized(videoFormat)) {
            request.writeMessage(2, SabrProto.formatId(videoFormat));
        }
        if (streamState.shouldSelectAudioFormat() && streamState.isInitialized(audioFormat)) {
            request.writeMessage(2, SabrProto.formatId(audioFormat));
        }
        if (!streamState.shouldSelectVideoFormatBeforeAudio()
                && streamState.shouldSelectVideoFormat()
                && streamState.isInitialized(videoFormat)) {
            request.writeMessage(2, SabrProto.formatId(videoFormat));
        }
        final List<SabrBufferedRange> bufferedRanges = streamState.getBufferedRanges();
        for (final SabrBufferedRange range : bufferedRanges) {
            request.writeMessage(3, range.toProto(streamState.shouldWriteBufferedRangeTimeRange()));
        }
        if (streamState.shouldWriteTopLevelPlayerTimeMs()) {
            request.writeUInt64(4, playerTimeMs);
        }
        request.writeBytes(5, decodeBase64(ustreamerConfig));
        writePreferredFormats(request, info, audioFormat, videoFormat, streamState);
        request.writeMessage(19, buildStreamerContext(info, streamState));
        return request.toByteArray();
    }

    @Nonnull
    private static byte[] buildClientAbrState(@Nonnull final YoutubeSabrFormat audioFormat,
                                               @Nonnull final YoutubeSabrFormat videoFormat) {
        return buildClientAbrState(audioFormat, videoFormat, 0, false);
    }

    @Nonnull
    private static byte[] buildClientAbrState(@Nonnull final YoutubeSabrFormat audioFormat,
                                               @Nonnull final YoutubeSabrFormat videoFormat,
                                                 final long playerTimeMs,
                                                 final boolean includeFollowUpState) {
        return buildClientAbrState(audioFormat, videoFormat, playerTimeMs, includeFollowUpState,
                ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO);
    }

    @Nonnull
    private static byte[] buildClientAbrState(@Nonnull final YoutubeSabrFormat audioFormat,
                                                 @Nonnull final YoutubeSabrFormat videoFormat,
                                                  final long playerTimeMs,
                                                  final boolean includeFollowUpState,
                                                  final int enabledTrackTypesBitfield) {
        return buildClientAbrState(audioFormat, videoFormat, playerTimeMs, includeFollowUpState,
                enabledTrackTypesBitfield, null);
    }

    @Nonnull
    private static byte[] buildClientAbrState(@Nonnull final YoutubeSabrFormat audioFormat,
                                                 @Nonnull final YoutubeSabrFormat videoFormat,
                                                  final long playerTimeMs,
                                                  final boolean includeFollowUpState,
                                                  final int enabledTrackTypesBitfield,
                                                  @Nullable final YoutubeSabrStreamState streamState) {
        final SabrProto.Writer state = new SabrProto.Writer();
        final boolean officialWebClientAbrFields = streamState != null
                && streamState.shouldWriteOfficialWebClientAbrFields();
        if ((includeFollowUpState || officialWebClientAbrFields) && streamState != null
                && streamState.shouldWriteLastManualSelectedResolution()) {
            state.writeInt32(16, Math.max(videoFormat.getHeight(), 360));
        }
        if (includeFollowUpState || officialWebClientAbrFields) {
            state.writeInt32(18, streamState != null && streamState.getClientViewportWidth() > 0
                    ? streamState.getClientViewportWidth()
                    : Math.max(videoFormat.getWidth(), 640));
            state.writeInt32(19, streamState != null && streamState.getClientViewportHeight() > 0
                    ? streamState.getClientViewportHeight()
                    : Math.max(videoFormat.getHeight(), 360));
        }
        final Integer stickyResolutionOverride = streamState == null
                ? null
                : streamState.getStickyResolutionOverride();
        state.writeInt32(21, stickyResolutionOverride == null
                ? Math.max(videoFormat.getHeight(), 360)
                : stickyResolutionOverride);
        if (includeFollowUpState || officialWebClientAbrFields) {
            final long bandwidthEstimate = streamState != null
                    && streamState.getBandwidthEstimate() > 0
                    ? streamState.getBandwidthEstimate()
                    : audioFormat.getBitrate() > 0 && videoFormat.getBitrate() > 0
                    ? (audioFormat.getBitrate() + videoFormat.getBitrate()) * 2L
                    : -1;
            if (bandwidthEstimate > 0) {
                state.writeUInt64(23, bandwidthEstimate);
            }
        }
        final Integer visibility = streamState == null
                ? Integer.valueOf(1)
                : streamState.getClientAbrVisibility();
        if (visibility != null) {
            state.writeInt32(34, visibility);
        }
        state.writeFloat(35, streamState == null ? 1.0f : streamState.getPlaybackRate());
        if (enabledTrackTypesBitfield != ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO) {
            state.writeInt32(40, enabledTrackTypesBitfield);
        }
        if (audioFormat.isDrc()) {
            state.writeBool(46, true);
        }
        if (streamState != null
                && streamState.getSabrReportRequestCancellationInfoOverride() != null) {
            state.writeInt32(54, streamState.getSabrReportRequestCancellationInfoOverride());
        }
        if (officialWebClientAbrFields) {
            if (includeFollowUpState) {
                state.writeUInt64(29, longOverride(
                        streamState.getOfficialTimeSinceLastSeekOverride(), 48));
                state.writeUInt64(36, longOverride(
                        streamState.getOfficialElapsedWallTimeOverride(), 1406));
                state.writeUInt64(39, longOverride(
                        streamState.getOfficialTimeSinceLastActionOverride(), 1446));
                state.writeUInt64(57, longOverride(streamState.getOfficialField57Override(), 59));
            } else {
                state.writeUInt64(29, longOverride(
                        streamState.getOfficialTimeSinceLastSeekOverride(), 9));
                state.writeUInt64(36, longOverride(
                        streamState.getOfficialElapsedWallTimeOverride(), 41));
                state.writeUInt64(39, longOverride(
                        streamState.getOfficialTimeSinceLastActionOverride(), 80));
                final Long officialField57Override = streamState.getOfficialField57Override();
                if (officialField57Override != null) {
                    state.writeUInt64(57, officialField57Override);
                }
            }
            state.writeBool(58, false);
            state.writeInt32(59, Math.max(videoFormat.getHeight(), 1080));
            state.writeUInt64(68, longOverride(streamState.getOfficialField68Override(), 0));
            state.writeBool(71, true);
            state.writeMessage(72, buildOfficialWebQualityConstraints(
                    Math.max(videoFormat.getHeight(), 1080)));
            state.writeInt32(76, 0);
            state.writeMessage(79, buildOfficialWebPlaybackAuthorization());
            if (!includeFollowUpState) {
                state.writeInt32(80, 1);
            }
        }
        state.writeUInt64(28, playerTimeMs);
        state.writeStringIfNotEmpty(69, audioFormat.getAudioTrackId());
        return state.toByteArray();
    }

    private static void writePreferredFormats(@Nonnull final SabrProto.Writer request,
                                              @Nonnull final YoutubeSabrInfo info,
                                              @Nonnull final YoutubeSabrFormat audioFormat,
                                              @Nonnull final YoutubeSabrFormat videoFormat,
                                              @Nullable final YoutubeSabrStreamState streamState) {
        if (streamState != null && streamState.shouldWriteAllPreferredFormats()) {
            if (streamState.shouldWriteOfficialWebPreferredFormats()) {
                writeOfficialWebPreferredFormats(request, info);
                return;
            }
            for (final YoutubeSabrFormat format : info.getFormats()) {
                if (format.isAudio() && streamState.shouldSelectAudioFormat()) {
                    request.writeMessage(16, SabrProto.formatId(format));
                }
            }
            for (final YoutubeSabrFormat format : info.getFormats()) {
                if (format.isVideo() && streamState.shouldSelectVideoFormat()) {
                    request.writeMessage(17, SabrProto.formatId(format));
                }
            }
            return;
        }
        if (streamState == null || streamState.shouldSelectAudioFormat()) {
            request.writeMessage(16, SabrProto.formatId(audioFormat));
        }
        if (streamState == null || streamState.shouldSelectVideoFormat()) {
            request.writeMessage(17, SabrProto.formatId(videoFormat));
        }
    }

    private static void writeOfficialWebPreferredFormats(@Nonnull final SabrProto.Writer request,
                                                         @Nonnull final YoutubeSabrInfo info) {
        writeAudioFormatByItagAndXtagsLength(request, info, 251, 12);
        writeAudioFormatByItagAndXtagsLength(request, info, 251, 14);
        writeAudioFormatByItagAndXtagsLength(request, info, 251, 0);
        writeAudioFormatByItagAndXtagsLength(request, info, 250, 14);
        writeAudioFormatByItagAndXtagsLength(request, info, 250, 0);
        writeAudioFormatByItagAndXtagsLength(request, info, 250, 12);
        writeVideoFormatByItag(request, info, 248);
        writeVideoFormatByItag(request, info, 247);
        writeVideoFormatByItag(request, info, 244);
        writeVideoFormatByItag(request, info, 243);
        writeVideoFormatByItag(request, info, 242);
        writeVideoFormatByItag(request, info, 278);
    }

    private static void writeAudioFormatByItagAndXtagsLength(
            @Nonnull final SabrProto.Writer request,
            @Nonnull final YoutubeSabrInfo info,
            final int itag,
            final int xtagsLength) {
        for (final YoutubeSabrFormat format : info.getFormats()) {
            final String xtags = format.getXtags();
            final int currentXtagsLength = xtags == null ? 0 : xtags.length();
            if (format.isAudio() && format.getItag() == itag
                    && currentXtagsLength == xtagsLength) {
                request.writeMessage(16, SabrProto.formatId(format));
            }
        }
    }

    private static void writeVideoFormatByItag(@Nonnull final SabrProto.Writer request,
                                               @Nonnull final YoutubeSabrInfo info,
                                               final int itag) {
        for (final YoutubeSabrFormat format : info.getFormats()) {
            if (format.isVideo() && format.getItag() == itag) {
                request.writeMessage(17, SabrProto.formatId(format));
                return;
            }
        }
    }

    private static boolean isOfficialWebPreferredAudio(@Nonnull final YoutubeSabrFormat format) {
        final String mimeType = format.getMimeType();
        return mimeType != null && mimeType.contains("webm") && format.getItag() != 249;
    }

    private static boolean isOfficialWebPreferredVideo(@Nonnull final YoutubeSabrFormat format) {
        final String mimeType = format.getMimeType();
        return mimeType != null && mimeType.contains("webm");
    }

    @Nonnull
    private static byte[] buildOfficialWebQualityConstraints(final int height) {
        final SabrProto.Writer constraints = new SabrProto.Writer();
        constraints.writeInt32(1, 0);
        constraints.writeInt32(2, height);
        constraints.writeInt32(3, 0);
        constraints.writeInt32(4, 0);
        constraints.writeInt32(5, height);
        constraints.writeInt32(6, 0);
        return constraints.toByteArray();
    }

    @Nonnull
    private static byte[] buildOfficialWebPlaybackAuthorization() {
        final SabrProto.Writer authorization = new SabrProto.Writer();
        authorization.writeMessage(1, buildAuthorizedTrack(1, false));
        authorization.writeMessage(1, buildAuthorizedTrack(2, false));
        authorization.writeMessage(1, buildAuthorizedTrack(2, true));
        return authorization.toByteArray();
    }

    @Nonnull
    private static byte[] buildAuthorizedTrack(final int trackType, final boolean hdr) {
        final SabrProto.Writer track = new SabrProto.Writer();
        track.writeInt32(1, trackType);
        track.writeBool(2, hdr);
        return track.toByteArray();
    }

    @Nonnull
    private static byte[] buildStreamerContext(@Nonnull final YoutubeSabrInfo info) {
        return buildStreamerContext(info, (byte[]) null);
    }

    @Nonnull
    private static byte[] buildStreamerContext(@Nonnull final YoutubeSabrInfo info,
                                               @Nonnull final YoutubeSabrStreamState streamState) {
        return buildStreamerContext(info, streamState.getRawPlaybackCookie(), streamState);
    }

    @Nonnull
    private static byte[] buildStreamerContext(@Nonnull final YoutubeSabrInfo info,
                                               @Nullable final byte[] playbackCookie) {
        return buildStreamerContext(info, playbackCookie, null);
    }

    @Nonnull
    private static byte[] buildStreamerContext(@Nonnull final YoutubeSabrInfo info,
                                               @Nullable final byte[] playbackCookie,
                                               @Nullable final YoutubeSabrStreamState streamState) {
        final SabrProto.Writer context = new SabrProto.Writer();
        context.writeMessage(1, buildClientInfo(info, streamState));
        final byte[] poToken = streamState == null ? null : streamState.getRawPoToken();
        if (poToken != null && poToken.length > 0) {
            context.writeBytes(2, poToken);
        }
        if (playbackCookie != null && playbackCookie.length > 0) {
            context.writeBytes(3, playbackCookie);
        }
        if (streamState != null) {
            for (final SabrContextUpdate contextUpdate : streamState.getActiveSabrContexts()) {
                context.writeMessage(5, contextUpdate.toStreamerContextProto());
            }
            for (final Integer type : streamState.getUnsentSabrContextTypes()) {
                context.writeInt32(6, type);
            }
        }
        return context.toByteArray();
    }

    @Nonnull
    private static byte[] buildClientInfo(@Nonnull final YoutubeSabrInfo info) {
        return buildClientInfo(info, null);
    }

    @Nonnull
    private static byte[] buildClientInfo(@Nonnull final YoutubeSabrInfo info,
                                          @Nullable final YoutubeSabrStreamState streamState) {
        final SabrProto.Writer client = new SabrProto.Writer();
        if (streamState != null && streamState.shouldWriteOfficialWebClientAbrFields()) {
            client.writeStringIfNotEmpty(1, "en_US");
            client.writeInt32(16, parseInt(info.getProfile().getClientId(), -1));
            client.writeStringIfNotEmpty(17, info.getClientVersion());
            client.writeStringIfNotEmpty(18, "X11");
            return client.toByteArray();
        }
        client.writeInt32(16, parseInt(info.getProfile().getClientId(), -1));
        client.writeStringIfNotEmpty(17, info.getClientVersion());
        client.writeStringIfNotEmpty(18, info.getProfile().getOsName());
        client.writeStringIfNotEmpty(19, info.getProfile().getOsVersion());
        client.writeStringIfNotEmpty(21, "en-US");
        client.writeStringIfNotEmpty(22, "US");
        return client.toByteArray();
    }

    @Nonnull
    private static byte[] decodeBase64(@Nonnull final String value) throws SabrProtocolException {
        try {
            return Base64.getDecoder().decode(padBase64(value));
        } catch (final IllegalArgumentException first) {
            try {
                return Base64.getUrlDecoder().decode(padBase64(value));
            } catch (final IllegalArgumentException second) {
                throw new SabrProtocolException("Could not decode base64 ustreamer config", second);
            }
        }
    }

    @Nonnull
    private static String padBase64(@Nonnull final String value) {
        final int padding = (4 - value.length() % 4) % 4;
        final StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < padding; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static int parseInt(@Nullable final String value, final int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longOverride(@Nullable final Long override, final long fallback) {
        return override == null ? fallback : override;
    }
}
