package com.vayunmathur.securemail.domain.mail

/**
 * Pure recipient-key lookup + fallback decision (no Android imports, fully
 * unit-testable).
 *
 * Key discovery is Web Key Directory only (RFC 7929):
 *   1. own relay directory over the live socket for `@maemail.org` (which the
 *      server also serves as WKD at
 *      `https://mail.maemail.org/.well-known/openpgpkey/...`, so external
 *      clients discover us the same way), plus the local vault cache;
 *   2. WKD (advanced then direct method) for every external address —
 *      Proton serves it on `proton.me`, Gmail and others on their own domains;
 *   3. unencrypted fallback with explicit per-message opt-in when WKD has no key.
 *
 * Never silently downgrade: an address that previously had a key requires an
 * explicit key-change acknowledgement before sending to a new key.
 */
object KeyLookupPolicy {

    /** Where a recipient's usable public key came from, or why there is none. */
    sealed interface KeySource {
        data object OwnDirectory : KeySource
        data object Wkd : KeySource
        data object Cached : KeySource
        data object None : KeySource
    }

    /** Trust state of one recipient for the compose screen. */
    data class RecipientTrust(
        val address: String,
        val hasKey: Boolean,
        val source: KeySource,
        val fingerprintHex: String? = null,
        /** True when the cached fingerprint differs from the directory's. */
        val keyChanged: Boolean = false,
        /** True when a previous key existed but none is found now. */
        val keyLost: Boolean = false,
    )

    /** Send-gate decision for the current recipient set. */
    sealed interface SendDecision {
        /** Every recipient has a usable key — send encrypted. */
        data object Encrypted : SendDecision
        /**
         * Some recipients lack keys — they receive the message unencrypted
         * (badged `plain` on receipt). No opt-in; no WKD key means no
         * encryption for those recipients.
         */
        data class UnencryptedFallback(val missing: List<String>) : SendDecision
        /** A known key changed — the user must acknowledge before sending. */
        data class NeedsKeyChangeAck(val changed: List<RecipientTrust>) : SendDecision
    }

    fun decide(recipients: List<RecipientTrust>): SendDecision {
        val changed = recipients.filter { it.keyChanged || it.keyLost }
        if (changed.isNotEmpty()) return SendDecision.NeedsKeyChangeAck(changed)
        val missing = recipients.filter { !it.hasKey }.map { it.address }
        return if (missing.isEmpty()) SendDecision.Encrypted
        else SendDecision.UnencryptedFallback(missing)
    }

    /**
     * WKD lookup URLs for [address] (RFC 7929): the advanced method
     * (`openpgpkey.<domain>`) first, then the direct method (`<domain>`).
     * The local part is lowercased, SHA-1 hashed, and z-base-32 encoded; the
     * original local part also rides as `?l=` (WKD draft §3.1), which Proton
     * requires - without it openpgpkey.proton.me answers 400.
     */
    fun wkdUrls(address: String): List<String> {
        val at = address.lastIndexOf('@')
        if (at <= 0) return emptyList()
        val rawLocal = address.substring(0, at)
        val local = rawLocal.lowercase()
        val domain = address.substring(at + 1).lowercase()
        if (local.isEmpty() || domain.isEmpty()) return emptyList()
        val hash = wkdHash(local)
        val l = java.net.URLEncoder.encode(rawLocal, "UTF-8")
        return listOf(
            "https://openpgpkey.$domain/.well-known/openpgpkey/$domain/hu/$hash?l=$l",
            "https://$domain/.well-known/openpgpkey/hu/$hash?l=$l",
        )
    }

    /** Single (direct-method) WKD URL, for callers that try only one. */
    fun wkdUrl(address: String): String? = wkdUrls(address).lastOrNull()

    internal fun wkdHash(local: String): String {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest(local.toByteArray(Charsets.UTF_8))
        return zBase32(digest)
    }

    // RFC 7929 z-base-32 alphabet.
    private const val ZBASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"

    internal fun zBase32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ZBASE32[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) out.append(ZBASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
