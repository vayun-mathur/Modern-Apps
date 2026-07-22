package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SabrPlaybackCookie {
    private final int resolution;
    private final int field2;
    private final int videoItag;
    private final long videoLastModified;
    private final boolean videoXtagsPresent;
    private final int audioItag;
    private final long audioLastModified;
    private final boolean audioXtagsPresent;
    @Nonnull
    private final Map<Integer, Long> extraVarints;
    @Nonnull
    private final String extraFields;

    private SabrPlaybackCookie(final int resolution,
                               final int field2,
                               final int videoItag,
                               final long videoLastModified,
                               final boolean videoXtagsPresent,
                               final int audioItag,
                                final long audioLastModified,
                                final boolean audioXtagsPresent,
                                @Nonnull final Map<Integer, Long> extraVarints,
                                @Nonnull final String extraFields) {
        this.resolution = resolution;
        this.field2 = field2;
        this.videoItag = videoItag;
        this.videoLastModified = videoLastModified;
        this.videoXtagsPresent = videoXtagsPresent;
        this.audioItag = audioItag;
        this.audioLastModified = audioLastModified;
        this.audioXtagsPresent = audioXtagsPresent;
        this.extraVarints = Collections.unmodifiableMap(new LinkedHashMap<>(extraVarints));
        this.extraFields = extraFields;
    }

    @Nonnull
    static SabrPlaybackCookie decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int resolution = -1;
        int field2 = -1;
        int videoItag = -1;
        long videoLastModified = -1;
        boolean videoXtagsPresent = false;
        int audioItag = -1;
        long audioLastModified = -1;
        boolean audioXtagsPresent = false;
        final Map<Integer, Long> extraVarints = new LinkedHashMap<>();
        final String extraFields = SabrProto.summarizeUnknownFields(data, 1, 2, 7, 8);

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    resolution = (int) field.getVarint();
                    break;
                case 2:
                    field2 = (int) field.getVarint();
                    break;
                case 7:
                    final FormatId video = decodeFormatId(field.getBytes());
                    videoItag = video.itag;
                    videoLastModified = video.lastModified;
                    videoXtagsPresent = video.xtagsPresent;
                    break;
                case 8:
                    final FormatId audio = decodeFormatId(field.getBytes());
                    audioItag = audio.itag;
                    audioLastModified = audio.lastModified;
                    audioXtagsPresent = audio.xtagsPresent;
                    break;
                default:
                    if (field.getWireType() == SabrProto.WIRE_VARINT) {
                        extraVarints.put(field.getNumber(), field.getVarint());
                    }
                    break;
            }
        }
        return new SabrPlaybackCookie(resolution, field2, videoItag, videoLastModified,
                videoXtagsPresent, audioItag, audioLastModified, audioXtagsPresent,
                extraVarints, extraFields);
    }

    @Nonnull
    private static FormatId decodeFormatId(@Nonnull final byte[] data) throws SabrProtocolException {
        int itag = -1;
        long lastModified = -1;
        boolean xtagsPresent = false;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                itag = (int) field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                lastModified = field.getVarint();
            } else if (field.getNumber() == 3
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                xtagsPresent = field.getBytes().length > 0;
            }
        }
        return new FormatId(itag, lastModified, xtagsPresent);
    }

    public int getResolution() {
        return resolution;
    }

    public int getField2() {
        return field2;
    }

    public int getVideoItag() {
        return videoItag;
    }

    public long getVideoLastModified() {
        return videoLastModified;
    }

    public boolean isVideoXtagsPresent() {
        return videoXtagsPresent;
    }

    public int getAudioItag() {
        return audioItag;
    }

    public long getAudioLastModified() {
        return audioLastModified;
    }

    public boolean isAudioXtagsPresent() {
        return audioXtagsPresent;
    }

    @Nonnull
    public Map<Integer, Long> getExtraVarints() {
        return extraVarints;
    }

    @Nonnull
    public String getExtraFields() {
        return extraFields;
    }

    @Nonnull
    public String summarize() {
        return "resolution=" + resolution
                + ", field2=" + field2
                + ", videoItag=" + videoItag
                + (videoXtagsPresent ? "+xtags" : "")
                + ", audioItag=" + audioItag
                + (audioXtagsPresent ? "+xtags" : "")
                + ", extraVarints=" + extraVarints
                + ("none".equals(extraFields) ? "" : ", extraFields=" + extraFields);
    }

    private static final class FormatId {
        private final int itag;
        private final long lastModified;
        private final boolean xtagsPresent;

        private FormatId(final int itag,
                         final long lastModified,
                         final boolean xtagsPresent) {
            this.itag = itag;
            this.lastModified = lastModified;
            this.xtagsPresent = xtagsPresent;
        }
    }
}
