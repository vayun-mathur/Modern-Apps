package org.schabi.newpipe.extractor.services.youtube.sabr;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;

/** Signed metadata and ordinary JavaScript source. This is a container, not a policy language. */
public final class SabrScriptPolicy {
    private static final int MAGIC = 0x534A5331;
    private static final int VERSION = 1;
    private static final int MAX_SOURCE_BYTES = 512 * 1024;
    private final long revision;
    private final long validFromMs;
    private final long validUntilMs;
    @Nonnull private final String source;
    @Nonnull private final byte[] payload;

    public SabrScriptPolicy(final long revision, final long validFromMs,
                            final long validUntilMs, @Nonnull final String source) {
        if (revision < 0 || validFromMs < 0 || validUntilMs <= validFromMs
                || source.isEmpty()) {
            throw new IllegalArgumentException("Invalid SABR JavaScript policy");
        }
        this.revision = revision;
        this.validFromMs = validFromMs;
        this.validUntilMs = validUntilMs;
        this.source = source;
        this.payload = encode(revision, validFromMs, validUntilMs, source);
    }

    private SabrScriptPolicy(final long revision, final long validFromMs,
                             final long validUntilMs, @Nonnull final String source,
                             @Nonnull final byte[] payload) {
        this.revision = revision;
        this.validFromMs = validFromMs;
        this.validUntilMs = validUntilMs;
        this.source = source;
        this.payload = payload;
    }

    public long getRevision() { return revision; }
    public long getValidFromMs() { return validFromMs; }
    public long getValidUntilMs() { return validUntilMs; }
    @Nonnull public String getSource() { return source; }
    @Nonnull public byte[] serialize() { return payload.clone(); }

    @Nonnull
    public static SabrScriptPolicy parseVerified(@Nonnull final byte[] payload,
                                                  @Nonnull final byte[] signature,
                                                  @Nonnull final PublicKey key,
                                                  final long nowMs,
                                                  final long minimumRevision) {
        try {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new IllegalArgumentException(
                    "Invalid SABR JavaScript policy signature");
        } catch (final GeneralSecurityException error) {
            throw new IllegalArgumentException("Could not verify SABR JavaScript policy", error);
        }
        return parse(payload, nowMs, minimumRevision);
    }

    @Nonnull
    public static SabrScriptPolicy parseVerified(@Nonnull final byte[] payload,
                                                  @Nonnull final byte[] signature,
                                                  @Nonnull final byte[] rawKey,
                                                  final long nowMs,
                                                  final long minimumRevision) {
        if (rawKey.length != Ed25519PublicKeyParameters.KEY_SIZE) {
            throw new IllegalArgumentException("Invalid Ed25519 public key");
        }
        final Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(rawKey));
        verifier.update(payload, 0, payload.length);
        if (!verifier.verifySignature(signature)) {
            throw new IllegalArgumentException("Invalid SABR JavaScript policy signature");
        }
        return parse(payload, nowMs, minimumRevision);
    }

    @Nonnull
    private static SabrScriptPolicy parse(@Nonnull final byte[] payload, final long nowMs,
                                          final long minimumRevision) {
        if (payload.length == 0 || payload.length > MAX_SOURCE_BYTES + 64) {
            throw new IllegalArgumentException("Invalid SABR JavaScript policy size");
        }
        try {
            final DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("Unsupported SABR JavaScript policy");
            }
            final long revision = input.readLong();
            final long from = input.readLong();
            final long until = input.readLong();
            final int size = input.readInt();
            if (revision < minimumRevision || from < 0 || until <= from
                    || size <= 0 || size > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("Invalid SABR JavaScript policy metadata");
            }
            final byte[] source = new byte[size];
            input.readFully(source);
            if (input.available() != 0) throw new IllegalArgumentException(
                    "Trailing SABR JavaScript policy bytes");
            if (nowMs < from || nowMs >= until) throw new IllegalArgumentException(
                    "SABR JavaScript policy is not currently valid");
            return new SabrScriptPolicy(revision, from, until,
                    new String(source, StandardCharsets.UTF_8), payload.clone());
        } catch (final IOException error) {
            throw new IllegalArgumentException("Malformed SABR JavaScript policy", error);
        }
    }

    @Nonnull
    private static byte[] encode(final long revision, final long from, final long until,
                                 @Nonnull final String source) {
        final byte[] script = source.getBytes(StandardCharsets.UTF_8);
        if (script.length == 0 || script.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Invalid SABR JavaScript source size");
        }
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeLong(revision);
            output.writeLong(from);
            output.writeLong(until);
            output.writeInt(script.length);
            output.write(script);
            output.flush();
            return bytes.toByteArray();
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
