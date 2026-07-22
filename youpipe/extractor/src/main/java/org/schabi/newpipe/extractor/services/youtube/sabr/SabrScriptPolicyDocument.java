package org.schabi.newpipe.extractor.services.youtube.sabr;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import javax.annotation.Nonnull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Human-readable delivery document for a signed SABR JavaScript policy.
 *
 * <p>The signature covers the canonical payload returned by
 * {@link SabrScriptPolicy#serialize()}, not the JSON representation itself. A decoder therefore
 * reconstructs that payload from the signed metadata and source before verification.</p>
 */
public final class SabrScriptPolicyDocument {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 1024;

    private SabrScriptPolicyDocument() {
    }

    /** Encodes one policy and its detached signature as a single UTF-8 JSON document. */
    @Nonnull
    public static byte[] encode(@Nonnull final SabrScriptPolicy policy,
                                @Nonnull final byte[] signature) {
        validateSignature(signature);
        final JsonObject document = new JsonObject();
        document.put("format", FORMAT_VERSION);
        document.put("revision", policy.getRevision());
        document.put("validFromMs", policy.getValidFromMs());
        document.put("validUntilMs", policy.getValidUntilMs());
        document.put("source", policy.getSource());
        document.put("signature", Base64.getEncoder().encodeToString(signature));
        final byte[] encoded = JsonWriter.string(document).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("SABR policy document exceeded size limit");
        }
        return encoded;
    }

    /** Decodes JSON and reconstructs the exact payload which must be signature verified. */
    @Nonnull
    public static Parsed decode(@Nonnull final byte[] encoded) {
        if (encoded.length == 0 || encoded.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Invalid SABR policy document size");
        }
        try {
            final JsonObject document = JsonParser.object().from(
                    new String(encoded, StandardCharsets.UTF_8));
            if (document.size() != 6
                    || !document.has("revision")
                    || !document.has("validFromMs")
                    || !document.has("validUntilMs")
                    || !document.has("source")
                    || !document.has("signature")
                    || requireLong(document, "format") != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported SABR policy document");
            }
            final String source = document.getString("source");
            final String encodedSignature = document.getString("signature");
            if (source == null || encodedSignature == null) {
                throw new IllegalArgumentException("Invalid SABR policy document fields");
            }
            final byte[] signature = Base64.getDecoder().decode(encodedSignature);
            validateSignature(signature);
            final SabrScriptPolicy policy = new SabrScriptPolicy(
                    requireLong(document, "revision"),
                    requireLong(document, "validFromMs"),
                    requireLong(document, "validUntilMs"),
                    source);
            return new Parsed(policy.serialize(), signature);
        } catch (final IllegalArgumentException error) {
            throw error;
        } catch (final JsonParserException | RuntimeException error) {
            throw new IllegalArgumentException("Malformed SABR policy document", error);
        }
    }

    private static long requireLong(@Nonnull final JsonObject document,
                                    @Nonnull final String field) {
        final Object value = document.get(field);
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            try {
                return ((BigInteger) value).longValueExact();
            } catch (final ArithmeticException error) {
                throw new IllegalArgumentException(
                        "SABR policy document integer is out of range: " + field, error);
            }
        }
        throw new IllegalArgumentException(
                "SABR policy document field is not an exact integer: " + field);
    }

    private static void validateSignature(@Nonnull final byte[] signature) {
        if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Invalid SABR policy signature size");
        }
    }

    /** Canonical signed payload and its detached signature. */
    public static final class Parsed {
        @Nonnull private final byte[] payload;
        @Nonnull private final byte[] signature;

        private Parsed(@Nonnull final byte[] payload, @Nonnull final byte[] signature) {
            this.payload = payload;
            this.signature = signature;
        }

        @Nonnull
        public byte[] getPayload() {
            return payload.clone();
        }

        @Nonnull
        public byte[] getSignature() {
            return signature.clone();
        }
    }
}
