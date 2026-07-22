package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrReloadPlayerResponse {
    @Nullable
    private final String reloadPlaybackParamsToken;

    private SabrReloadPlayerResponse(@Nullable final String reloadPlaybackParamsToken) {
        this.reloadPlaybackParamsToken = reloadPlaybackParamsToken;
    }

    @Nonnull
    static SabrReloadPlayerResponse decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        String token = null;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                token = decodeReloadPlaybackContext(field.getBytes());
            }
        }
        return new SabrReloadPlayerResponse(token);
    }

    @Nullable
    private static String decodeReloadPlaybackContext(@Nonnull final byte[] data)
            throws SabrProtocolException {
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                return decodeReloadPlaybackParams(field.getBytes());
            }
        }
        return null;
    }

    @Nullable
    private static String decodeReloadPlaybackParams(@Nonnull final byte[] data)
            throws SabrProtocolException {
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                return field.getString();
            }
        }
        return null;
    }

    @Nullable
    public String getReloadPlaybackParamsToken() {
        return reloadPlaybackParamsToken;
    }

    @Nonnull
    public String summarize() {
        return "reloadPlaybackParamsTokenLength="
                + (reloadPlaybackParamsToken == null ? 0 : reloadPlaybackParamsToken.length());
    }
}
