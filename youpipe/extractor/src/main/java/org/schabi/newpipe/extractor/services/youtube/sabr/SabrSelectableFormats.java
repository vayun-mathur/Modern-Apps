package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SabrSelectableFormats {
    @Nonnull
    private final List<FormatId> videoFormats;
    @Nonnull
    private final List<FormatId> audioFormats;
    @Nonnull
    private final List<FormatId> wrappedVideoFormats;
    @Nonnull
    private final List<FormatId> wrappedAudioFormats;
    private final int otherFieldCount;

    private SabrSelectableFormats(@Nonnull final List<FormatId> videoFormats,
                                  @Nonnull final List<FormatId> audioFormats,
                                  @Nonnull final List<FormatId> wrappedVideoFormats,
                                  @Nonnull final List<FormatId> wrappedAudioFormats,
                                  final int otherFieldCount) {
        this.videoFormats = Collections.unmodifiableList(new ArrayList<>(videoFormats));
        this.audioFormats = Collections.unmodifiableList(new ArrayList<>(audioFormats));
        this.wrappedVideoFormats = Collections.unmodifiableList(new ArrayList<>(wrappedVideoFormats));
        this.wrappedAudioFormats = Collections.unmodifiableList(new ArrayList<>(wrappedAudioFormats));
        this.otherFieldCount = otherFieldCount;
    }

    @Nonnull
    static SabrSelectableFormats decode(@Nonnull final byte[] data) throws SabrProtocolException {
        final List<FormatId> videoFormats = new ArrayList<>();
        final List<FormatId> audioFormats = new ArrayList<>();
        final List<FormatId> wrappedVideoFormats = new ArrayList<>();
        final List<FormatId> wrappedAudioFormats = new ArrayList<>();
        int otherFieldCount = 0;

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getWireType() != SabrProto.WIRE_LENGTH_DELIMITED) {
                otherFieldCount++;
                continue;
            }
            if (field.getNumber() == 1) {
                videoFormats.add(FormatId.decode(field.getBytes()));
            } else if (field.getNumber() == 2) {
                audioFormats.add(FormatId.decode(field.getBytes()));
            } else if (field.getNumber() == 4) {
                wrappedVideoFormats.add(decodeWrappedFormatId(field.getBytes()));
            } else if (field.getNumber() == 5) {
                wrappedAudioFormats.add(decodeWrappedFormatId(field.getBytes()));
            } else {
                otherFieldCount++;
            }
        }

        return new SabrSelectableFormats(videoFormats, audioFormats, wrappedVideoFormats,
                wrappedAudioFormats, otherFieldCount);
    }

    @Nonnull
    private static FormatId decodeWrappedFormatId(@Nonnull final byte[] data)
            throws SabrProtocolException {
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                return FormatId.decode(field.getBytes());
            }
        }
        return FormatId.empty();
    }

    @Nonnull
    public List<FormatId> getVideoFormats() {
        return videoFormats;
    }

    @Nonnull
    public List<FormatId> getAudioFormats() {
        return audioFormats;
    }

    @Nonnull
    public List<FormatId> getWrappedVideoFormats() {
        return wrappedVideoFormats;
    }

    @Nonnull
    public List<FormatId> getWrappedAudioFormats() {
        return wrappedAudioFormats;
    }

    public int getOtherFieldCount() {
        return otherFieldCount;
    }

    @Nonnull
    public String summarize() {
        return "video=" + summarizeFormats(videoFormats)
                + ", audio=" + summarizeFormats(audioFormats)
                + ", wrappedVideo=" + summarizeFormats(wrappedVideoFormats)
                + ", wrappedAudio=" + summarizeFormats(wrappedAudioFormats)
                + ", otherFields=" + otherFieldCount;
    }

    @Nonnull
    private static String summarizeFormats(@Nonnull final List<FormatId> formats) {
        final StringBuilder builder = new StringBuilder();
        builder.append(formats.size()).append('[');
        final int sampleSize = Math.min(6, formats.size());
        for (int i = 0; i < sampleSize; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(formats.get(i).summarize());
        }
        if (formats.size() > sampleSize) {
            builder.append(",...");
        }
        return builder.append(']').toString();
    }

    public static final class FormatId {
        private final int itag;
        private final long lastModified;
        @Nullable
        private final String xtags;

        private FormatId(final int itag,
                         final long lastModified,
                         @Nullable final String xtags) {
            this.itag = itag;
            this.lastModified = lastModified;
            this.xtags = xtags;
        }

        @Nonnull
        private static FormatId decode(@Nonnull final byte[] data) throws SabrProtocolException {
            int itag = -1;
            long lastModified = -1;
            String xtags = null;
            for (final SabrProto.Field field : SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    itag = (int) field.getVarint();
                } else if (field.getNumber() == 2
                        && field.getWireType() == SabrProto.WIRE_VARINT) {
                    lastModified = field.getVarint();
                } else if (field.getNumber() == 3
                        && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    xtags = field.getString();
                }
            }
            return new FormatId(itag, lastModified, xtags);
        }

        @Nonnull
        private static FormatId empty() {
            return new FormatId(-1, -1, null);
        }

        public int getItag() {
            return itag;
        }

        public long getLastModified() {
            return lastModified;
        }

        @Nullable
        public String getXtags() {
            return xtags;
        }

        @Nonnull
        private String summarize() {
            return "itag:" + itag
                    + (lastModified >= 0 ? "+lm" : "")
                    + (xtags != null ? "+xtags" : "");
        }
    }
}
