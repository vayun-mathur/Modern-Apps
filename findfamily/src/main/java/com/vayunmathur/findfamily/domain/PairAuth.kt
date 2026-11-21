package com.vayunmathur.findfamily.domain

import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Hidden per-peer sender authentication for location packets (GHSA-x94x-v6f7-wwpp).
 *
 * ML-KEM + AES-GCM encrypts *to* the recipient, proving nothing about who sent the
 * plaintext — and the `userid` inside is self-declared. Instead of a 3,309-byte
 * ML-DSA signature per message, each direction of each peer relationship holds a
 * 128-bit secret ("pair ID") that is never user-visible and never leaves the
 * ciphertext:
 *  - `outboundPairId`: what this device puts in every packet it sends to a peer.
 *  - `inboundPairId`: what this device expects from a peer; pinned trust-on-first-use.
 *
 * Both IDs are opaque strings (base64url, unpadded, 16 random bytes ≈ 22 chars).
 * Directional so neither side transmits its expectation.
 */
object PairAuth {
    /** Random bytes per pair ID (128-bit). */
    const val PAIR_ID_BYTES = 16

    /**
     * How far ahead of the receiver's clock a fix may claim before it is dropped.
     * Kills the one-message future-pin amplifier (`getLatest()` ranks on `reportedAt`).
     */
    const val MAX_FUTURE_SKEW_SECONDS = 5 * 60L

    /** Result of checking one inbound packet's pair ID against the pinned expectation. */
    enum class InboundVerdict {
        /** No pin yet and packet carries an ID — caller should pin it, then accept. */
        ACCEPT_PIN,

        /** Pinned and matches — accept. */
        ACCEPT_MATCH,

        /** No pin and no ID (legacy peer) — accept without pinning. */
        ACCEPT_LEGACY,

        /** Pinned but packet carries a different ID — drop. */
        DROP_MISMATCH,

        /** Pinned but packet carries none — drop. */
        DROP_MISSING,
    }

    /** Generates a fresh pair ID (16 bytes, base64url unpadded). */
    fun generatePairId(): String {
        val bytes = ByteArray(PAIR_ID_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.UrlSafe.encode(bytes).trimEnd('=')
    }

    /** Pure check of an inbound `pairId` against the pinned [expected] (null = unpinned). */
    fun checkInbound(expected: String?, incoming: String?): InboundVerdict {
        if (expected == null) {
            return if (incoming == null) InboundVerdict.ACCEPT_LEGACY else InboundVerdict.ACCEPT_PIN
        }
        if (incoming == null) return InboundVerdict.DROP_MISSING
        return if (incoming == expected) InboundVerdict.ACCEPT_MATCH else InboundVerdict.DROP_MISMATCH
    }

    /**
     * True when a fix claims a time more than [MAX_FUTURE_SKEW_SECONDS] ahead of [now].
     * Both `reportedAt` (send time) and `timestamp` (measure time) are sender-controlled.
     */
    fun isFromTheFuture(reportedAt: Instant, timestamp: Instant, now: Instant): Boolean {
        val skew = MAX_FUTURE_SKEW_SECONDS
        return reportedAt.epochSeconds - now.epochSeconds > skew ||
            timestamp.epochSeconds - now.epochSeconds > skew
    }

    /**
     * Clamps a sender-stamped [reportedAt] to at most [MAX_FUTURE_SKEW_SECONDS]
     * ahead of the receiver's [now]. Applied at ingest (only to incoming peer
     * reports, never to this device's own reports) so a peer with a fast clock
     * cannot pin a stale fix as newest forever via `MAX(reportedAt)`: the fix
     * is kept, but ranked no newer than the receiver allows. The slack absorbs
     * ordinary in-flight delay and small genuine clock differences.
     */
    fun clampReportedAt(reportedAt: Instant, now: Instant): Instant {
        val latestAllowed = now + MAX_FUTURE_SKEW_SECONDS.seconds
        return if (reportedAt > latestAllowed) latestAllowed else reportedAt
    }

    /** Truncated pair ID for logs — the full value must never be logged. */
    fun logPreview(pairId: String?): String = pairId?.take(4)?.let { "$it…" } ?: "absent"
}
