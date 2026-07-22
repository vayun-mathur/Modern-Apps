package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrRedirect {
    @Nullable
    private final String url;

    private SabrRedirect(@Nullable final String url) {
        this.url = url;
    }

    @Nonnull
    static SabrRedirect decode(@Nonnull final byte[] data) throws SabrProtocolException {
        String url = null;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                url = field.getString();
            }
        }
        return new SabrRedirect(url);
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    @Nonnull
    public String summarize() {
        return "urlLength=" + (url == null ? 0 : url.length());
    }
}
