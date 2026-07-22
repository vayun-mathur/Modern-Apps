package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrRequestIdentifier {
    @Nullable
    private final String token;

    private SabrRequestIdentifier(@Nullable final String token) {
        this.token = token;
    }

    @Nonnull
    static SabrRequestIdentifier decode(@Nonnull final byte[] data) throws SabrProtocolException {
        String token = null;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                token = field.getString();
            }
        }
        return new SabrRequestIdentifier(token);
    }

    @Nullable
    public String getToken() {
        return token;
    }

    @Nonnull
    public String summarize() {
        return "tokenLength=" + (token == null ? 0 : token.length());
    }
}
