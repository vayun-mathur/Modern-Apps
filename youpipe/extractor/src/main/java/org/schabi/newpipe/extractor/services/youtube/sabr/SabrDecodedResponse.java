package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SabrDecodedResponse {
    private static final int MAX_MALFORMED_PARTS = 16;
    private static final int MAX_MALFORMED_MESSAGE_CHARS = 256;
    private final List<UmpPart> parts = new ArrayList<>();
    private final List<String> partSummaries = new ArrayList<>();
    private final List<String> wireFieldSummaries = new ArrayList<>();
    private final List<SabrFormatInitializationMetadata> formatInitializationMetadata =
            new ArrayList<>();
    private final List<SabrMediaHeader> mediaHeaders = new ArrayList<>();
    private final List<SabrContextUpdate> sabrContextUpdates = new ArrayList<>();
    private final List<SabrLiveMetadata> liveMetadata = new ArrayList<>();
    private final List<SabrOnesieHeader> onesieHeaders = new ArrayList<>();
    private final List<SabrOnesieData> onesieData = new ArrayList<>();
    private final Map<Integer, Long> mediaBytesByHeaderId = new LinkedHashMap<>();
    private final List<Integer> mediaEndHeaderIds = new ArrayList<>();
    private final List<Integer> unknownPartTypes = new ArrayList<>();
    private final List<String> malformedParts = new ArrayList<>();
    private final Map<Integer, List<String>> genericPartDescriptions = new LinkedHashMap<>();
    @Nullable
    private String redirectUrl;
    @Nullable
    private SabrRedirect redirect;
    @Nullable
    private SabrSeek sabrSeek;
    @Nullable
    private String sabrError;
    @Nullable
    private SabrError sabrErrorDetails;
    @Nullable
    private SabrReloadPlayerResponse reloadPlayerResponse;
    @Nullable
    private SabrFormatSelectionConfig formatSelectionConfig;
    @Nullable
    private SabrSelectableFormats selectableFormats;
    @Nullable
    private SabrNextRequestPolicy nextRequestPolicy;
    @Nullable
    private SabrRequestIdentifier requestIdentifier;
    @Nullable
    private SabrPlaybackStartPolicy playbackStartPolicy;
    @Nullable
    private SabrContextSendingPolicy sabrContextSendingPolicy;
    @Nullable
    private SabrRequestCancellationPolicy requestCancellationPolicy;
    @Nullable
    private SabrStreamProtectionStatus streamProtection;
    @Nullable
    private SabrPrewarmConnection prewarmConnection;
    @Nullable
    private SabrSnackbarMessage snackbarMessage;
    private int streamProtectionStatus = -1;
    private int streamProtectionMaxRetries = -1;
    private int backoffTimeMs = -1;
    private boolean reloadRequested;

    void addPart(@Nonnull final UmpPart part) {
        parts.add(part);
        addPartSummary(partSummaries, part.getType(), part.getSize());
    }

    void setPartSummaries(@Nonnull final List<String> summaries) {
        partSummaries.clear();
        partSummaries.addAll(summaries);
    }

    static void addPartSummary(@Nonnull final List<String> summaries,
                               final int type,
                               final int size) {
        final String value = type + ":" + size;
        if (summaries.isEmpty()) {
            summaries.add(value);
            return;
        }
        final int lastIndex = summaries.size() - 1;
        final String last = summaries.get(lastIndex);
        if (last.equals(value)) {
            summaries.set(lastIndex, value + "x2");
        } else if (last.startsWith(value + 'x')) {
            summaries.set(lastIndex, value
                    + 'x' + (Integer.parseInt(last.substring(value.length() + 1)) + 1));
        } else {
            summaries.add(value);
        }
    }

    void addUnknownPartType(final int type) {
        unknownPartTypes.add(type);
    }

    void addMalformedPart(final int type, final int size,
                          @Nonnull final SabrProtocolException error) {
        if (malformedParts.size() >= MAX_MALFORMED_PARTS) {
            return;
        }
        final String message = String.valueOf(error.getMessage());
        malformedParts.add(type + ":" + size + ":" + (message.length()
                > MAX_MALFORMED_MESSAGE_CHARS
                ? message.substring(0, MAX_MALFORMED_MESSAGE_CHARS) : message));
    }

    void addWireFieldSummary(final int type, @Nonnull final String summary) {
        wireFieldSummaries.add(type + "={" + summary + '}');
    }

    void addGenericPartDescription(final int type, @Nonnull final String description) {
        List<String> descriptions = genericPartDescriptions.get(type);
        if (descriptions == null) {
            descriptions = new ArrayList<>();
            genericPartDescriptions.put(type, descriptions);
        }
        descriptions.add(description);
    }

    void addFormatInitializationMetadata(
            @Nonnull final SabrFormatInitializationMetadata metadata) {
        formatInitializationMetadata.add(metadata);
    }

    void addMediaHeader(@Nonnull final SabrMediaHeader header) {
        mediaHeaders.add(header);
    }

    void addSabrContextUpdate(@Nonnull final SabrContextUpdate sabrContextUpdate) {
        sabrContextUpdates.add(sabrContextUpdate);
    }

    void addLiveMetadata(@Nonnull final SabrLiveMetadata metadata) {
        liveMetadata.add(metadata);
    }

    void addOnesieHeader(@Nonnull final SabrOnesieHeader onesieHeader) {
        onesieHeaders.add(onesieHeader);
    }

    void addOnesieData(@Nonnull final SabrOnesieData data) {
        onesieData.add(data);
    }

    void addMediaBytes(final int headerId, final long bytes) {
        final Long current = mediaBytesByHeaderId.get(headerId);
        mediaBytesByHeaderId.put(headerId, current == null ? bytes : current + bytes);
    }

    void addMediaEndHeaderId(final int headerId) {
        mediaEndHeaderIds.add(headerId);
    }

    void setRedirectUrl(@Nullable final String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    void setRedirect(@Nullable final SabrRedirect redirect) {
        this.redirect = redirect;
    }

    void setSabrSeek(@Nullable final SabrSeek sabrSeek) {
        this.sabrSeek = sabrSeek;
    }

    void setSabrError(@Nullable final String sabrError) {
        this.sabrError = sabrError;
    }

    void setSabrErrorDetails(@Nullable final SabrError sabrErrorDetails) {
        this.sabrErrorDetails = sabrErrorDetails;
    }

    void setReloadPlayerResponse(@Nullable final SabrReloadPlayerResponse reloadPlayerResponse) {
        this.reloadPlayerResponse = reloadPlayerResponse;
    }

    void setFormatSelectionConfig(@Nullable final SabrFormatSelectionConfig formatSelectionConfig) {
        this.formatSelectionConfig = formatSelectionConfig;
    }

    void setSelectableFormats(@Nullable final SabrSelectableFormats selectableFormats) {
        this.selectableFormats = selectableFormats;
    }

    void setNextRequestPolicy(@Nullable final SabrNextRequestPolicy nextRequestPolicy) {
        this.nextRequestPolicy = nextRequestPolicy;
    }

    void setRequestIdentifier(@Nullable final SabrRequestIdentifier requestIdentifier) {
        this.requestIdentifier = requestIdentifier;
    }

    void setPlaybackStartPolicy(@Nullable final SabrPlaybackStartPolicy playbackStartPolicy) {
        this.playbackStartPolicy = playbackStartPolicy;
    }

    void setSabrContextSendingPolicy(
            @Nullable final SabrContextSendingPolicy sabrContextSendingPolicy) {
        this.sabrContextSendingPolicy = sabrContextSendingPolicy;
    }

    void setRequestCancellationPolicy(
            @Nullable final SabrRequestCancellationPolicy requestCancellationPolicy) {
        this.requestCancellationPolicy = requestCancellationPolicy;
    }

    void setStreamProtection(@Nullable final SabrStreamProtectionStatus streamProtection) {
        this.streamProtection = streamProtection;
    }

    void setPrewarmConnection(@Nullable final SabrPrewarmConnection prewarmConnection) {
        this.prewarmConnection = prewarmConnection;
    }

    void setSnackbarMessage(@Nullable final SabrSnackbarMessage snackbarMessage) {
        this.snackbarMessage = snackbarMessage;
    }

    void setStreamProtectionStatus(final int streamProtectionStatus) {
        this.streamProtectionStatus = streamProtectionStatus;
    }

    void setStreamProtectionMaxRetries(final int streamProtectionMaxRetries) {
        this.streamProtectionMaxRetries = streamProtectionMaxRetries;
    }

    void setBackoffTimeMs(final int backoffTimeMs) {
        this.backoffTimeMs = backoffTimeMs;
    }

    void setReloadRequested(final boolean reloadRequested) {
        this.reloadRequested = reloadRequested;
    }

    @Nonnull
    public List<UmpPart> getParts() {
        return Collections.unmodifiableList(parts);
    }

    @Nonnull
    public List<SabrFormatInitializationMetadata> getFormatInitializationMetadata() {
        return Collections.unmodifiableList(formatInitializationMetadata);
    }

    @Nonnull
    public List<SabrMediaHeader> getMediaHeaders() {
        return Collections.unmodifiableList(mediaHeaders);
    }

    @Nonnull
    public List<SabrContextUpdate> getSabrContextUpdates() {
        return Collections.unmodifiableList(sabrContextUpdates);
    }

    @Nonnull
    public List<SabrLiveMetadata> getLiveMetadata() {
        return Collections.unmodifiableList(liveMetadata);
    }

    @Nonnull
    public List<SabrOnesieHeader> getOnesieHeaders() {
        return Collections.unmodifiableList(onesieHeaders);
    }

    @Nonnull
    public List<SabrOnesieData> getOnesieData() {
        return Collections.unmodifiableList(onesieData);
    }

    @Nonnull
    public Map<Integer, Long> getMediaBytesByHeaderId() {
        return Collections.unmodifiableMap(mediaBytesByHeaderId);
    }

    @Nonnull
    public List<Integer> getMediaEndHeaderIds() {
        return Collections.unmodifiableList(mediaEndHeaderIds);
    }

    @Nonnull
    public List<String> getIntegrityIssues() {
        final List<String> issues = new ArrayList<>();
        final List<Integer> mediaHeaderIds = new ArrayList<>();
        for (final SabrMediaHeader header : mediaHeaders) {
            if (mediaHeaderIds.contains(header.getHeaderId())) {
                issues.add("duplicate-media-header:" + header.getHeaderId());
            }
            mediaHeaderIds.add(header.getHeaderId());
            final Long mediaBytes = mediaBytesByHeaderId.get(header.getHeaderId());
            if (mediaBytes == null) {
                issues.add("missing-media:" + header.getHeaderId());
            } else if (header.getContentLength() >= 0 && mediaBytes != header.getContentLength()) {
                issues.add("length-mismatch:" + header.getHeaderId()
                        + ":expected=" + header.getContentLength()
                        + ":actual=" + mediaBytes);
            }
            if (!mediaEndHeaderIds.contains(header.getHeaderId())) {
                issues.add("missing-media-end:" + header.getHeaderId());
            }
        }
        for (final Integer headerId : mediaBytesByHeaderId.keySet()) {
            if (!mediaHeaderIds.contains(headerId)) {
                issues.add("media-without-header:" + headerId);
            }
        }
        for (final Integer headerId : mediaEndHeaderIds) {
            if (!mediaHeaderIds.contains(headerId)) {
                issues.add("media-end-without-header:" + headerId);
            }
        }
        return issues;
    }

    @Nonnull
    public List<Integer> getUnknownPartTypes() {
        return Collections.unmodifiableList(unknownPartTypes);
    }

    @Nonnull
    public List<String> getMalformedParts() {
        return Collections.unmodifiableList(malformedParts);
    }

    @Nonnull
    public Map<Integer, List<String>> getGenericPartDescriptions() {
        final Map<Integer, List<String>> copy = new LinkedHashMap<>();
        for (final Map.Entry<Integer, List<String>> entry : genericPartDescriptions.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Nullable
    public String getRedirectUrl() {
        return redirectUrl;
    }

    @Nullable
    public SabrRedirect getRedirect() {
        return redirect;
    }

    @Nullable
    public SabrSeek getSabrSeek() {
        return sabrSeek;
    }

    @Nullable
    public String getSabrError() {
        return sabrError;
    }

    @Nullable
    public SabrError getSabrErrorDetails() {
        return sabrErrorDetails;
    }

    @Nullable
    public SabrReloadPlayerResponse getReloadPlayerResponse() {
        return reloadPlayerResponse;
    }

    @Nullable
    public SabrFormatSelectionConfig getFormatSelectionConfig() {
        return formatSelectionConfig;
    }

    @Nullable
    public SabrSelectableFormats getSelectableFormats() {
        return selectableFormats;
    }

    @Nullable
    public SabrNextRequestPolicy getNextRequestPolicy() {
        return nextRequestPolicy;
    }

    @Nullable
    public SabrRequestIdentifier getRequestIdentifier() {
        return requestIdentifier;
    }

    @Nullable
    public SabrPlaybackStartPolicy getPlaybackStartPolicy() {
        return playbackStartPolicy;
    }

    @Nullable
    public SabrContextSendingPolicy getSabrContextSendingPolicy() {
        return sabrContextSendingPolicy;
    }

    @Nullable
    public SabrRequestCancellationPolicy getRequestCancellationPolicy() {
        return requestCancellationPolicy;
    }

    @Nullable
    public SabrStreamProtectionStatus getStreamProtection() {
        return streamProtection;
    }

    @Nullable
    public SabrPrewarmConnection getPrewarmConnection() {
        return prewarmConnection;
    }

    @Nullable
    public SabrSnackbarMessage getSnackbarMessage() {
        return snackbarMessage;
    }

    public int getStreamProtectionStatus() {
        return streamProtectionStatus;
    }

    public int getStreamProtectionMaxRetries() {
        return streamProtectionMaxRetries;
    }

    public int getBackoffTimeMs() {
        return backoffTimeMs;
    }

    public boolean isReloadRequested() {
        return reloadRequested;
    }

    public boolean hasMedia() {
        return !mediaHeaders.isEmpty() || !mediaBytesByHeaderId.isEmpty();
    }

    public boolean isNoMediaResponse() {
        return !hasMedia();
    }

    public boolean isPolicyOnlyResponse() {
        return isNoMediaResponse() && nextRequestPolicy != null;
    }

    public boolean isProtectedNoMediaResponse() {
        return isNoMediaResponse() && streamProtectionStatus >= 3;
    }

    public boolean isProtectionBoundaryNoMediaResponse() {
        return isNoMediaResponse() && streamProtectionStatus >= 2;
    }

    @Nonnull
    public String summarizeForDiagnostics() {
        final List<String> initialization = new ArrayList<>();
        for (final SabrFormatInitializationMetadata metadata : formatInitializationMetadata) {
            initialization.add(metadata.summarize());
        }
        final List<String> headers = new ArrayList<>();
        for (final SabrMediaHeader header : mediaHeaders) {
            headers.add(header.summarize());
        }
        return "parts=" + partSummaries
                + ", wireFields=" + wireFieldSummaries
                + ", controls=" + genericPartDescriptions
                + ", initialization=" + initialization
                + ", mediaHeaders=" + headers
                + ", mediaBytes=" + mediaBytesByHeaderId
                + ", mediaEnds=" + mediaEndHeaderIds
                + ", integrity=" + getIntegrityIssues()
                + ", malformedParts=" + malformedParts
                + ", unknownParts=" + unknownPartTypes
                + ", protection=" + streamProtectionStatus + '/' + streamProtectionMaxRetries
                + ", backoffMs=" + backoffTimeMs
                + ", reload=" + reloadRequested;
    }

    @Nonnull
    public String summarizeNoMediaResponse() {
        return "parts=" + parts.size()
                + ", status=" + streamProtectionStatus
                + ", maxRetries=" + streamProtectionMaxRetries
                + ", backoffMs=" + backoffTimeMs
                + ", policy=" + (nextRequestPolicy != null)
                + ", reload=" + reloadRequested
                + ", redirect=" + (redirectUrl != null && !redirectUrl.isEmpty())
                + ", error=" + (sabrError == null ? "null" : sabrError);
    }
}
