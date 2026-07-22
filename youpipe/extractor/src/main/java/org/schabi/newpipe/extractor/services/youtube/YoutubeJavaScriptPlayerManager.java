package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.Parser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manage the extraction and the usage of YouTube's player JavaScript needed data in the YouTube
 * service.
 *
 * <p>
 * YouTube restrict streaming their media in multiple ways by requiring their HTML5 clients to use
 * a signature timestamp, and on streaming URLs a signature deobfuscation function for some
 * contents and a throttling parameter deobfuscation one for all contents.
 * </p>
 *
 * <p>
 * This class provides access to methods which allows to get base JavaScript player's signature
 * timestamp and to deobfuscate streaming URLs' signature and/or throttling parameter of HTML5
 * clients using the PipePipe API.
 * </p>
 */
public final class YoutubeJavaScriptPlayerManager {

    private static final Pattern THROTTLING_PARAM_PATTERN = Pattern.compile("[&?]n=([^&]+)");

    private static final String LATEST_PLAYER_URL =
            "https://api.pipepipe.dev/decoder/latest-player";
    private static final String USER_AGENT = "PipePipe/4.9.0";
    private static final long PLAYER_METADATA_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    @Nullable
    private static PlayerMetadata playerMetadata;

    private YoutubeJavaScriptPlayerManager() {
    }

    /**
     * Get the signature timestamp of the base JavaScript player file.
     *
     * <p>
     * A valid signature timestamp sent in the payload of player InnerTube requests is required to
     * get valid stream URLs on HTML5 clients for videos which have obfuscated signatures.
     * </p>
     *
     * <p>
     * The signature timestamp is loaded together with the player ID from the decoder API.
     * </p>
     *
     * <p>
     * The metadata is reused for up to 24 hours before being refreshed from the API.
     * </p>
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @return the signature timestamp of the base JavaScript player file
     * @throws ParsingException if the extraction of the signature timestamp failed
     */
    @Nonnull
    public static Integer getSignatureTimestamp(@Nonnull final String videoId)
            throws ParsingException {
        final long startedAtNanos = System.nanoTime();
        final int signatureTimestamp = getPlayerMetadata(videoId).signatureTimestamp;
        logPerformance(videoId, "ejs.signatureTimestamp", startedAtNanos);
        return signatureTimestamp;
    }

    /**
     * Deobfuscate a signature of a streaming URL using the PipePipe API.
     *
     * <p>
     * Obfuscated signatures are only present on streaming URLs of some videos with HTML5 clients.
     * </p>
     *
     * @param videoId             the video ID used to get the JavaScript base player ID (an
     *                            empty one can be passed, even it is not recommend in order to
     *                            spoof better official YouTube clients)
     * @param obfuscatedSignature the obfuscated signature of a streaming URL
     * @return the deobfuscated signature
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @Nonnull
    public static String deobfuscateSignature(@Nonnull final String videoId,
                                              @Nonnull final String obfuscatedSignature)
            throws ParsingException {
        return YoutubeApiDecoder.decodeSignature(
                getPlayerMetadata(videoId).playerId, obfuscatedSignature);
    }

    /**
     * Return a streaming URL with the throttling parameter of a given one deobfuscated, if it is
     * present, using the PipePipe API.
     *
     * <p>
     * The throttling parameter is present on all streaming URLs of HTML5 clients.
     * </p>
     *
     * <p>
     * If it is not given or deobfuscated, speeds will be throttled to a very slow speed (around 50
     * KB/s) and some streaming URLs could even lead to invalid HTTP responses such a 403 one.
     * </p>
     *
     * @param videoId      the video ID used to get the JavaScript base player ID (an empty one
     *                     can be passed, even it is not recommend in order to spoof better
     *                     official YouTube clients)
     * @param streamingUrl a streaming URL
     * @return the original streaming URL if it has no throttling parameter or a URL with a
     * deobfuscated throttling parameter
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @Nonnull
    public static String getUrlWithThrottlingParameterDeobfuscated(
            @Nonnull final String videoId,
            @Nonnull final String streamingUrl) throws ParsingException {
        final String obfuscatedThrottlingParameter =
                getThrottlingParameterFromStreamingUrl(streamingUrl);
        // If the throttling parameter is not present, return the original streaming URL
        if (obfuscatedThrottlingParameter == null) {
            return streamingUrl;
        }

        final PlayerMetadata metadata = getPlayerMetadata(videoId);

        final String deobfuscatedThrottlingParameter = YoutubeApiDecoder.decodeThrottlingParameter(
                metadata.playerId, obfuscatedThrottlingParameter);

        return streamingUrl.replace(
                obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter);
    }

    /**
     * Clear the cached player metadata.
     *
     * <p>
     * The next access will fetch a fresh player ID and signature timestamp from the API.
     * </p>
     */
    public static void clearAllCaches() {
        playerMetadata = null;
        YoutubeApiDecoder.clearCache();
    }

    public static void clearThrottlingParametersCache() {
        YoutubeApiDecoder.clearCache();
    }

    public static int getThrottlingParametersCacheSize() {
        return YoutubeApiDecoder.getCacheSize();
    }

    @Nullable
    public static String getThrottlingParameterFromStreamingUrl(
            @Nonnull final String streamingUrl) {
        try {
            return Parser.matchGroup1(THROTTLING_PARAM_PATTERN, streamingUrl);
        } catch (final Parser.RegexException e) {
            return null;
        }
    }

    /**
     * Batch deobfuscate multiple signatures and throttling parameters in a single API call.
     *
     * <p>
     * This method is more efficient than calling {@link #deobfuscateSignature(String, String)}
     * and {@link #getUrlWithThrottlingParameterDeobfuscated(String, String)} individually for
     * each stream, as it combines all parameters into a single API request.
     * </p>
     *
     * @param videoId          the video ID used to get the JavaScript base player ID
     * @param signatures       list of obfuscated signatures to decode (can be null or empty)
     * @param throttlingParams list of obfuscated throttling parameters to decode (can be null or empty)
     * @return a BatchDecodeResult containing decoded signatures and throttling parameters
     * @throws ParsingException if the extraction of the player ID or the API call failed
     */
    @Nonnull
    public static YoutubeApiDecoder.BatchDecodeResult deobfuscateBatch(
            @Nonnull final String videoId,
            @Nullable final List<String> signatures,
            @Nullable final List<String> throttlingParams) throws ParsingException {
        final String playerId = getPlayerMetadata(videoId).playerId;
        final long startedAtNanos = System.nanoTime();
        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                playerId, signatures, throttlingParams);
        logPerformanceIfSlow(videoId, "ejs.batch.decode", startedAtNanos);
        return result;
    }

    /**
     * Load player metadata from memory or refresh it from the decoder API.
     *
     * @param videoId unused, kept to avoid changing public call sites
     * @throws ParsingException if loading the player metadata failed
     */
    @Nonnull
    private static PlayerMetadata getPlayerMetadata(@Nonnull final String videoId)
            throws ParsingException {
        final PlayerMetadata currentMetadata = playerMetadata;
        if (currentMetadata != null && !currentMetadata.isExpired()) {
            return currentMetadata;
        }

        final long startedAtNanos = System.nanoTime();
        final YoutubeJavaScriptDecoder decoder = YoutubeApiDecoder.getLocalDecoder();
        if (decoder != null) {
            final YoutubeJavaScriptDecoder.PlayerData data = decoder.getPlayerData(videoId);
            playerMetadata = new PlayerMetadata(data.getPlayerId(), data.getSignatureTimestamp(),
                    System.currentTimeMillis() + PLAYER_METADATA_TTL_MILLIS);
            logPerformance(videoId, "ejs.playerMetadata.local", startedAtNanos);
            return playerMetadata;
        }

        playerMetadata = fetchLatestPlayerMetadata();
        logPerformance(videoId, "ejs.playerMetadata.remoteFallback", startedAtNanos);
        return playerMetadata;
    }

    private static void logPerformance(@Nonnull final String videoId,
                                       @Nonnull final String stage,
                                       final long startedAtNanos) {
        System.out.println("YT_PERF videoId=" + videoId + " stage=" + stage
                + " durationMs="
                + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAtNanos));
    }

    private static void logPerformanceIfSlow(@Nonnull final String videoId,
                                             @Nonnull final String stage,
                                             final long startedAtNanos) {
        final long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos);
        if (durationMs >= 5) {
            System.out.println("YT_PERF videoId=" + videoId + " stage=" + stage
                    + " durationMs=" + durationMs);
        }
    }

    @Nonnull
    private static PlayerMetadata fetchLatestPlayerMetadata() throws ParsingException {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));

        try {
            final Response response = NewPipe.getDownloader().get(
                    LATEST_PLAYER_URL, headers, Localization.DEFAULT);
            final JsonObject responseJson = JsonParser.object().from(response.responseBody());

            final String playerId = responseJson.getString("player", "");
            if (playerId.isEmpty()) {
                throw new ParsingException("latest-player response missing player");
            }

            if (!responseJson.has("signatureTimestamp")) {
                throw new ParsingException("latest-player response missing signatureTimestamp");
            }

            final int signatureTimestamp = responseJson.getInt("signatureTimestamp");
            return new PlayerMetadata(playerId, signatureTimestamp,
                    System.currentTimeMillis() + PLAYER_METADATA_TTL_MILLIS);
        } catch (final IOException e) {
            throw new ParsingException("Failed to fetch latest player metadata", e);
        } catch (final ReCaptchaException e) {
            throw new ParsingException("Failed to fetch latest player metadata", e);
        } catch (final JsonParserException e) {
            throw new ParsingException("Failed to parse latest player metadata", e);
        }
    }

    private static final class PlayerMetadata {
        @Nonnull
        private final String playerId;
        private final int signatureTimestamp;
        private final long expiresAt;

        private PlayerMetadata(@Nonnull final String playerId,
                               final int signatureTimestamp,
                               final long expiresAt) {
            this.playerId = playerId;
            this.signatureTimestamp = signatureTimestamp;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
