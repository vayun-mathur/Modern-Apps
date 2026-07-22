package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;

public final class SabrSeek {
    private final long seekMediaTime;
    private final int seekMediaTimescale;
    private final int seekSource;

    private SabrSeek(final long seekMediaTime,
                     final int seekMediaTimescale,
                     final int seekSource) {
        this.seekMediaTime = seekMediaTime;
        this.seekMediaTimescale = seekMediaTimescale;
        this.seekSource = seekSource;
    }

    @Nonnull
    static SabrSeek decode(@Nonnull final byte[] data) throws SabrProtocolException {
        long seekMediaTime = -1;
        int seekMediaTimescale = -1;
        int seekSource = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                seekMediaTime = field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                seekMediaTimescale = (int) field.getVarint();
            } else if (field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT) {
                seekSource = (int) field.getVarint();
            }
        }
        return new SabrSeek(seekMediaTime, seekMediaTimescale, seekSource);
    }

    public long getSeekMediaTime() {
        return seekMediaTime;
    }

    public int getSeekMediaTimescale() {
        return seekMediaTimescale;
    }

    public int getSeekSource() {
        return seekSource;
    }

    @Nonnull
    public String summarize() {
        return "seek=" + seekMediaTime + '/' + seekMediaTimescale
                + ", source=" + seekSource;
    }
}
