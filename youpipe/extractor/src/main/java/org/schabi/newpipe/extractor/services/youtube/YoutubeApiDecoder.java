package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoder for YouTube signature and throttling parameters using the PipePipe API.
 *
 * <p>
 * This class replaces the local JavaScript-based decoding with API calls to
 * https://api.pipepipe.dev/decoder/decode
 * </p>
 */
public final class YoutubeApiDecoder {

    private static final String API_BASE_URL = "https://api.pipepipe.dev/decoder/decode";
    private static final String USER_AGENT = "PipePipe/4.9.0";

    @Nonnull
    private static final Map<String, String> DECODE_CACHE = new HashMap<>();

    @Nullable
    private static volatile YoutubeJavaScriptDecoder localDecoder;

    private YoutubeApiDecoder() {
    }

    /**
     * Decode a signature parameter using the PipePipe API.
     *
     * @param playerId  the YouTube player ID (8-character hash)
     * @param signature the obfuscated signature to decode
     * @return the deobfuscated signature
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static String decodeSignature(@Nonnull final String playerId,
                                  @Nonnull final String signature) throws ParsingException {
        return decode(playerId, "sig", signature);
    }

    /**
     * Decode a throttling parameter (n parameter) using the PipePipe API.
     *
     * @param playerId          the YouTube player ID (8-character hash)
     * @param nParameter        the obfuscated n parameter to decode
     * @return the deobfuscated n parameter
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static String decodeThrottlingParameter(@Nonnull final String playerId,
                                            @Nonnull final String nParameter)
            throws ParsingException {
        return decode(playerId, "n", nParameter);
    }

    /**
     * Generic decode method that calls the PipePipe API.
     *
     * @param playerId   the YouTube player ID (8-character hash)
     * @param paramType  the parameter type ("sig" or "n")
     * @param value      the obfuscated value to decode
     * @return the deobfuscated value
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    private static String decode(@Nonnull final String playerId,
                                 @Nonnull final String paramType,
                                 @Nonnull final String value) throws ParsingException {
        final String cacheKey = playerId + ":" + paramType + ":" + value;
        final String cachedResult = DECODE_CACHE.get(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }

        final YoutubeJavaScriptDecoder decoder = localDecoder;
        if (decoder != null) {
            try {
                final BatchDecodeResult result = decoder.decodeBatch(playerId,
                        "sig".equals(paramType) ? Collections.singletonList(value) : null,
                        "n".equals(paramType) ? Collections.singletonList(value) : null);
                final String decodedValue = "sig".equals(paramType)
                        ? result.getSignatures().get(value) : result.getNParameters().get(value);
                if (decodedValue == null || decodedValue.isEmpty()) {
                    throw new ParsingException("Local decoder returned empty value for: " + value);
                }
                DECODE_CACHE.put(cacheKey, decodedValue);
                return decodedValue;
            } catch (final Exception localFailure) {
                disableLocalDecoder(decoder);
            }
        }

        try {
            final String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
            final String url = API_BASE_URL + "?player=" + playerId + "&" + paramType + "=" + encodedValue;

            final Map<String, java.util.List<String>> headers = new HashMap<>();
            headers.put("User-Agent", Collections.singletonList(USER_AGENT));

            final Response response = NewPipe.getDownloader().get(url, headers, Localization.DEFAULT);
            final String responseBody = response.responseBody();
            final JsonObject jsonResponse = JsonParser.object().from(responseBody);

            if (!"result".equals(jsonResponse.getString("type"))) {
                throw new ParsingException("API returned unexpected type: " + jsonResponse.getString("type"));
            }

            final JsonObject firstResponse = jsonResponse.getArray("responses").getObject(0);
            if (!"result".equals(firstResponse.getString("type"))) {
                throw new ParsingException("API response item has unexpected type: " + firstResponse.getString("type"));
            }

            final JsonObject data = firstResponse.getObject("data");
            final String decodedValue = data.getString(value);

            if (decodedValue == null || decodedValue.isEmpty()) {
                throw new ParsingException("API returned empty decoded value for: " + value);
            }

            DECODE_CACHE.put(cacheKey, decodedValue);
            return decodedValue;
        } catch (final IOException e) {
            throw new ParsingException("Failed to call decode API", e);
        } catch (final JsonParserException e) {
            throw new ParsingException("Failed to parse API response", e);
        } catch (final Exception e) {
            throw new ParsingException("Unexpected error during decoding", e);
        }
    }

    static void clearCache() {
        DECODE_CACHE.clear();
    }

    private static void disableLocalDecoder(@Nonnull final YoutubeJavaScriptDecoder decoder) {
        if (localDecoder == decoder) {
            localDecoder = null;
            clearCache();
        }
    }

    public static void setLocalDecoder(@Nullable final YoutubeJavaScriptDecoder decoder) {
        localDecoder = decoder;
        clearCache();
    }

    @Nullable
    static YoutubeJavaScriptDecoder getLocalDecoder() {
        return localDecoder;
    }

    static int getCacheSize() {
        return DECODE_CACHE.size();
    }

    /**
     * Batch decode multiple signatures and throttling parameters in a single API call.
     *
     * @param playerId        the YouTube player ID (8-character hash)
     * @param signatureParams list of obfuscated signatures to decode (can be null or empty)
     * @param nParams         list of obfuscated n parameters to decode (can be null or empty)
     * @return a BatchDecodeResult containing the decoded values
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static BatchDecodeResult decodeBatch(@Nonnull final String playerId,
                                         @Nullable final List<String> signatureParams,
                                         @Nullable final List<String> nParams)
            throws ParsingException {
        final YoutubeJavaScriptDecoder decoder = localDecoder;
        if (decoder != null) {
            try {
                return decoder.decodeBatch(playerId, signatureParams, nParams);
            } catch (final Exception localFailure) {
                disableLocalDecoder(decoder);
            }
        }
        // Validate input
        final boolean hasSigs = signatureParams != null && !signatureParams.isEmpty();
        final boolean hasNs = nParams != null && !nParams.isEmpty();

        if (!hasSigs && !hasNs) {
            return new BatchDecodeResult(new HashMap<>(), new HashMap<>());
        }

        final Map<String, String> sigResults = new HashMap<>();
        final Map<String, String> nResults = new HashMap<>();
        final List<String> uncachedSigs = new java.util.ArrayList<>();
        final List<String> uncachedNs = new java.util.ArrayList<>();

        if (hasSigs) {
            for (final String sig : signatureParams) {
                final String cachedResult = DECODE_CACHE.get(playerId + ":sig:" + sig);
                if (cachedResult != null) {
                    sigResults.put(sig, cachedResult);
                } else {
                    uncachedSigs.add(sig);
                }
            }
        }

        if (hasNs) {
            for (final String n : nParams) {
                final String cachedResult = DECODE_CACHE.get(playerId + ":n:" + n);
                if (cachedResult != null) {
                    nResults.put(n, cachedResult);
                } else {
                    uncachedNs.add(n);
                }
            }
        }

        if (uncachedSigs.isEmpty() && uncachedNs.isEmpty()) {
            return new BatchDecodeResult(sigResults, nResults);
        }

        try {
            final StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
            urlBuilder.append("?player=").append(playerId);

            if (!uncachedNs.isEmpty()) {
                urlBuilder.append("&n=");
                for (int i = 0; i < uncachedNs.size(); i++) {
                    if (i > 0) {
                        urlBuilder.append(',');
                    }
                    urlBuilder.append(URLEncoder.encode(uncachedNs.get(i), StandardCharsets.UTF_8.name()));
                }
            }

            if (!uncachedSigs.isEmpty()) {
                urlBuilder.append("&sig=");
                for (int i = 0; i < uncachedSigs.size(); i++) {
                    if (i > 0) {
                        urlBuilder.append(',');
                    }
                    urlBuilder.append(URLEncoder.encode(uncachedSigs.get(i), StandardCharsets.UTF_8.name()));
                }
            }

            final Map<String, java.util.List<String>> headers = new HashMap<>();
            headers.put("User-Agent", Collections.singletonList(USER_AGENT));

            final Response response = NewPipe.getDownloader().get(urlBuilder.toString(), headers, Localization.DEFAULT);
            final String responseBody = response.responseBody();
            final JsonObject jsonResponse = JsonParser.object().from(responseBody);

            if (!"result".equals(jsonResponse.getString("type"))) {
                throw new ParsingException("API returned unexpected type: " + jsonResponse.getString("type"));
            }

            final JsonArray responses = jsonResponse.getArray("responses");

            int responseIndex = 0;
            if (!uncachedNs.isEmpty()) {
                final JsonObject nResponse = responses.getObject(responseIndex++);
                if (!"result".equals(nResponse.getString("type"))) {
                    throw new ParsingException("N parameter response has unexpected type: " + nResponse.getString("type"));
                }

                final JsonObject nData = nResponse.getObject("data");
                for (final String nParam : uncachedNs) {
                    final String decodedValue = nData.getString(nParam);
                    if (decodedValue == null || decodedValue.isEmpty()) {
                        throw new ParsingException("API returned empty decoded value for n parameter: " + nParam);
                    }
                    nResults.put(nParam, decodedValue);
                    DECODE_CACHE.put(playerId + ":n:" + nParam, decodedValue);
                }
            }

            if (!uncachedSigs.isEmpty()) {
                final JsonObject sigResponse = responses.getObject(responseIndex);
                if (!"result".equals(sigResponse.getString("type"))) {
                    throw new ParsingException("Signature response has unexpected type: " + sigResponse.getString("type"));
                }

                final JsonObject sigData = sigResponse.getObject("data");
                for (final String sig : uncachedSigs) {
                    final String decodedValue = sigData.getString(sig);
                    if (decodedValue == null || decodedValue.isEmpty()) {
                        throw new ParsingException("API returned empty decoded value for signature: " + sig);
                    }
                    sigResults.put(sig, decodedValue);
                    DECODE_CACHE.put(playerId + ":sig:" + sig, decodedValue);
                }
            }

            return new BatchDecodeResult(sigResults, nResults);
        } catch (final IOException e) {
            throw new ParsingException("Failed to call batch decode API", e);
        } catch (final JsonParserException e) {
            throw new ParsingException("Failed to parse batch API response", e);
        } catch (final Exception e) {
            throw new ParsingException("Unexpected error during batch decoding", e);
        }
    }

    /**
     * Result class for batch decode operations.
     */
    public static class BatchDecodeResult {
        private final Map<String, String> signatures;
        private final Map<String, String> nParameters;

        public BatchDecodeResult(@Nonnull final Map<String, String> signatures,
                                 @Nonnull final Map<String, String> nParameters) {
            this.signatures = signatures;
            this.nParameters = nParameters;
        }

        @Nonnull
        public Map<String, String> getSignatures() {
            return signatures;
        }

        @Nonnull
        public Map<String, String> getNParameters() {
            return nParameters;
        }
    }
}
