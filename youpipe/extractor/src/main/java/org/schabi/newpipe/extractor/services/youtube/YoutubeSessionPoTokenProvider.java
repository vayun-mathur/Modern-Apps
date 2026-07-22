package org.schabi.newpipe.extractor.services.youtube;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Supplies the visitor-bound token used by YouTube {@code /player} requests.
 *
 * <p>Implementations must be thread-safe. Returning {@code null} lets extraction continue without
 * a token, which is required as a compatibility fallback when the local attestation runtime is not
 * available.</p>
 */
public interface YoutubeSessionPoTokenProvider {
    @Nullable
    YoutubeSessionPoToken getSessionPoToken(@Nonnull String clientName,
                                            @Nonnull Localization localization,
                                            @Nonnull ContentCountry contentCountry,
                                            boolean loggedIn)
            throws IOException, ExtractionException;
}
