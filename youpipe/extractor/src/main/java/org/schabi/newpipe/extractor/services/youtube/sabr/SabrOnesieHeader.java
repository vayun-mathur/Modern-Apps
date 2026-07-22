package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SabrOnesieHeader {
    private final int type;
    @Nullable
    private final String videoId;
    @Nullable
    private final String itag;
    private final int cryptoParamsBytes;
    private final int cryptoHmacBytes;
    private final int cryptoIvBytes;
    private final int cryptoCompressionType;
    private final long lastModified;
    private final long expectedMediaSizeBytes;
    private final int restrictedFormatCount;
    @Nullable
    private final String xtags;
    private final long sequenceNumber;
    private final int field23VideoIdLength;
    private final int field34ItagDenylistCount;

    private SabrOnesieHeader(final int type,
                              @Nullable final String videoId,
                              @Nullable final String itag,
                              final int cryptoParamsBytes,
                              final int cryptoHmacBytes,
                              final int cryptoIvBytes,
                              final int cryptoCompressionType,
                              final long lastModified,
                             final long expectedMediaSizeBytes,
                             final int restrictedFormatCount,
                             @Nullable final String xtags,
                             final long sequenceNumber,
                             final int field23VideoIdLength,
                             final int field34ItagDenylistCount) {
        this.type = type;
        this.videoId = videoId;
        this.itag = itag;
        this.cryptoParamsBytes = cryptoParamsBytes;
        this.cryptoHmacBytes = cryptoHmacBytes;
        this.cryptoIvBytes = cryptoIvBytes;
        this.cryptoCompressionType = cryptoCompressionType;
        this.lastModified = lastModified;
        this.expectedMediaSizeBytes = expectedMediaSizeBytes;
        this.restrictedFormatCount = restrictedFormatCount;
        this.xtags = xtags;
        this.sequenceNumber = sequenceNumber;
        this.field23VideoIdLength = field23VideoIdLength;
        this.field34ItagDenylistCount = field34ItagDenylistCount;
    }

    @Nonnull
    static SabrOnesieHeader decode(@Nonnull final byte[] data) throws SabrProtocolException {
        int type = -1;
        String videoId = null;
        String itag = null;
        int cryptoParamsBytes = 0;
        int cryptoHmacBytes = 0;
        int cryptoIvBytes = 0;
        int cryptoCompressionType = -1;
        long lastModified = -1;
        long expectedMediaSizeBytes = -1;
        int restrictedFormatCount = 0;
        String xtags = null;
        long sequenceNumber = -1;
        int field23VideoIdLength = 0;
        int field34ItagDenylistCount = 0;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            switch (field.getNumber()) {
                case 1:
                    type = (int) field.getVarint();
                    break;
                case 2:
                    videoId = field.getString();
                    break;
                case 3:
                    itag = field.getString();
                    break;
                case 4:
                    final byte[] cryptoParams = field.getBytes();
                    cryptoParamsBytes = cryptoParams.length;
                    final CryptoParamsSummary cryptoParamsSummary =
                            decodeCryptoParams(cryptoParams);
                    cryptoHmacBytes = cryptoParamsSummary.hmacBytes;
                    cryptoIvBytes = cryptoParamsSummary.ivBytes;
                    cryptoCompressionType = cryptoParamsSummary.compressionType;
                    break;
                case 5:
                    lastModified = field.getVarint();
                    break;
                case 7:
                    expectedMediaSizeBytes = field.getVarint();
                    break;
                case 11:
                    restrictedFormatCount++;
                    break;
                case 15:
                    xtags = field.getString();
                    break;
                case 18:
                    sequenceNumber = field.getVarint();
                    break;
                case 23:
                    field23VideoIdLength = decodeField23VideoIdLength(field.getBytes());
                    break;
                case 34:
                    field34ItagDenylistCount = decodeField34ItagDenylistCount(field.getBytes());
                    break;
                default:
                    break;
            }
        }
        return new SabrOnesieHeader(type, videoId, itag, cryptoParamsBytes,
                cryptoHmacBytes, cryptoIvBytes, cryptoCompressionType, lastModified,
                expectedMediaSizeBytes, restrictedFormatCount, xtags, sequenceNumber,
                field23VideoIdLength, field34ItagDenylistCount);
    }

    @Nonnull
    private static CryptoParamsSummary decodeCryptoParams(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int hmacBytes = 0;
        int ivBytes = 0;
        int compressionType = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 4
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                hmacBytes = field.getBytes().length;
            } else if (field.getNumber() == 5
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                ivBytes = field.getBytes().length;
            } else if (field.getNumber() == 6
                    && field.getWireType() == SabrProto.WIRE_VARINT) {
                compressionType = (int) field.getVarint();
            }
        }
        return new CryptoParamsSummary(hmacBytes, ivBytes, compressionType);
    }

    private static int decodeField23VideoIdLength(@Nonnull final byte[] data)
            throws SabrProtocolException {
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 2
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                return field.getString().length();
            }
        }
        return 0;
    }

    private static int decodeField34ItagDenylistCount(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int count = 0;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1) {
                count++;
            }
        }
        return count;
    }

    @Nonnull
    public String summarize() {
        return "type=" + getTypeSummary()
                + ", videoIdLength=" + (videoId == null ? 0 : videoId.length())
                + ", itag=" + (itag == null ? "null" : itag)
                + ", cryptoParamsBytes=" + cryptoParamsBytes
                + ", cryptoHmacBytes=" + cryptoHmacBytes
                + ", cryptoIvBytes=" + cryptoIvBytes
                + ", cryptoCompression=" + cryptoCompressionType
                + ", lastModified=" + lastModified
                + ", expectedMediaSizeBytes=" + expectedMediaSizeBytes
                + ", restrictedFormats=" + restrictedFormatCount
                + ", xtags=" + (xtags != null)
                + ", sequenceNumber=" + sequenceNumber
                + ", field23VideoIdLength=" + field23VideoIdLength
                + ", field34ItagDenylistCount=" + field34ItagDenylistCount;
    }

    int getType() {
        return type;
    }

    @Nonnull
    String getTypeSummary() {
        return type + "/" + getTypeName();
    }

    @Nonnull
    String getTypeName() {
        switch (type) {
            case 0:
                return "ONESIE_PLAYER_RESPONSE";
            case 1:
                return "MEDIA";
            case 2:
                return "MEDIA_DECRYPTION_KEY";
            case 3:
                return "CLEAR_MEDIA";
            case 4:
                return "CLEAR_INIT_SEGMENT";
            case 5:
                return "ACK";
            case 6:
                return "MEDIA_STREAMER_HOSTNAME";
            case 7:
                return "MEDIA_SIZE_HINT";
            case 8:
                return "PLAYER_SERVICE_RESPONSE_PUSH_URL";
            case 9:
                return "LAST_HIGH_PRIORITY_HINT";
            case 16:
                return "STREAM_METADATA";
            case 25:
                return "ENCRYPTED_INNERTUBE_RESPONSE_PART";
            default:
                return "UNKNOWN";
        }
    }

    @Nullable
    String getItag() {
        return itag;
    }

    long getSequenceNumber() {
        return sequenceNumber;
    }

    boolean hasCryptoParams() {
        return cryptoParamsBytes > 0;
    }

    boolean hasEncryptionMaterial() {
        return cryptoHmacBytes > 0 || cryptoIvBytes > 0;
    }

    int getCryptoCompressionType() {
        return cryptoCompressionType;
    }

    private static final class CryptoParamsSummary {
        private final int hmacBytes;
        private final int ivBytes;
        private final int compressionType;

        private CryptoParamsSummary(final int hmacBytes,
                                    final int ivBytes,
                                    final int compressionType) {
            this.hmacBytes = hmacBytes;
            this.ivBytes = ivBytes;
            this.compressionType = compressionType;
        }
    }
}
