package org.schabi.newpipe.extractor.services.youtube;

import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Holder for batch signature / n-parameter decoding results.
 *
 * <p>PipePipe's upstream {@code YoutubeApiDecoder} routes decoding through a remote API; this fork
 * instead performs local decoding via {@link YoutubeJavaScriptPlayerManager#deobfuscateBatch}. Only
 * the {@link BatchDecodeResult} value type is kept so the {@code sabr} package can consume decoded
 * values by the same type name.</p>
 */
public final class YoutubeApiDecoder {

    private YoutubeApiDecoder() {
    }

    /**
     * Result class for batch decode operations: maps of obfuscated value to decoded value.
     */
    public static final class BatchDecodeResult {
        private final Map<String, String> signatures;
        private final Map<String, String> nParameters;

        public BatchDecodeResult(@Nonnull final Map<String, String> signatures,
                                 @Nonnull final Map<String, String> nParameters) {
            this.signatures = signatures;
            this.nParameters = nParameters;
        }

        @Nonnull
        public Map<String, String> getSignatures() {
            return signatures;
        }

        @Nonnull
        public Map<String, String> getNParameters() {
            return nParameters;
        }
    }
}
