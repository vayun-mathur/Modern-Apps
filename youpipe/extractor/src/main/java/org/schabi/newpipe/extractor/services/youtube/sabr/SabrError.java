package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrError {
    @Nullable
    private final String type;
    private final int code;

    private SabrError(@Nullable final String type,
                      final int code) {
        this.type = type;
        this.code = code;
    }

    @Nonnull
    static SabrError decode(@Nonnull final byte[] data) throws SabrProtocolException {
        String type = null;
        int code = 0;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                type = field.getString();
            } else if (field.getNumber() == 2
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                code = (int) field.getVarint();
            }
        }
        return new SabrError(type, code);
    }

    @Nullable
    public String getType() {
        return type;
    }

    public int getCode() {
        return code;
    }

    @Nonnull
    public String summarize() {
        return "type=" + (type == null ? "null" : type) + ", code=" + code;
    }
}
