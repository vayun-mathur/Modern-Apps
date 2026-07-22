package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

public final class SabrSnackbarMessage {
    private final int id;

    private SabrSnackbarMessage(final int id) {
        this.id = id;
    }

    @Nonnull
    static SabrSnackbarMessage decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int id = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                id = (int) field.getVarint();
            }
        }
        return new SabrSnackbarMessage(id);
    }

    public int getId() {
        return id;
    }

    @Nonnull
    public String summarize() {
        return "id=" + id;
    }
}
