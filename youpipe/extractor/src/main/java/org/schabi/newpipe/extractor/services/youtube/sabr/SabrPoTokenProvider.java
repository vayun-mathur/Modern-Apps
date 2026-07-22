package org.schabi.newpipe.extractor.services.youtube.sabr;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Supplies raw WEB PO token bytes for experimental SABR requests.
 */
@FunctionalInterface
public interface SabrPoTokenProvider {
    /**
     * Returns raw PO token bytes for the current SABR session, or {@code null} if unavailable.
     */
    @Nullable
    byte[] getPoToken(@Nonnull YoutubeSabrInfo info,
                      @Nonnull YoutubeSabrStreamState streamState)
            throws IOException, ExtractionException;

    /**
     * Like {@link #getPoToken}, but {@code forceRefresh} drops the cached token and mints a fresh
     * one. For when the server rejects a token that died mid-playback. Default impl ignores the flag.
     */
    @Nullable
    default byte[] getPoToken(@Nonnull final YoutubeSabrInfo info,
                              @Nonnull final YoutubeSabrStreamState streamState,
                              final boolean forceRefresh)
            throws IOException, ExtractionException {
        return getPoToken(info, streamState);
    }
}
