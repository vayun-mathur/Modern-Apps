package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.PublicKey;
import java.util.Arrays;

/** Thread-safe verifier and monotonic activation boundary for signed JavaScript source. */
public final class SabrScriptPolicyManager {
    @Nullable private final PublicKey publicKey;
    @Nullable private final byte[] rawPublicKey;
    @Nullable private volatile SabrScriptPolicy active;
    private long highestRevision;

    public SabrScriptPolicyManager(@Nonnull final PublicKey key, final long minimumRevision) {
        if (minimumRevision < 0) throw new IllegalArgumentException("Invalid policy revision");
        publicKey = key;
        rawPublicKey = null;
        highestRevision = minimumRevision;
    }

    public SabrScriptPolicyManager(@Nonnull final byte[] key, final long minimumRevision) {
        if (key.length != 32 || minimumRevision < 0) {
            throw new IllegalArgumentException("Invalid policy verifier");
        }
        publicKey = null;
        rawPublicKey = key.clone();
        highestRevision = minimumRevision;
    }

    @Nonnull
    public synchronized SabrScriptPolicy verify(@Nonnull final byte[] payload,
                                                 @Nonnull final byte[] signature,
                                                 final long nowMs) {
        return rawPublicKey == null
                ? SabrScriptPolicy.parseVerified(payload, signature, publicKey,
                nowMs, highestRevision)
                : SabrScriptPolicy.parseVerified(payload, signature, rawPublicKey,
                nowMs, highestRevision);
    }

    public synchronized void activate(@Nonnull final SabrScriptPolicy verified) {
        if (verified.getRevision() < highestRevision) {
            throw new IllegalArgumentException("SABR policy rollback rejected");
        }
        if (active != null && active.getRevision() == verified.getRevision()
                && !Arrays.equals(active.serialize(), verified.serialize())) {
            throw new IllegalArgumentException("Conflicting SABR policy revision");
        }
        active = verified;
        highestRevision = verified.getRevision();
    }

    @Nonnull
    public synchronized SabrScriptPolicy install(@Nonnull final byte[] payload,
                                                  @Nonnull final byte[] signature,
                                                  final long nowMs) {
        final SabrScriptPolicy verified = verify(payload, signature, nowMs);
        activate(verified);
        return verified;
    }

    @Nullable
    public SabrScriptPolicy current(final long nowMs) {
        final SabrScriptPolicy value = active;
        return value != null && nowMs >= value.getValidFromMs() && nowMs < value.getValidUntilMs()
                ? value : null;
    }

    public synchronized long getHighestRevision() { return highestRevision; }

    public synchronized void deactivate(@Nonnull final SabrScriptPolicy expected) {
        if (active == expected) active = null;
    }
}
