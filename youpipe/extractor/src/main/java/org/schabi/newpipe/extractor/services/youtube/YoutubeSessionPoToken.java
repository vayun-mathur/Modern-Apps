package org.schabi.newpipe.extractor.services.youtube;

import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * A session-bound proof-of-origin token and the visitor data it is bound to.
 */
public final class YoutubeSessionPoToken {
    @Nonnull
    private final String visitorData;
    @Nonnull
    private final String poToken;

    public YoutubeSessionPoToken(@Nonnull final String visitorData,
                                 @Nonnull final String poToken) {
        this.visitorData = Objects.requireNonNull(visitorData);
        this.poToken = Objects.requireNonNull(poToken);
    }

    @Nonnull
    public String getVisitorData() {
        return visitorData;
    }

    @Nonnull
    public String getPoToken() {
        return poToken;
    }
}
