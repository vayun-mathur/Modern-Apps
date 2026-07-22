package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

/** Host-facing media envelope operations. Control policy code never receives media payloads. */
public interface SabrMediaProtocol {
    int getHeaderPartType();
    int getMediaPartType();
    int getEndPartType();

    @Nonnull
    SabrMediaHeader decodeHeader(@Nonnull byte[] payload) throws SabrProtocolException;

    @Nonnull
    static SabrMediaProtocol builtin() {
        return BuiltinHolder.INSTANCE;
    }

    final class BuiltinHolder {
        private static final SabrMediaProtocol INSTANCE = new SabrMediaProtocol() {
            @Override public int getHeaderPartType() { return SabrResponseDecoder.MEDIA_HEADER; }
            @Override public int getMediaPartType() { return SabrResponseDecoder.MEDIA; }
            @Override public int getEndPartType() { return SabrResponseDecoder.MEDIA_END; }
            @Nonnull @Override public SabrMediaHeader decodeHeader(@Nonnull final byte[] payload)
                    throws SabrProtocolException {
                return SabrMediaHeader.decode(payload);
            }
        };
        private BuiltinHolder() { }
    }
}
