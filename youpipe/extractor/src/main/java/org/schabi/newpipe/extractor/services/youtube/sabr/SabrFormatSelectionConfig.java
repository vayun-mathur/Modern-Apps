package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrFormatSelectionConfig {
    @Nonnull
    private final List<Integer> itags;
    @Nullable
    private final String videoId;
    private final int resolution;

    private SabrFormatSelectionConfig(@Nonnull final List<Integer> itags,
                                      @Nullable final String videoId,
                                      final int resolution) {
        this.itags = Collections.unmodifiableList(new ArrayList<>(itags));
        this.videoId = videoId;
        this.resolution = resolution;
    }

    @Nonnull
    static SabrFormatSelectionConfig decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        final List<Integer> itags = new ArrayList<>();
        String videoId = null;
        int resolution = 0;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 2) {
                if (field.getWireType() == SabrProto.WIRE_VARINT) {
                    itags.add((int) field.getVarint());
                } else if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    for (final Long itag : SabrProto.readPackedVarints(field.getBytes())) {
                        itags.add(itag.intValue());
                    }
                }
            } else if (field.getNumber() == 3
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                videoId = field.getString();
            } else if (field.getNumber() == 4
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                resolution = (int) field.getVarint();
            }
        }
        return new SabrFormatSelectionConfig(itags, videoId, resolution);
    }

    @Nonnull
    public List<Integer> getItags() {
        return itags;
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    public int getResolution() {
        return resolution;
    }

    @Nonnull
    public String summarize() {
        final StringBuilder builder = new StringBuilder();
        builder.append("itags=").append(itags.size()).append('[');
        final int sampleSize = Math.min(8, itags.size());
        for (int i = 0; i < sampleSize; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(itags.get(i));
        }
        if (itags.size() > sampleSize) {
            builder.append(",...");
        }
        builder.append(']')
                .append(", videoIdLength=").append(videoId == null ? 0 : videoId.length())
                .append(", resolution=").append(resolution);
        return builder.toString();
    }
}
