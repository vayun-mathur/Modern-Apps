package org.schabi.newpipe.extractor.services.youtube.sabr;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YoutubeSabrFormat implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int itag;
    private final long lastModified;
    @Nullable
    private final String xtags;
    @Nullable
    private final String mimeType;
    @Nullable
    private final String audioTrackId;
    @Nullable
    private final String audioTrackDisplayName;
    private final boolean audioIsDefault;
    @Nullable
    private final String qualityLabel;
    @Nullable
    private final String audioQuality;
    private final boolean drc;
    private final int width;
    private final int height;
    private final int bitrate;
    private final long contentLength;
    private final long approxDurationMs;
    @Nullable
    private String initializationUrl;
    @Nullable
    private final String initializationUrlTemplate;
    @Nullable
    private final String obfuscatedSignature;
    @Nullable
    private final String signatureParameter;
    @Nullable
    private final String obfuscatedNParameter;
    private final long initRangeStart;
    private final long initRangeEnd;
    private YoutubeSabrFormat(final int itag,
                              final long lastModified,
                              @Nullable final String xtags,
                              @Nullable final String mimeType,
                              @Nullable final String audioTrackId,
                              @Nullable final String audioTrackDisplayName,
                              final boolean audioIsDefault,
                              @Nullable final String qualityLabel,
                              @Nullable final String audioQuality,
                              final boolean drc,
                              final int width,
                              final int height,
                              final int bitrate,
                              final long contentLength,
                              final long approxDurationMs,
                              @Nullable final String initializationUrl,
                              final long initRangeStart,
                              final long initRangeEnd) {
        this(itag, lastModified, xtags, mimeType, audioTrackId, audioTrackDisplayName,
                audioIsDefault, qualityLabel, audioQuality, drc, width, height, bitrate,
                contentLength, approxDurationMs, initializationUrl, initializationUrl, null, null,
                null, initRangeStart, initRangeEnd);
    }

    private YoutubeSabrFormat(final int itag,
                              final long lastModified,
                              @Nullable final String xtags,
                              @Nullable final String mimeType,
                              @Nullable final String audioTrackId,
                              @Nullable final String audioTrackDisplayName,
                              final boolean audioIsDefault,
                              @Nullable final String qualityLabel,
                              @Nullable final String audioQuality,
                              final boolean drc,
                              final int width,
                              final int height,
                              final int bitrate,
                              final long contentLength,
                              final long approxDurationMs,
                              @Nullable final String initializationUrl,
                              @Nullable final String initializationUrlTemplate,
                              @Nullable final String obfuscatedSignature,
                              @Nullable final String signatureParameter,
                              @Nullable final String obfuscatedNParameter,
                              final long initRangeStart,
                              final long initRangeEnd) {
        this.itag = itag;
        this.lastModified = lastModified;
        this.xtags = xtags;
        this.mimeType = mimeType;
        this.audioTrackId = audioTrackId;
        this.audioTrackDisplayName = audioTrackDisplayName;
        this.audioIsDefault = audioIsDefault;
        this.qualityLabel = qualityLabel;
        this.audioQuality = audioQuality;
        this.drc = drc;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.contentLength = contentLength;
        this.approxDurationMs = approxDurationMs;
        this.initializationUrl = initializationUrl;
        this.initializationUrlTemplate = initializationUrlTemplate;
        this.obfuscatedSignature = obfuscatedSignature;
        this.signatureParameter = signatureParameter;
        this.obfuscatedNParameter = obfuscatedNParameter;
        this.initRangeStart = initRangeStart;
        this.initRangeEnd = initRangeEnd;
    }

    @Nonnull
    static List<YoutubeSabrFormat> fromAdaptiveFormats(@Nonnull final String videoId,
                                                       @Nullable final JsonArray formats)
            throws ParsingException {
        final List<YoutubeSabrFormat> result = parseAdaptiveFormats(formats);
        return resolveInitializationUrls(videoId, result);
    }

    @Nonnull
    static List<YoutubeSabrFormat> parseAdaptiveFormats(@Nullable final JsonArray formats)
            throws ParsingException {
        final List<YoutubeSabrFormat> result = new ArrayList<>();
        if (formats == null) {
            return result;
        }
        for (int i = 0; i < formats.size(); i++) {
            final JsonObject format = formats.getObject(i);
            if (format != null && format.has("itag")) {
                result.add(fromJson(format));
            }
        }
        return result;
    }

    @Nonnull
    private static YoutubeSabrFormat fromJson(@Nonnull final JsonObject format)
            throws ParsingException {
        final JsonObject audioTrack = format.getObject("audioTrack");
        final JsonObject initRange = format.getObject("initRange");
        final JsonObject indexRange = format.getObject("indexRange");
        final long initRangeStart = initRange == null ? -1 : parseLong(initRange.get("start"));
        long initRangeEnd = initRange == null ? -1 : parseLong(initRange.get("end"));
        if (indexRange != null) {
            final long indexRangeEnd = parseLong(indexRange.get("end"));
            if (indexRangeEnd > initRangeEnd) {
                initRangeEnd = indexRangeEnd;
            }
        }
        final StreamingUrlParts urlParts = StreamingUrlParts.fromJson(format);
        return new YoutubeSabrFormat(
                format.getInt("itag"),
                parseLong(format.get("lastModified")),
                format.getString("xtags"),
                format.getString("mimeType"),
                audioTrack == null ? null : audioTrack.getString("id"),
                audioTrack == null ? null : audioTrack.getString("displayName"),
                audioTrack != null && audioTrack.getBoolean("audioIsDefault", false),
                format.getString("qualityLabel"),
                format.getString("audioQuality"),
                format.getBoolean("isDrc", false),
                format.getInt("width", -1),
                format.getInt("height", -1),
                format.getInt("bitrate", -1),
                parseLong(format.get("contentLength")),
                parseLong(format.get("approxDurationMs")),
                urlParts.isResolved() ? urlParts.url : null,
                urlParts.url,
                urlParts.signature,
                urlParts.signatureParameter,
                urlParts.nParameter,
                initRangeStart, initRangeEnd);
    }

    @Nonnull
    private static List<YoutubeSabrFormat> resolveInitializationUrls(
            @Nonnull final String videoId,
            @Nonnull final List<YoutubeSabrFormat> formats) throws ParsingException {
        final Set<String> signatures = new LinkedHashSet<>();
        final Set<String> nParameters = new LinkedHashSet<>();
        collectDecodeParameters(formats, signatures, nParameters);
        if (signatures.isEmpty() && nParameters.isEmpty()) {
            return formats;
        }
        final YoutubeApiDecoder.BatchDecodeResult decoded =
                YoutubeJavaScriptPlayerManager.deobfuscateBatch(videoId,
                        new ArrayList<>(signatures), new ArrayList<>(nParameters));
        resolveInitializationUrls(formats, decoded);
        return formats;
    }

    static void collectDecodeParameters(@Nonnull final Collection<YoutubeSabrFormat> formats,
                                        @Nonnull final Set<String> signatures,
                                        @Nonnull final Set<String> nParameters) {
        for (final YoutubeSabrFormat format : formats) {
            if (format.obfuscatedSignature != null) {
                signatures.add(format.obfuscatedSignature);
            }
            if (format.obfuscatedNParameter != null) {
                nParameters.add(format.obfuscatedNParameter);
            }
        }
    }

    static void resolveInitializationUrls(@Nonnull final Collection<YoutubeSabrFormat> formats,
                                          @Nonnull final YoutubeApiDecoder.BatchDecodeResult decoded)
            throws ParsingException {
        for (final YoutubeSabrFormat format : formats) {
            format.resolveInitializationUrl(decoded);
        }
    }

    @Nullable
    static String resolveNParameter(@Nullable final String url,
                                    @Nonnull final YoutubeApiDecoder.BatchDecodeResult decoded)
            throws ParsingException {
        if (url == null || url.isEmpty()) {
            return url;
        }
        final String encryptedN = extractNParameter(url);
        if (encryptedN == null) {
            return url;
        }
        final String decryptedN = decoded.getNParameters().get(encryptedN);
        if (decryptedN == null) {
            return url;
        }
        final java.util.regex.Matcher queryMatcher = java.util.regex.Pattern
                .compile("([?&])n=([^&]+)").matcher(url);
        if (queryMatcher.find()) {
            return url.substring(0, queryMatcher.start(2)) + urlEncode(decryptedN)
                    + url.substring(queryMatcher.end(2));
        }
        final java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("/n/([^/?#]+)").matcher(url);
        if (!pathMatcher.find()) {
            return url;
        }
        return url.substring(0, pathMatcher.start(1)) + decryptedN
                + url.substring(pathMatcher.end(1));
    }

    @Nullable
    static String extractNParameter(@Nullable final String url) throws ParsingException {
        if (url == null || url.isEmpty()) {
            return null;
        }
        final java.util.regex.Matcher queryMatcher = java.util.regex.Pattern
                .compile("([?&])n=([^&]+)").matcher(url);
        if (queryMatcher.find()) {
            return urlDecode(queryMatcher.group(2));
        }
        final java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("/n/([^/?#]+)").matcher(url);
        return pathMatcher.find() ? pathMatcher.group(1) : null;
    }

    private void resolveInitializationUrl(
            @Nonnull final YoutubeApiDecoder.BatchDecodeResult decoded) throws ParsingException {
        String url = initializationUrlTemplate;
        if (url == null || url.isEmpty()) {
            initializationUrl = url;
            return;
        }
        if (obfuscatedSignature != null) {
            final String signature = decoded.getSignatures().get(obfuscatedSignature);
            if (signature == null) {
                initializationUrl = null;
                return;
            }
            final String separator = url.contains("?") ? "&" : "?";
            url = url + separator + urlEncode(signatureParameter == null
                    ? "signature" : signatureParameter) + '=' + urlEncode(signature);
        }
        initializationUrl = resolveNParameter(url, decoded);
    }

    private static final class StreamingUrlParts {
        @Nullable final String url;
        @Nullable final String signature;
        @Nullable final String signatureParameter;
        @Nullable final String nParameter;

        private StreamingUrlParts(@Nullable final String url,
                                  @Nullable final String signature,
                                  @Nullable final String signatureParameter,
                                  @Nullable final String nParameter) {
            this.url = url;
            this.signature = signature;
            this.signatureParameter = signatureParameter;
            this.nParameter = nParameter;
        }

        @Nonnull
        static StreamingUrlParts fromJson(@Nonnull final JsonObject format)
                throws ParsingException {
            String url = format.getString("url");
            String signature = null;
            String signatureParameter = null;
            final String cipherValue = format.has("signatureCipher")
                    ? format.getString("signatureCipher") : format.getString("cipher");
            if ((url == null || url.isEmpty()) && cipherValue != null && !cipherValue.isEmpty()) {
                final Map<String, String> cipher = parseQuery(cipherValue);
                url = cipher.get("url");
                signature = cipher.get("s");
                signatureParameter = cipher.getOrDefault("sp", "signature");
            }
            final String nParameter = extractNParameter(url);
            return new StreamingUrlParts(url, signature, signatureParameter, nParameter);
        }

        boolean isResolved() {
            return signature == null && nParameter == null;
        }
    }

    @Nonnull
    private static Map<String, String> parseQuery(@Nullable final String value)
            throws ParsingException {
        final Map<String, String> params = new HashMap<>();
        if (value == null || value.isEmpty()) {
            return params;
        }
        for (final String part : value.split("&")) {
            final int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            params.put(urlDecode(part.substring(0, equals)),
                    urlDecode(part.substring(equals + 1)));
        }
        return params;
    }

    @Nonnull
    private static String urlEncode(@Nonnull final String value) throws ParsingException {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            throw new ParsingException("Could not encode SABR signature cipher", e);
        }
    }

    @Nonnull
    private static String urlDecode(@Nonnull final String value) throws ParsingException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            throw new ParsingException("Could not decode SABR signature cipher", e);
        }
    }

    private static long parseLong(@Nullable final Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (final NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public boolean isAudio() {
        return mimeType != null && mimeType.contains("audio");
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.contains("video");
    }

    public int getItag() {
        return itag;
    }

    public long getLastModified() {
        return lastModified;
    }

    @Nullable
    public String getXtags() {
        return xtags;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    @Nullable
    public String getAudioTrackId() {
        return audioTrackId;
    }

    @Nullable
    public String getAudioTrackDisplayName() {
        return audioTrackDisplayName;
    }

    public boolean isAudioDefault() {
        return audioIsDefault;
    }

    /**
     * True when this is the source/original-language audio track (not an auto-dub). YouTube marks
     * it in the localized display name, e.g. "French (original)". Mirrors the detection used for
     * non-SABR streams.
     */
    public boolean isOriginalAudio() {
        return audioTrackDisplayName != null
                && (audioTrackDisplayName.contains("original")
                    || audioTrackDisplayName.contains("yokuqala"));
    }

    @Nullable
    public String getQualityLabel() {
        return qualityLabel;
    }

    @Nullable
    public String getAudioQuality() {
        return audioQuality;
    }

    public boolean isDrc() {
        return drc;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrate() {
        return bitrate;
    }

    public long getContentLength() {
        return contentLength;
    }

    public long getApproxDurationMs() {
        return approxDurationMs;
    }

    @Nullable
    public String getInitializationUrl() {
        return initializationUrl;
    }

    public long getInitRangeStart() {
        return initRangeStart;
    }

    public long getInitRangeEnd() {
        return initRangeEnd;
    }

}
