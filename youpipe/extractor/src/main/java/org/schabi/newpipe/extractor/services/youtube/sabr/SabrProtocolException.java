package org.schabi.newpipe.extractor.services.youtube.sabr;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;

public class SabrProtocolException extends ExtractionException {
    public SabrProtocolException(final String message) {
        super(message);
    }

    public SabrProtocolException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
