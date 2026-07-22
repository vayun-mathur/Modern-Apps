package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

public final class UmpPart {
    private final int type;
    private final int size;
    @Nonnull
    private final byte[] data;

    UmpPart(final int type, final int size, @Nonnull final byte[] data) {
        this.type = type;
        this.size = size;
        this.data = data;
    }

    public int getType() {
        return type;
    }

    public int getSize() {
        return size;
    }

    @Nonnull
    public byte[] getData() {
        return data.clone();
    }

    @Nonnull
    byte[] getRawData() {
        return data;
    }
}
