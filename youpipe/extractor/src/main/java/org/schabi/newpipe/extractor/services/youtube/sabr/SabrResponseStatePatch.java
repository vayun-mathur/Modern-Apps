package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Normalized protocol state produced by a policy and applied by the bounded Host. */
public final class SabrResponseStatePatch {
    private static final int MAX_FORMATS = 64;
    private static final int MAX_LIVE_METADATA = 16;
    private static final int MAX_CONTEXT_UPDATES = 128;

    @Nullable private final SabrNextRequestPolicy nextRequestPolicy;
    @Nonnull private final List<SabrLiveMetadata> liveMetadata;
    @Nonnull private final List<SabrFormatInitializationMetadata> formatMetadata;
    @Nonnull private final List<SabrMediaHeader> mediaHeaders;
    @Nonnull private final List<SabrContextUpdate> contextUpdates;
    @Nullable private final SabrContextSendingPolicy contextSendingPolicy;

    private SabrResponseStatePatch(@Nonnull final Builder builder) {
        if (builder.liveMetadata.size() > MAX_LIVE_METADATA
                || builder.formatMetadata.size() > MAX_FORMATS
                || builder.contextUpdates.size() > MAX_CONTEXT_UPDATES) {
            throw new IllegalArgumentException("SABR response state patch exceeded Host limit");
        }
        nextRequestPolicy = builder.nextRequestPolicy;
        liveMetadata = immutableCopy(builder.liveMetadata);
        formatMetadata = immutableCopy(builder.formatMetadata);
        mediaHeaders = immutableCopy(builder.mediaHeaders);
        contextUpdates = immutableCopy(builder.contextUpdates);
        contextSendingPolicy = builder.contextSendingPolicy;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    static SabrResponseStatePatch builtin(@Nonnull final SabrDecodedResponse response) {
        final Builder builder = builder().setNextRequestPolicy(response.getNextRequestPolicy());
        for (final SabrLiveMetadata metadata : response.getLiveMetadata()) {
            builder.addLiveMetadata(metadata);
        }
        for (final SabrFormatInitializationMetadata metadata
                : response.getFormatInitializationMetadata()) {
            builder.addFormatMetadata(metadata);
        }
        for (final SabrMediaHeader header : response.getMediaHeaders()) {
            builder.addMediaHeader(header);
        }
        for (final SabrContextUpdate update : response.getSabrContextUpdates()) {
            builder.addContextUpdate(update);
        }
        builder.setContextSendingPolicy(response.getSabrContextSendingPolicy());
        return builder.build();
    }

    @Nullable SabrNextRequestPolicy getNextRequestPolicy() { return nextRequestPolicy; }
    @Nonnull List<SabrLiveMetadata> getLiveMetadata() { return liveMetadata; }
    @Nonnull List<SabrFormatInitializationMetadata> getFormatMetadata() { return formatMetadata; }
    @Nonnull List<SabrMediaHeader> getMediaHeaders() { return mediaHeaders; }
    @Nonnull List<SabrContextUpdate> getContextUpdates() { return contextUpdates; }
    @Nullable SabrContextSendingPolicy getContextSendingPolicy() { return contextSendingPolicy; }

    @Nonnull
    private static <T> List<T> immutableCopy(@Nonnull final List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Builder {
        @Nullable private SabrNextRequestPolicy nextRequestPolicy;
        @Nonnull private final List<SabrLiveMetadata> liveMetadata = new ArrayList<>();
        @Nonnull private final List<SabrFormatInitializationMetadata> formatMetadata =
                new ArrayList<>();
        @Nonnull private final List<SabrMediaHeader> mediaHeaders = new ArrayList<>();
        @Nonnull private final List<SabrContextUpdate> contextUpdates = new ArrayList<>();
        @Nullable private SabrContextSendingPolicy contextSendingPolicy;

        private Builder() { }

        @Nonnull
        public Builder setNextRequestPolicy(@Nullable final SabrNextRequestPolicy value) {
            nextRequestPolicy = value;
            return this;
        }

        @Nonnull
        public Builder addLiveMetadata(@Nonnull final SabrLiveMetadata value) {
            liveMetadata.add(value);
            return this;
        }

        @Nonnull
        public Builder addFormatMetadata(@Nonnull final SabrFormatInitializationMetadata value) {
            formatMetadata.add(value);
            return this;
        }

        @Nonnull
        public Builder addMediaHeader(@Nonnull final SabrMediaHeader value) {
            mediaHeaders.add(value);
            return this;
        }

        @Nonnull
        public Builder addContextUpdate(@Nonnull final SabrContextUpdate value) {
            contextUpdates.add(value);
            return this;
        }

        @Nonnull
        public Builder setContextSendingPolicy(@Nullable final SabrContextSendingPolicy value) {
            contextSendingPolicy = value;
            return this;
        }

        @Nonnull
        public SabrResponseStatePatch build() {
            return new SabrResponseStatePatch(this);
        }
    }
}
