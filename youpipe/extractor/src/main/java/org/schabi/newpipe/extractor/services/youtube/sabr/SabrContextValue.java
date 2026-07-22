package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrContextValue {
    @Nullable
    private final TimingInfo timingInfo;
    private final int signatureLength;
    private final int field5;

    private SabrContextValue(@Nullable final TimingInfo timingInfo,
                             final int signatureLength,
                             final int field5) {
        this.timingInfo = timingInfo;
        this.signatureLength = signatureLength;
        this.field5 = field5;
    }

    @Nonnull
    static SabrContextValue decode(@Nonnull final byte[] data) throws SabrProtocolException {
        TimingInfo timingInfo = null;
        int signatureLength = 0;
        int field5 = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                timingInfo = TimingInfo.decode(field.getBytes());
            } else if (field.getNumber() == 2
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                signatureLength = field.getBytes().length;
            } else if (field.getNumber() == 5
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                field5 = (int) field.getVarint();
            }
        }
        return new SabrContextValue(timingInfo, signatureLength, field5);
    }

    @Nullable
    public TimingInfo getTimingInfo() {
        return timingInfo;
    }

    public int getSignatureLength() {
        return signatureLength;
    }

    public int getField5() {
        return field5;
    }

    @Nonnull
    public String summarize() {
        return "timing=" + (timingInfo == null ? "null" : timingInfo.summarize())
                + ", signatureBytes=" + signatureLength
                + ", field5=" + field5;
    }

    public static final class TimingInfo {
        private final long timestampMs;
        private final int durationMs;
        @Nullable
        private final ContentInfo contentInfo;

        private TimingInfo(final long timestampMs,
                           final int durationMs,
                           @Nullable final ContentInfo contentInfo) {
            this.timestampMs = timestampMs;
            this.durationMs = durationMs;
            this.contentInfo = contentInfo;
        }

        @Nonnull
        private static TimingInfo decode(@Nonnull final byte[] data) throws SabrProtocolException {
            long timestampMs = -1;
            int durationMs = -1;
            ContentInfo contentInfo = null;
            for (final SabrProto.Field field : SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    timestampMs = field.getVarint();
                } else if (field.getNumber() == 2
                        && field.getWireType() == SabrProto.WIRE_VARINT) {
                    durationMs = (int) field.getVarint();
                } else if (field.getNumber() == 3
                        && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    contentInfo = ContentInfo.decode(field.getBytes());
                }
            }
            return new TimingInfo(timestampMs, durationMs, contentInfo);
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public int getDurationMs() {
            return durationMs;
        }

        @Nullable
        public ContentInfo getContentInfo() {
            return contentInfo;
        }

        @Nonnull
        private String summarize() {
            return "timestampMs=" + timestampMs
                    + "/durationMs=" + durationMs
                    + "/content=" + (contentInfo == null ? "null" : contentInfo.summarize());
        }
    }

    public static final class ContentInfo {
        @Nullable
        private final String contentId;
        private final int contentType;

        private ContentInfo(@Nullable final String contentId,
                            final int contentType) {
            this.contentId = contentId;
            this.contentType = contentType;
        }

        @Nonnull
        private static ContentInfo decode(@Nonnull final byte[] data) throws SabrProtocolException {
            String contentId = null;
            int contentType = -1;
            for (final SabrProto.Field field : SabrProto.readFields(data)) {
                if (field.getNumber() == 1
                        && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    contentId = field.getString();
                } else if (field.getNumber() == 2
                        && field.getWireType() == SabrProto.WIRE_VARINT) {
                    contentType = (int) field.getVarint();
                }
            }
            return new ContentInfo(contentId, contentType);
        }

        @Nullable
        public String getContentId() {
            return contentId;
        }

        public int getContentType() {
            return contentType;
        }

        @Nonnull
        private String summarize() {
            return "contentIdLength=" + (contentId == null ? 0 : contentId.length())
                    + "/contentType=" + contentType;
        }
    }
}
