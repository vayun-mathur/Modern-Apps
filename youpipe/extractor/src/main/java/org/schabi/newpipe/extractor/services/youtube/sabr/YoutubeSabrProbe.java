package org.schabi.newpipe.extractor.services.youtube.sabr;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonBuilder;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoToken;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FilterInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YoutubeSabrProbe {
    private static final String PLAYER = "player";
    private static final String STREAMING_DATA = "streamingData";
    private static final String ADAPTIVE_FORMATS = "adaptiveFormats";

    private YoutubeSabrProbe() {
    }

    @Nonnull
    public static YoutubeSabrInfo fetchSabrInfo(@Nonnull final String videoId,
                                                @Nonnull final YoutubeSabrClientProfile profile,
                                                @Nonnull final Localization localization,
                                                @Nonnull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        return fetchSabrInfo(videoId, profile, localization, contentCountry, null, null);
    }

    @Nonnull
    public static YoutubeSabrInfo fetchSabrInfo(@Nonnull final String videoId,
                                                @Nonnull final YoutubeSabrClientProfile profile,
                                                @Nonnull final Localization localization,
                                                @Nonnull final ContentCountry contentCountry,
                                                @Nullable final String playerPoToken,
                                                @Nullable final String visitorDataOverride)
            throws IOException, ExtractionException {
        final PlayerIdentityPair playerIdentity = resolvePlayerIdentity(profile, localization,
                contentCountry, playerPoToken, visitorDataOverride);
        final String cpn = YoutubeParsingHelper.generateContentPlaybackNonce();
        final JsonObject playerResponse = fetchPlayerResponse(videoId, profile, localization,
                contentCountry, cpn, playerIdentity.playerPoToken, playerIdentity.visitorData);
        return fromPlayerResponse(videoId, profile, cpn, playerResponse,
                playerIdentity.visitorData);
    }

    @Nonnull
    static PlayerIdentityPair resolvePlayerIdentity(
            @Nonnull final YoutubeSabrClientProfile profile,
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            @Nullable final String playerPoToken,
            @Nullable final String visitorDataOverride) {
        final boolean hasExplicitPoToken = playerPoToken != null && !playerPoToken.isEmpty();
        final boolean hasExplicitVisitorData = visitorDataOverride != null
                && !visitorDataOverride.isEmpty();
        if (hasExplicitPoToken != hasExplicitVisitorData) {
            throw new IllegalArgumentException(
                    "playerPoToken and visitorDataOverride must be provided together");
        }
        if (hasExplicitPoToken) {
            return new PlayerIdentityPair(playerPoToken, visitorDataOverride);
        }

        final YoutubeSessionPoToken automaticToken = YoutubeParsingHelper.getSessionPoToken(
                profile.getClientName(), localization, contentCountry);
        return automaticToken == null
                ? new PlayerIdentityPair(playerPoToken, visitorDataOverride)
                : new PlayerIdentityPair(automaticToken.getPoToken(),
                        automaticToken.getVisitorData());
    }

    static final class PlayerIdentityPair {
        @Nullable
        final String playerPoToken;
        @Nullable
        final String visitorData;

        private PlayerIdentityPair(@Nullable final String playerPoToken,
                                   @Nullable final String visitorData) {
            this.playerPoToken = playerPoToken;
            this.visitorData = visitorData;
        }
    }

    @Nonnull
    public static YoutubeSabrInfo fromPlayerResponse(@Nonnull final String videoId,
                                                     @Nonnull final YoutubeSabrClientProfile profile,
                                                     @Nonnull final String cpn,
                                                     @Nonnull final JsonObject playerResponse)
            throws ExtractionException {
        return fromPlayerResponse(videoId, profile, cpn, playerResponse, null);
    }

    @Nonnull
    public static YoutubeSabrInfo fromPlayerResponse(@Nonnull final String videoId,
                                                     @Nonnull final YoutubeSabrClientProfile profile,
                                                     @Nonnull final String cpn,
                                                     @Nonnull final JsonObject playerResponse,
                                                     @Nullable final String visitorDataOverride)
            throws ExtractionException {
        final JsonObject streamingData = playerResponse.getObject(STREAMING_DATA);
        if (streamingData == null) {
            throw new SabrProtocolException("Player response has no streamingData for " + profile);
        }

        final String unresolvedServerAbrStreamingUrl =
                streamingData.getString("serverAbrStreamingUrl");
        final String ustreamerConfig = extractVideoPlaybackUstreamerConfig(playerResponse);
        final String visitorData = visitorDataOverride == null || visitorDataOverride.isEmpty()
                ? extractVisitorData(playerResponse)
                : visitorDataOverride;
        final JsonArray adaptiveFormats = streamingData.getArray(ADAPTIVE_FORMATS);
        final List<YoutubeSabrFormat> formats =
                YoutubeSabrFormat.parseAdaptiveFormats(adaptiveFormats);
        final Set<String> signatures = new LinkedHashSet<>();
        final Set<String> nParameters = new LinkedHashSet<>();
        YoutubeSabrFormat.collectDecodeParameters(formats, signatures, nParameters);
        final String serverAbrN = YoutubeSabrFormat.extractNParameter(
                unresolvedServerAbrStreamingUrl);
        if (serverAbrN != null) {
            nParameters.add(serverAbrN);
        }
        String serverAbrStreamingUrl = unresolvedServerAbrStreamingUrl;
        if (!signatures.isEmpty() || !nParameters.isEmpty()) {
            final YoutubeApiDecoder.BatchDecodeResult decoded =
                    YoutubeJavaScriptPlayerManager.deobfuscateBatch(videoId,
                            new ArrayList<>(signatures), new ArrayList<>(nParameters));
            YoutubeSabrFormat.resolveInitializationUrls(formats, decoded);
            serverAbrStreamingUrl = YoutubeSabrFormat.resolveNParameter(
                    unresolvedServerAbrStreamingUrl, decoded);
        }

        return new YoutubeSabrInfo(profile, videoId, cpn, resolveClientVersion(profile),
                visitorData, serverAbrStreamingUrl, ustreamerConfig,
                formats);
    }

    @Nonnull
    public static YoutubeSabrProbeResult probeFirstMediaResponse(
            @Nonnull final String videoId,
            @Nonnull final YoutubeSabrClientProfile profile,
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        final YoutubeSabrInfo info = fetchSabrInfo(videoId, profile, localization, contentCountry);
        return probeFirstMediaResponse(info, localization);
    }

    @Nonnull
    public static YoutubeSabrProbeResult probeFirstMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final YoutubeSabrFormat audioFormat = info.findBestAudioFormat();
        final YoutubeSabrFormat videoFormat = info.findLowestVideoFormat();
        if (audioFormat == null || videoFormat == null) {
            throw new SabrProtocolException("Could not select audio/video SABR formats");
        }
        final String serverAbrStreamingUrl = info.getServerAbrStreamingUrl();
        if (serverAbrStreamingUrl == null || serverAbrStreamingUrl.isEmpty()) {
            throw new SabrProtocolException("Missing serverAbrStreamingUrl");
        }

        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                info, audioFormat, videoFormat);
        return postMediaRequest(info, requestBody, 0, localization);
    }

    @Nonnull
    public static YoutubeSabrProbeResult probeFirstMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return probeFirstMediaResponse(info, audioFormat, videoFormat, null, localization);
    }

    @Nonnull
    public static YoutubeSabrProbeResult probeFirstMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return probeFirstMediaResponse(info, audioFormat, videoFormat, streamState, null,
                localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFirstMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, 0, serverAbrStreamingUrlOverride, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFirstMediaResponseStreaming(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.SegmentConsumer segmentConsumer,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, 0, serverAbrStreamingUrlOverride,
                keepStreaming(segmentConsumer), localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFirstMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return probeFirstMediaResponseStreamingUntil(info, audioFormat, videoFormat, streamState,
                serverAbrStreamingUrlOverride, segmentConsumer, null, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFirstMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, 0, serverAbrStreamingUrlOverride,
                segmentConsumer, segmentSpoolDirectory, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFirstMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nullable final YoutubeSabrStreamState streamState,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nonnull final SabrStreamingResponseReader.SegmentConsumer segmentStartConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFirstMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, 0, serverAbrStreamingUrlOverride,
                segmentConsumer, segmentStartConsumer, segmentSpoolDirectory, localization);
    }

    @Nonnull
    public static YoutubeSabrProbeResult probeFollowUpMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (requestNumber <= 0) {
            throw new SabrProtocolException("Follow-up request number must be positive");
        }
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, requestNumber, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFollowUpMediaResponse(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (requestNumber <= 0) {
            throw new SabrProtocolException("Follow-up request number must be positive");
        }
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFollowUpMediaResponseStreaming(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.SegmentConsumer segmentConsumer,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (requestNumber <= 0) {
            throw new SabrProtocolException("Follow-up request number must be positive");
        }
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                keepStreaming(segmentConsumer), localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFollowUpMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return probeFollowUpMediaResponseStreamingUntil(info, audioFormat, videoFormat, streamState,
                requestNumber, serverAbrStreamingUrlOverride, segmentConsumer, null, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFollowUpMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (requestNumber <= 0) {
            throw new SabrProtocolException("Follow-up request number must be positive");
        }
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                segmentConsumer, segmentSpoolDirectory, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult probeFollowUpMediaResponseStreamingUntil(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final YoutubeSabrFormat audioFormat,
            @Nonnull final YoutubeSabrFormat videoFormat,
            @Nonnull final YoutubeSabrStreamState streamState,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nonnull final SabrStreamingResponseReader.SegmentConsumer segmentStartConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        if (requestNumber <= 0) {
            throw new SabrProtocolException("Follow-up request number must be positive");
        }
        final byte[] requestBody = YoutubeSabrRequestBuilder.buildFollowUpMediaRequest(
                info, audioFormat, videoFormat, streamState);
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                segmentConsumer, segmentStartConsumer, segmentSpoolDirectory, localization);
    }

    @Nonnull
    private static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return postMediaRequest(info, requestBody, requestNumber, null, null, localization);
    }

    @Nonnull
    private static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                null, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nullable final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                segmentConsumer, null, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nullable final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                segmentConsumer, null, segmentSpoolDirectory, localization);
    }

    @Nonnull
    static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nullable final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final SabrStreamingResponseReader.SegmentConsumer segmentStartConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        return postMediaRequest(info, requestBody, requestNumber, serverAbrStreamingUrlOverride,
                segmentConsumer, segmentStartConsumer, segmentSpoolDirectory, localization,
                SabrMediaProtocol.builtin());
    }

    @Nonnull
    static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nullable final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final SabrStreamingResponseReader.SegmentConsumer segmentStartConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization,
            @Nonnull final SabrMediaProtocol mediaProtocol)
            throws IOException, ExtractionException {
        final String serverAbrStreamingUrl = serverAbrStreamingUrlOverride == null
                || serverAbrStreamingUrlOverride.isEmpty()
                ? info.getServerAbrStreamingUrl()
                : serverAbrStreamingUrlOverride;
        if (serverAbrStreamingUrl == null || serverAbrStreamingUrl.isEmpty()) {
            throw new SabrProtocolException("Missing serverAbrStreamingUrl");
        }

        // Stream the response instead of buffering the whole body: a 4K media batch can be
        // 50-150MB, and reading it into one byte[] (+ the parts copy) OOM'd the 512MB heap. The
        // streaming reader parses parts one at a time and assembles segments on the fly.
        final long requestStartNs = System.nanoTime();
        final long[] firstSegmentElapsedMs = {-1};
        final SabrStreamingResponseReader.StoppableSegmentConsumer timedConsumer =
                segmentConsumer == null ? null : segment -> {
                    if (firstSegmentElapsedMs[0] < 0) {
                        firstSegmentElapsedMs[0] = elapsedMs(requestStartNs);
                    }
                    return segmentConsumer.accept(segment);
                };
        final SabrStreamingResponseReader.SegmentConsumer timedStartConsumer =
                segmentStartConsumer == null ? null : segment -> {
                    if (firstSegmentElapsedMs[0] < 0) {
                        firstSegmentElapsedMs[0] = elapsedMs(requestStartNs);
                    }
                    segmentStartConsumer.accept(segment);
                };
        try (StreamingResponse response = NewPipe.getDownloader().postStreaming(
                withSabrSessionParameters(serverAbrStreamingUrl, info.getCpn(), requestNumber),
                buildSabrHeaders(info), requestBody, localization)) {
            final String contentType = response.getHeader("Content-Type");
            if (contentType == null
                    || !contentType.toLowerCase().contains("application/vnd.yt-ump")) {
                throw new SabrProtocolException("Expected UMP response, got content type: "
                        + contentType + ", status=" + response.responseCode());
            }
            final CountingInputStream body = new CountingInputStream(response.body());
            final SabrStreamingResponseReader.Result streamed =
                    timedConsumer == null && timedStartConsumer == null
                            ? SabrStreamingResponseReader.readUntil(body, null, null, null,
                            mediaProtocol)
                            : SabrStreamingResponseReader.readUntil(body, timedConsumer,
                                    timedStartConsumer, segmentSpoolDirectory, mediaProtocol);
            final long requestElapsedMs = elapsedMs(requestStartNs);
            return new YoutubeSabrProbeResult(info, streamed.getDecodedResponse(),
                    streamed.getSegments(), streamed.getSegmentCount(), response.responseCode(),
                    contentType, body.getCount(), streamed.getMediaPayloadBytes(),
                    streamed.getMediaPartPayloadBytes(), streamed.getControlPayloadBytes(),
                    streamed.getTotalPayloadBytes(), streamed.getMaxPartBytes(),
                    streamed.getMaxMediaPartPayloadBytes(), streamed.getMaxSegmentBytes(),
                    requestElapsedMs, firstSegmentElapsedMs[0]);
        }
    }

    private static long elapsedMs(final long startNs) {
        return Math.max(0, (System.nanoTime() - startNs) / 1_000_000L);
    }

    @Nonnull
    private static SabrStreamingResponseReader.StoppableSegmentConsumer keepStreaming(
            @Nonnull final SabrStreamingResponseReader.SegmentConsumer consumer) {
        return segment -> {
            consumer.accept(segment);
            return true;
        };
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(@Nonnull final InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            final int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(@Nonnull final byte[] buffer, final int offset, final int length)
                throws IOException {
            final int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        private long getCount() {
            return count;
        }
    }

    @Nonnull
    private static JsonObject fetchPlayerResponse(@Nonnull final String videoId,
                                                   @Nonnull final YoutubeSabrClientProfile profile,
                                                   @Nonnull final Localization localization,
                                                   @Nonnull final ContentCountry contentCountry,
                                                   @Nonnull final String cpn,
                                                   @Nullable final String playerPoToken,
                                                   @Nullable final String visitorDataOverride)
            throws IOException, ExtractionException {
        final byte[] body = createPlayerBody(videoId, profile, localization, contentCountry,
                cpn, playerPoToken, visitorDataOverride);
        final String url = getInnertubeBaseUrl(profile) + PLAYER + "?"
                + YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER;
        final Response response = NewPipe.getDownloader().post(url,
                buildPlayerHeaders(profile, visitorDataOverride),
                body, localization);
        return JsonUtils.toJsonObject(YoutubeParsingHelper.getValidJsonResponseBody(response));
    }

    @Nonnull
    private static byte[] createPlayerBody(@Nonnull final String videoId,
                                            @Nonnull final YoutubeSabrClientProfile profile,
                                            @Nonnull final Localization localization,
                                            @Nonnull final ContentCountry contentCountry,
                                            @Nonnull final String cpn,
                                            @Nullable final String playerPoToken,
                                            @Nullable final String visitorDataOverride)
            throws ParsingException {
        final JsonBuilder<JsonObject> builder = JsonObject.builder()
                .object("context")
                    .object("client")
                        .value("clientName", profile.getClientName())
                        .value("clientVersion", resolveClientVersion(profile))
                        .value("hl", localization.getLocalizationCode())
                        .value("gl", contentCountry.getCountryCode())
                        .value("utcOffsetMinutes", 0);

        if (visitorDataOverride != null && !visitorDataOverride.isEmpty()) {
            builder.value("visitorData", visitorDataOverride);
        }

        if (profile == YoutubeSabrClientProfile.WEB) {
            builder.value("platform", "DESKTOP");
        } else if (profile == YoutubeSabrClientProfile.TVHTML5) {
            builder.value("platform", "GAME_CONSOLE");
        } else {
            builder.value("platform", "MOBILE");
        }
        if (profile.getOsName() != null) {
            builder.value("osName", profile.getOsName());
        }
        if (profile.getOsVersion() != null) {
            builder.value("osVersion", profile.getOsVersion());
        }
        if (profile == YoutubeSabrClientProfile.MWEB && profile.getUserAgent() != null) {
            builder.value("userAgent", profile.getUserAgent());
        }
        if (profile == YoutubeSabrClientProfile.ANDROID) {
            builder.value("clientScreen", "WATCH")
                    .value("androidSdkVersion", 36);
        } else if (profile == YoutubeSabrClientProfile.ANDROID_VR) {
            builder.value("clientScreen", "WATCH")
                    .value("deviceMake", "Oculus")
                    .value("deviceModel", "Quest 3")
                    .value("androidSdkVersion", 32);
        } else if (profile == YoutubeSabrClientProfile.IOS) {
            builder.value("clientScreen", "WATCH")
                    .value("deviceMake", "Apple")
                    .value("deviceModel", "iPhone16,2");
        } else if (profile == YoutubeSabrClientProfile.WEB_EMBEDDED) {
            builder.value("clientScreen", "EMBED");
        }

        builder.end()
                    .object("request")
                        .array("internalExperimentFlags")
                        .end()
                        .value("useSsl", true)
                    .end()
                    .object("user")
                        .value("lockedSafetyMode", false)
                    .end()
                .end()
                .object("playbackContext")
                    .object("contentPlaybackContext")
                        .value("referer", "https://www.youtube.com/watch?v=" + videoId)
                        .value("vis", 0)
                        .value("splay", false)
                        .value("lactMilliseconds", "-1")
                        .value("signatureTimestamp",
                                YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId))
                        .value("html5Preference", "HTML5_PREF_WANTS")
                    .end()
                .end()
                .value(YoutubeParsingHelper.CPN, cpn)
                .value(YoutubeParsingHelper.VIDEO_ID, videoId)
                .value(YoutubeParsingHelper.CONTENT_CHECK_OK, true)
                .value(YoutubeParsingHelper.RACY_CHECK_OK, true);

        if (playerPoToken != null && !playerPoToken.isEmpty()) {
            builder.object("serviceIntegrityDimensions")
                    .value("poToken", playerPoToken)
                    .end();
        }

        return JsonWriter.string(builder.done()).getBytes(StandardCharsets.UTF_8);
    }

    @Nonnull
    private static String getInnertubeBaseUrl(@Nonnull final YoutubeSabrClientProfile profile) {
        if (profile == YoutubeSabrClientProfile.ANDROID
                || profile == YoutubeSabrClientProfile.ANDROID_VR
                || profile == YoutubeSabrClientProfile.IOS) {
            return YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL;
        }
        return YoutubeParsingHelper.YOUTUBEI_V1_URL;
    }

    @Nonnull
    private static Map<String, List<String>> buildPlayerHeaders(
            @Nonnull final YoutubeSabrClientProfile profile) throws IOException, ExtractionException {
        return buildPlayerHeaders(profile, null);
    }

    @Nonnull
    private static Map<String, List<String>> buildPlayerHeaders(
            @Nonnull final YoutubeSabrClientProfile profile,
            @Nullable final String visitorDataOverride) throws IOException, ExtractionException {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        if (visitorDataOverride != null && !visitorDataOverride.isEmpty()) {
            headers.put("X-Goog-Visitor-Id", Collections.singletonList(visitorDataOverride));
        }
        if (profile.getUserAgent() != null) {
            headers.put("User-Agent", Collections.singletonList(profile.getUserAgent()));
        }
        if (profile == YoutubeSabrClientProfile.ANDROID
                || profile == YoutubeSabrClientProfile.ANDROID_VR
                || profile == YoutubeSabrClientProfile.IOS) {
            headers.put("X-Goog-Api-Format-Version", Collections.singletonList("2"));
        } else {
            headers.put("Origin", Collections.singletonList("https://www.youtube.com"));
            headers.put("Referer", Collections.singletonList("https://www.youtube.com"));
            headers.put("X-YouTube-Client-Name", Collections.singletonList(profile.getClientId()));
            headers.put("X-YouTube-Client-Version",
                    Collections.singletonList(resolveClientVersion(profile)));
            YoutubeParsingHelper.addLoggedInHeaders(headers);
            if (!headers.containsKey("Cookie")) {
                YoutubeParsingHelper.addCookieHeader(headers);
            }
        }
        return headers;
    }

    @Nonnull
    private static Map<String, List<String>> buildSabrHeaders(@Nonnull final YoutubeSabrInfo info) {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/x-protobuf"));
        headers.put("Accept", Collections.singletonList("application/vnd.yt-ump"));
        headers.put("Accept-Encoding", Collections.singletonList("identity"));
        if (info.getProfile().getUserAgent() != null) {
            headers.put("User-Agent", Collections.singletonList(info.getProfile().getUserAgent()));
        }
        if (!isWebSabrProfile(info.getProfile())
                && info.getVisitorData() != null && !info.getVisitorData().isEmpty()) {
            headers.put("X-Goog-Visitor-Id", Collections.singletonList(info.getVisitorData()));
        }
        if (isWebSabrProfile(info.getProfile())) {
            headers.remove("Content-Type");
            headers.remove("Accept-Encoding");
            headers.put("Accept", Collections.singletonList("*/*"));
            headers.put("Accept-Language", Collections.singletonList("en-US,en;q=0.9"));
            headers.put("Origin", Collections.singletonList("https://www.youtube.com"));
            headers.put("Referer", Collections.singletonList("https://www.youtube.com/"));
        }
        return headers;
    }

    private static boolean isWebSabrProfile(@Nonnull final YoutubeSabrClientProfile profile) {
        return profile.isWebLike()
                || profile == YoutubeSabrClientProfile.WEB;
    }

    @Nonnull
    private static String withSabrSessionParameters(@Nonnull final String url,
                                                    @Nonnull final String cpn,
                                                    final int requestNumber) {
        String result = appendQueryParameterIfMissing(url, "alr", "yes");
        result = appendQueryParameterIfMissing(result, "cpn", cpn);
        return setQueryParameter(result, "rn", String.valueOf(requestNumber + 1));
    }

    @Nonnull
    private static String appendQueryParameterIfMissing(@Nonnull final String url,
                                                        @Nonnull final String name,
                                                        @Nonnull final String value) {
        if (url.contains("?" + name + "=") || url.contains("&" + name + "=")) {
            return url;
        }
        return appendQueryParameter(url, name, value);
    }

    @Nonnull
    private static String appendQueryParameter(@Nonnull final String url,
                                               @Nonnull final String name,
                                               @Nonnull final String value) {
        final String separator = url.contains("?") ? "&" : "?";
        return url + separator + name + "=" + value;
    }

    @Nonnull
    private static String setQueryParameter(@Nonnull final String url,
                                            @Nonnull final String name,
                                            @Nonnull final String value) {
        final int fragmentIndex = url.indexOf('#');
        final String baseUrl = fragmentIndex < 0 ? url : url.substring(0, fragmentIndex);
        final String fragment = fragmentIndex < 0 ? "" : url.substring(fragmentIndex);
        final int queryIndex = baseUrl.indexOf('?');
        final String path = queryIndex < 0 ? baseUrl : baseUrl.substring(0, queryIndex);
        final String query = queryIndex < 0 ? "" : baseUrl.substring(queryIndex + 1);

        final StringBuilder result = new StringBuilder(path).append('?');
        boolean wroteParameter = false;
        for (final String parameter : query.split("&", -1)) {
            if (parameter.isEmpty()) {
                continue;
            }
            final int equalsIndex = parameter.indexOf('=');
            final String parameterName = equalsIndex < 0
                    ? parameter
                    : parameter.substring(0, equalsIndex);
            if (parameterName.equals(name)) {
                continue;
            }
            if (wroteParameter) {
                result.append('&');
            }
            result.append(parameter);
            wroteParameter = true;
        }
        if (wroteParameter) {
            result.append('&');
        }
        return result.append(name).append('=').append(value).append(fragment).toString();
    }

    @Nonnull
    private static String resolveClientVersion(@Nonnull final YoutubeSabrClientProfile profile)
            throws ParsingException {
        if (profile == YoutubeSabrClientProfile.WEB
                || profile == YoutubeSabrClientProfile.MWEB) {
            try {
                return YoutubeParsingHelper.getClientVersion();
            } catch (final Exception e) {
                return profile.getClientVersion();
            }
        }
        return profile.getClientVersion();
    }

    @Nullable
    private static String extractVisitorData(@Nonnull final JsonObject response) {
        final JsonObject responseContext = response.getObject("responseContext");
        return responseContext == null ? null : responseContext.getString("visitorData");
    }

    @Nullable
    private static String extractVideoPlaybackUstreamerConfig(@Nonnull final JsonObject response) {
        JsonObject current = response.getObject("playerConfig");
        if (current == null) {
            return null;
        }
        current = current.getObject("mediaCommonConfig");
        if (current == null) {
            return null;
        }
        current = current.getObject("mediaUstreamerRequestConfig");
        if (current == null) {
            return null;
        }
        return current.getString("videoPlaybackUstreamerConfig");
    }

}
