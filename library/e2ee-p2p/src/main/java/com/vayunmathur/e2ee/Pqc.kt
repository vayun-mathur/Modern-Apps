package com.vayunmathur.e2ee

/**
 * Post-quantum crypto primitives for the Office app, backed by native Rust
 * (fips203 **ML-KEM-768** + fips204 **ML-DSA-65**) instead of Bouncy Castle:
 *   - ML-KEM-768 for encryption (key encapsulation + AES-256-GCM = hybrid PKE), and
 *   - ML-DSA-65 for signatures (authenticating edits and owner roster changes).
 *
 * Keys are handled as **DER** (X.509 SubjectPublicKeyInfo / PKCS#8), byte-compatible
 * with the previously-deployed Bouncy Castle encoding — including BC's SHA-256
 * single-step KDF over the ML-KEM shared secret — so existing identities and
 * ciphertexts keep working. A device's public identity is a [bundle] of its
 * ML-KEM and ML-DSA public keys.
 *
 * FindFamily additionally uses [generateLinkKey] for share links: ML-KEM only,
 * derived from a 32-byte seed small enough to fit in a URL fragment.
 */
object Pqc {
    /** Generate an ML-KEM keypair. Returns (kemPubDer, kemPrivDer). */
    fun generateKem(): Pair<ByteArray, ByteArray> {
        val kp = PqcNative.nativeMlkemKeygen() ?: error("ML-KEM keygen failed")
        return kp[0] to kp[1]
    }

    /**
     * A FindFamily share-link key. [seed] is the only secret — the ML-KEM keypair is
     * derived from it, so the seed alone is what travels in the link and what needs
     * storing. [publicBundle] has an empty ML-DSA half; nothing signs link data.
     */
    class LinkKey(val seed: ByteArray, val publicBundle: ByteArray)

    /** Generate a fresh share-link key. */
    fun generateLinkKey(): LinkKey {
        val kp = PqcNative.nativeMlkemLinkKeygen() ?: error("ML-KEM link keygen failed")
        return LinkKey(kp[0], bundle(kp[1], ByteArray(0)))
    }

    /** Re-derive a share-link public bundle from a stored 32-byte seed. */
    fun linkPublicFromSeed(seed: ByteArray): ByteArray {
        val kemPub = PqcNative.nativeMlkemLinkPubFromSeed(seed) ?: error("ML-KEM link derive failed")
        return bundle(kemPub, ByteArray(0))
    }

    /** Generate an ML-DSA keypair. Returns (dsaPubDer, dsaPrivDer). */
    fun generateDsa(): Pair<ByteArray, ByteArray> {
        val kp = PqcNative.nativeMldsaKeygen() ?: error("ML-DSA keygen failed")
        return kp[0] to kp[1]
    }

    // --- Public-key bundle = [4B kemLen][kemPub][dsaPub] ---

    fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray = lenPrefix(kemPub, dsaPub)

    private fun splitBundle(b: ByteArray): Pair<ByteArray, ByteArray> = unLenPrefix(b)

    /** Encrypts to a recipient bundle: ML-KEM encapsulate → AES-256-GCM. Layout `[4B encapLen][encap][aes]`. */
    fun encryptTo(recipientBundle: ByteArray, plaintext: ByteArray): ByteArray {
        val (kemPub, _) = splitBundle(recipientBundle)
        val enc = PqcNative.nativeMlkemEncaps(kemPub) ?: error("ML-KEM encaps failed")
        val encapsulation = enc[0]
        val sharedSecret = enc[1]
        val ct = E2ee.aesEncrypt(sharedSecret, plaintext)
        return lenPrefix(encapsulation, ct)
    }

    /** Decrypts data from [encryptTo] with this identity's ML-KEM private key (DER). */
    fun decrypt(kemPrivateDer: ByteArray, data: ByteArray): ByteArray {
        val (encap, ct) = unLenPrefix(data)
        val sharedSecret = PqcNative.nativeMlkemDecaps(kemPrivateDer, encap) ?: error("ML-KEM decaps failed")
        return E2ee.aesDecrypt(sharedSecret, ct)
    }

    fun signWith(dsaPrivateDer: ByteArray, data: ByteArray): ByteArray =
        PqcNative.nativeMldsaSign(dsaPrivateDer, data) ?: error("ML-DSA sign failed")

    fun verify(bundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val (_, dsaPub) = splitBundle(bundle)
        PqcNative.nativeMldsaVerify(dsaPub, data, signature)
    }.getOrDefault(false)

    /** Verification security code from two public bundles (identical on both devices when unmodified). */
    fun securityCode(myBundle: ByteArray, peerBundle: ByteArray): String =
        SecurityCode.compute(myBundle, peerBundle)

    /** Short fingerprint of a bundle for invite links (see [InviteFingerprint]). */
    fun inviteFingerprint(userId: Long, publicBundle: ByteArray): ByteArray =
        InviteFingerprint.compute(userId, publicBundle)

    // --- helpers ---

    private fun lenPrefix(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(LEN_PREFIX_BYTES + a.size + b.size)
        out[0] = (a.size ushr BYTE_THIRD_SHIFT).toByte()
        out[1] = (a.size ushr BYTE_SECOND_SHIFT).toByte()
        out[BYTE_SECOND_INDEX] = (a.size ushr BYTE_BITS).toByte()
        out[BYTE_THIRD_INDEX] = a.size.toByte()
        a.copyInto(out, LEN_PREFIX_BYTES)
        b.copyInto(out, LEN_PREFIX_BYTES + a.size)
        return out
    }

    private const val LEN_PREFIX_BYTES = 4
    private const val BYTE_BITS = 8
    private const val BYTE_SECOND_SHIFT = 16
    private const val BYTE_THIRD_SHIFT = 24
    private const val BYTE_SECOND_INDEX = 2
    private const val BYTE_THIRD_INDEX = 3

    private fun unLenPrefix(x: ByteArray): Pair<ByteArray, ByteArray> {
        // Bundles/ciphertexts arrive from peers and storage; a truncated or
        // lying length prefix must fail as IllegalArgumentException (the
        // failure type callers already handle for bad crypto input), not as
        // an IndexOutOfBoundsException from a raw array read.
        require(x.size >= LEN_PREFIX_BYTES) { "length-prefixed data too short: ${x.size}" }
        val len = ((x[0].toInt() and BYTE_MASK) shl BYTE_THIRD_SHIFT) or
            ((x[1].toInt() and BYTE_MASK) shl BYTE_SECOND_SHIFT) or
            ((x[BYTE_SECOND_INDEX].toInt() and BYTE_MASK) shl BYTE_BITS) or
            (x[BYTE_THIRD_INDEX].toInt() and BYTE_MASK)
        require(len >= 0 && LEN_PREFIX_BYTES + len <= x.size) { "length prefix $len exceeds size ${x.size}" }
        return x.copyOfRange(LEN_PREFIX_BYTES, LEN_PREFIX_BYTES + len) to
            x.copyOfRange(LEN_PREFIX_BYTES + len, x.size)
    }

    private const val BYTE_MASK = 0xFF
}
