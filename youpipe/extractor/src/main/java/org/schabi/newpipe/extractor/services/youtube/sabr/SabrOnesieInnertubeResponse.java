package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

public final class SabrOnesieInnertubeResponse {
    private final int proxyStatus;
    private final int httpStatus;
    private final int headerCount;
    private final int bodyBytes;

    private SabrOnesieInnertubeResponse(final int proxyStatus,
                                        final int httpStatus,
                                        final int headerCount,
                                        final int bodyBytes) {
        this.proxyStatus = proxyStatus;
        this.httpStatus = httpStatus;
        this.headerCount = headerCount;
        this.bodyBytes = bodyBytes;
    }

    @Nonnull
    static SabrOnesieInnertubeResponse decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int proxyStatus = -1;
        int httpStatus = -1;
        int headerCount = 0;
        int bodyBytes = 0;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                proxyStatus = (int) field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                httpStatus = (int) field.getVarint();
            } else if (field.getNumber() == 3
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                headerCount++;
            } else if (field.getNumber() == 4
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                bodyBytes = field.getBytes().length;
            }
        }
        return new SabrOnesieInnertubeResponse(proxyStatus, httpStatus, headerCount, bodyBytes);
    }

    @Nonnull
    public String summarize() {
        return "proxyStatus=" + proxyStatus
                + ", httpStatus=" + httpStatus
                + ", headers=" + headerCount
                + ", bodyBytes=" + bodyBytes;
    }
}
