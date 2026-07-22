package org.schabi.newpipe.extractor.services.youtube.sabr;

public final class SabrRecoverableException extends SabrProtocolException {
    public SabrRecoverableException(final String message) {
        super(message);
    }

    public SabrRecoverableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
