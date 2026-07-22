package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

public final class SabrOnesieData {
    private final boolean encrypted;
    private final int payloadBytes;
    @Nullable
    private final SabrOnesieHeader header;
    @Nullable
    private final SabrOnesieInnertubeResponse innertubeResponse;

    private SabrOnesieData(final boolean encrypted,
                           final int payloadBytes,
                           @Nullable final SabrOnesieHeader header,
                           @Nullable final SabrOnesieInnertubeResponse innertubeResponse) {
        this.encrypted = encrypted;
        this.payloadBytes = payloadBytes;
        this.header = header;
        this.innertubeResponse = innertubeResponse;
    }

    @Nonnull
    static SabrOnesieData fromPart(@Nonnull final byte[] data,
                                   final boolean encrypted,
                                   @Nullable final SabrOnesieHeader header) {
        return new SabrOnesieData(encrypted, data.length, header,
                tryDecodeInnertubeResponse(data, encrypted, header));
    }

    @Nullable
    private static SabrOnesieInnertubeResponse tryDecodeInnertubeResponse(
            @Nonnull final byte[] data,
            final boolean encrypted,
            @Nullable final SabrOnesieHeader header) {
        if (encrypted || header == null || header.getType() != 0
                || header.hasEncryptionMaterial()) {
            return null;
        }
        final byte[] decodedData = maybeDecompress(data, header);
        if (decodedData == null) {
            return null;
        }
        try {
            return SabrOnesieInnertubeResponse.decode(decodedData);
        } catch (final SabrProtocolException ignored) {
            return null;
        }
    }

    @Nullable
    private static byte[] maybeDecompress(@Nonnull final byte[] data,
                                          @Nonnull final SabrOnesieHeader header) {
        if (header.getCryptoCompressionType() < 0 || header.getCryptoCompressionType() == 0) {
            return data;
        }
        if (header.getCryptoCompressionType() != 1) {
            return null;
        }
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (final IOException ignored) {
            return null;
        }
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public int getPayloadBytes() {
        return payloadBytes;
    }

    @Nullable
    public SabrOnesieHeader getHeader() {
        return header;
    }

    @Nullable
    public SabrOnesieInnertubeResponse getInnertubeResponse() {
        return innertubeResponse;
    }

    @Nonnull
    public String summarize() {
        if (header == null) {
            return "encrypted=" + encrypted
                    + ", payloadBytes=" + payloadBytes
                    + ", header=null"
                    + ", innertubeResponse=null";
        }
        return "encrypted=" + encrypted
                + ", payloadBytes=" + payloadBytes
                + ", headerType=" + header.getTypeSummary()
                + ", headerItag=" + (header.getItag() == null ? "null" : header.getItag())
                + ", headerSeq=" + header.getSequenceNumber()
                + ", headerCrypto=" + header.hasCryptoParams()
                + ", headerEncrypted=" + header.hasEncryptionMaterial()
                + ", innertubeResponse="
                + (innertubeResponse == null ? "null" : '[' + innertubeResponse.summarize() + ']');
    }
}
