package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

public final class SabrStreamProtectionStatus {
    private final int status;
    private final int maxRetries;
    @Nonnull
    private final String unknownFields;

    private SabrStreamProtectionStatus(final int status,
                                       final int maxRetries,
                                       @Nonnull final String unknownFields) {
        this.status = status;
        this.maxRetries = maxRetries;
        this.unknownFields = unknownFields;
    }

    @Nonnull
    static SabrStreamProtectionStatus decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int status = -1;
        int maxRetries = -1;
        final String unknownFields = SabrProto.summarizeUnknownFields(data, 1, 2);
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                status = (int) field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                maxRetries = (int) field.getVarint();
            }
        }
        return new SabrStreamProtectionStatus(status, maxRetries, unknownFields);
    }

    public int getStatus() {
        return status;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    @Nonnull
    public String getUnknownFields() {
        return unknownFields;
    }

    @Nonnull
    public String summarize() {
        return "status=" + status + ", maxRetries=" + maxRetries
                + ("none".equals(unknownFields) ? "" : ", unknown=" + unknownFields);
    }
}
