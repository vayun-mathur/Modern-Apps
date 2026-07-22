package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

final class SabrColdStartPoToken {
    private static final int MAX_IDENTIFIER_BYTES = 118;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SabrColdStartPoToken() {
    }

    @Nonnull
    static byte[] generate(@Nonnull final String identifier, final int clientState)
            throws SabrProtocolException {
        final byte[] identifierBytes = identifier.getBytes(StandardCharsets.UTF_8);
        if (identifierBytes.length > MAX_IDENTIFIER_BYTES) {
            throw new SabrProtocolException("PO token identifier is too long");
        }

        final int timestamp = (int) (System.currentTimeMillis() / 1000L);
        final byte[] key = new byte[] {(byte) RANDOM.nextInt(256), (byte) RANDOM.nextInt(256)};
        final byte[] header = new byte[] {
                key[0],
                key[1],
                0,
                (byte) clientState,
                (byte) ((timestamp >> 24) & 0xff),
                (byte) ((timestamp >> 16) & 0xff),
                (byte) ((timestamp >> 8) & 0xff),
                (byte) (timestamp & 0xff)
        };

        final byte[] packet = new byte[2 + header.length + identifierBytes.length];
        packet[0] = 34;
        packet[1] = (byte) (header.length + identifierBytes.length);
        System.arraycopy(header, 0, packet, 2, header.length);
        System.arraycopy(identifierBytes, 0, packet, 2 + header.length, identifierBytes.length);

        for (int i = key.length; i < packet.length - 2; i++) {
            packet[2 + i] ^= packet[2 + (i % key.length)];
        }
        return packet;
    }
}
