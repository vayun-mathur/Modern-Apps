package com.vayunmathur.youpipe.util.sabr;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.BuiltinSabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaHeader;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaProtocol;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicyDocument;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicyManager;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyTranscript;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;

/** Single construction boundary for the bundled SABR policy set and its per-session transcripts. */
public final class SabrPolicyRuntime {
    public enum BenchmarkPolicyMode { AUTO, BUILTIN, CLOUD }

    // Config holder replacing org.schabi.newpipe.BuildConfig (buildConfig is disabled in youpipe).
    // Blank public key + URL keep the remote signed-policy mechanism DISABLED BY DEFAULT so that
    // playback uses BuiltinSabrSessionPolicy. Deployments that ship a signed policy set these.
    static final String SABR_POLICY_PUBLIC_KEY_BASE64 = "";
    static final String SABR_POLICY_URL = "";
    private static final boolean DEBUG = false;

    private static final int SESSION_TRANSCRIPT_CAPACITY = 512;
    private static final int CACHE_MAGIC = 0x53504348;
    private static final int CACHE_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 1024;
    private static final String CACHE_FILE = "sabr-cloud-policy.bin";
    private static final String REVISION_FILE = "sabr-cloud-policy.rev";
    @NonNull private static final SabrSessionPolicy FALLBACK = new BuiltinSabrSessionPolicy();
    @Nullable private static volatile SabrScriptPolicyManager cloudPolicies;
    @Nullable private static volatile AtomicFile policyCache;
    @Nullable private static volatile AtomicFile revisionCache;
    @NonNull private static volatile BenchmarkPolicyMode benchmarkPolicyMode =
            BenchmarkPolicyMode.AUTO;

    private SabrPolicyRuntime() {
    }

    @NonNull
    public static SabrSessionPolicyHost createSessionHost() {
        final BenchmarkPolicyMode benchmarkMode = benchmarkPolicyMode;
        if (benchmarkMode == BenchmarkPolicyMode.BUILTIN) {
            return createHost(FALLBACK);
        }
        final SabrScriptPolicyManager manager = cloudPolicies;
        SabrSessionPolicy policy = FALLBACK;
        final SabrScriptPolicy script = manager == null
                ? null : manager.current(System.currentTimeMillis());
        if (script != null) {
            try {
                policy = new FailoverPolicy(script, createScriptPolicy(script));
            } catch (final SabrProtocolException ignored) {
                policy = FALLBACK;
            }
        }
        if (benchmarkMode == BenchmarkPolicyMode.CLOUD && policy == FALLBACK) {
            throw new IllegalStateException("No active SABR cloud policy for benchmark");
        }
        return createHost(policy);
    }

    public static void setBenchmarkPolicyMode(@NonNull final BenchmarkPolicyMode mode) {
        if (!DEBUG) {
            throw new IllegalStateException("SABR benchmark policy override requires debug build");
        }
        benchmarkPolicyMode = mode;
    }

    @NonNull
    private static SabrSessionPolicyHost createHost(@NonNull final SabrSessionPolicy policy) {
        return new SabrSessionPolicyHost(policy,
                new SabrSessionPolicyTranscript(SESSION_TRANSCRIPT_CAPACITY));
    }

    /** Configures cloud policy verification and restores the last verified cached envelope. */
    public static synchronized void initialize(@NonNull final Context context,
                                               @NonNull final PublicKey publicKey,
                                               final long minimumRevision) {
        final SabrScriptPolicyManager manager = new SabrScriptPolicyManager(publicKey,
                Math.max(minimumRevision, readHighestRevision(context)));
        initialize(context, manager);
    }

    private static synchronized void initialize(@NonNull final Context context,
                                                @NonNull final SabrScriptPolicyManager manager) {
        final AtomicFile cache = new AtomicFile(new File(
                context.getApplicationContext().getFilesDir(), CACHE_FILE));
        final AtomicFile revisions = new AtomicFile(new File(
                context.getApplicationContext().getFilesDir(), REVISION_FILE));
        try {
            final Envelope envelope = decodeEnvelope(cache.readFully());
            final SabrScriptPolicy verified = manager.verify(
                    envelope.payload, envelope.signature, System.currentTimeMillis());
            createScriptPolicy(verified).close();
            manager.activate(verified);
        } catch (final IOException | IllegalArgumentException | SabrProtocolException ignored) {
            // Missing, expired, or invalid cache must never prevent startup or builtin playback.
        }
        cloudPolicies = manager;
        policyCache = cache;
        revisionCache = revisions;
    }

    /** Initializes from a deployment-provided raw 32-byte Ed25519 key. Empty means builtin only. */
    public static synchronized void initialize(@NonNull final Context context,
                                               @Nullable final String publicKeyBase64,
                                               final long minimumRevision) {
        if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
            cloudPolicies = null;
            policyCache = null;
            revisionCache = null;
            return;
        }
        try {
            final byte[] key = Base64.decode(publicKeyBase64, Base64.DEFAULT);
            final SabrScriptPolicyManager manager = new SabrScriptPolicyManager(key,
                    Math.max(minimumRevision, readHighestRevision(context)));
            initialize(context, manager);
        } catch (final IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid SABR cloud policy public key", error);
        }
    }

    /** Verifies, activates, and atomically persists a downloaded policy envelope. */
    public static synchronized void install(@NonNull final byte[] payload,
                                            @NonNull final byte[] signature,
                                            final long nowMs) throws IOException {
        final SabrScriptPolicyManager manager = cloudPolicies;
        final AtomicFile cache = policyCache;
        final AtomicFile revisions = revisionCache;
        if (manager == null || cache == null || revisions == null) {
            throw new IllegalStateException("SABR cloud policy runtime is not initialized");
        }
        final SabrScriptPolicy verified = manager.verify(payload, signature, nowMs);
        try {
            createScriptPolicy(verified).close();
        } catch (final SabrProtocolException error) {
            throw new IOException("Invalid SABR JavaScript policy", error);
        }
        writeRevision(revisions, verified.getRevision());
        try {
            writeEnvelope(cache, encodeEnvelope(payload, signature));
        } catch (final IOException error) {
            cache.delete();
            throw error;
        }
        manager.activate(verified);
    }

    /** Installs a signed, human-readable JSON policy document received from remote delivery. */
    public static void installDocument(@NonNull final byte[] encoded, final long nowMs)
            throws IOException {
        final SabrScriptPolicyDocument.Parsed document;
        try {
            document = SabrScriptPolicyDocument.decode(encoded);
        } catch (final IllegalArgumentException error) {
            throw new IOException("Invalid SABR cloud policy document", error);
        }
        install(document.getPayload(), document.getSignature(), nowMs);
    }

    @NonNull
    private static SabrSessionPolicy createScriptPolicy(@NonNull final SabrScriptPolicy script)
            throws SabrProtocolException {
        return new QuickJsSabrSessionPolicy(script);
    }

    private static synchronized void disable(@NonNull final SabrScriptPolicy script) {
        final SabrScriptPolicyManager manager = cloudPolicies;
        if (manager != null) manager.deactivate(script);
        final AtomicFile cache = policyCache;
        if (cache != null) cache.delete();
    }

    private static final class FailoverPolicy implements SabrSessionPolicy {
        @NonNull private final SabrScriptPolicy script;
        @NonNull private final SabrSessionPolicy primary;
        @NonNull private final SabrMediaProtocol mediaProtocol;
        private boolean failed;

        private FailoverPolicy(@NonNull final SabrScriptPolicy script,
                               @NonNull final SabrSessionPolicy primary) {
            this.script = script;
            this.primary = primary;
            final SabrMediaProtocol primaryMedia = primary.getMediaProtocol();
            mediaProtocol = new SabrMediaProtocol() {
                @Override public int getHeaderPartType() {
                    return currentMediaProtocol(primaryMedia).getHeaderPartType();
                }
                @Override public int getMediaPartType() {
                    return currentMediaProtocol(primaryMedia).getMediaPartType();
                }
                @Override public int getEndPartType() {
                    return currentMediaProtocol(primaryMedia).getEndPartType();
                }
                @NonNull @Override public SabrMediaHeader decodeHeader(@NonNull final byte[] payload)
                        throws SabrProtocolException {
                    if (failed) return SabrMediaProtocol.builtin().decodeHeader(payload);
                    try {
                        return primaryMedia.decodeHeader(payload);
                    } catch (final RuntimeException | SabrProtocolException error) {
                        fail();
                        return SabrMediaProtocol.builtin().decodeHeader(payload);
                    }
                }
            };
        }

        @NonNull
        @Override
        public SabrMediaProtocol getMediaProtocol() {
            return mediaProtocol;
        }

        @NonNull
        @Override
        public Result evaluate(@NonNull final State state, @NonNull final Event event)
                throws SabrProtocolException {
            if (failed) return FALLBACK.evaluate(state, event);
            try {
                return primary.evaluate(state, event);
            } catch (final RuntimeException | SabrProtocolException error) {
                fail();
                return FALLBACK.evaluate(state, event);
            }
        }

        @NonNull
        @Override
        public DemandRoute evaluateDemandRoute(@NonNull final DemandRouteEvent event)
                throws SabrProtocolException {
            if (failed) return FALLBACK.evaluateDemandRoute(event);
            try {
                return primary.evaluateDemandRoute(event);
            } catch (final RuntimeException | SabrProtocolException error) {
                fail();
                return FALLBACK.evaluateDemandRoute(event);
            }
        }

        @NonNull
        @Override
        public DemandResponseDecision evaluateDemandResponse(
                @NonNull final DemandResponseEvent event) throws SabrProtocolException {
            if (failed) return FALLBACK.evaluateDemandResponse(event);
            try {
                return primary.evaluateDemandResponse(event);
            } catch (final RuntimeException | SabrProtocolException error) {
                fail();
                return FALLBACK.evaluateDemandResponse(event);
            }
        }

        @NonNull
        private SabrMediaProtocol currentMediaProtocol(@NonNull final SabrMediaProtocol primaryMedia) {
            return failed ? SabrMediaProtocol.builtin() : primaryMedia;
        }

        private void fail() {
            if (!failed) {
                failed = true;
                disable(script);
                try {
                    primary.close();
                } catch (final RuntimeException ignored) {
                }
            }
        }

        @Override
        public void close() {
            primary.close();
        }
    }

    @NonNull
    static byte[] encodeEnvelope(@NonNull final byte[] payload,
                                 @NonNull final byte[] signature) {
        validateLengths(payload.length, signature.length);
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(CACHE_MAGIC);
            output.writeByte(CACHE_VERSION);
            output.writeInt(payload.length);
            output.writeInt(signature.length);
            output.write(payload);
            output.write(signature);
            output.flush();
            return bytes.toByteArray();
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @NonNull
    static Envelope decodeEnvelope(@NonNull final byte[] encoded) throws IOException {
        final DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != CACHE_MAGIC || input.readUnsignedByte() != CACHE_VERSION) {
            throw new IOException("Unsupported SABR policy cache");
        }
        final int payloadLength = input.readInt();
        final int signatureLength = input.readInt();
        validateLengths(payloadLength, signatureLength);
        final byte[] payload = new byte[payloadLength];
        final byte[] signature = new byte[signatureLength];
        input.readFully(payload);
        input.readFully(signature);
        if (input.available() != 0) throw new IOException("Trailing SABR policy cache bytes");
        return new Envelope(payload, signature);
    }

    private static void validateLengths(final int payloadLength, final int signatureLength) {
        if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES
                || signatureLength <= 0 || signatureLength > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Invalid SABR policy cache size");
        }
    }

    private static void writeEnvelope(@NonNull final AtomicFile cache,
                                      @NonNull final byte[] encoded) throws IOException {
        FileOutputStream output = null;
        try {
            output = cache.startWrite();
            output.write(encoded);
            output.flush();
            cache.finishWrite(output);
        } catch (final IOException error) {
            if (output != null) cache.failWrite(output);
            throw error;
        }
    }

    private static long readHighestRevision(@NonNull final Context context) {
        final AtomicFile file = new AtomicFile(new File(
                context.getApplicationContext().getFilesDir(), REVISION_FILE));
        try {
            final DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(file.readFully()));
            final long revision = input.readLong();
            return input.available() == 0 && revision >= 0 ? revision : 0;
        } catch (final IOException ignored) {
            return 0;
        }
    }

    private static void writeRevision(@NonNull final AtomicFile file, final long revision)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Long.BYTES);
        final DataOutputStream output = new DataOutputStream(bytes);
        output.writeLong(revision);
        output.flush();
        writeEnvelope(file, bytes.toByteArray());
    }

    static final class Envelope {
        @NonNull final byte[] payload;
        @NonNull final byte[] signature;
        private Envelope(@NonNull final byte[] payload, @NonNull final byte[] signature) {
            this.payload = payload;
            this.signature = signature;
        }
    }
}
