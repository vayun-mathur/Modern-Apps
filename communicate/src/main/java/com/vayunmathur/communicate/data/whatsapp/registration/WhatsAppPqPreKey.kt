package com.vayunmathur.communicate.data.whatsapp.registration

import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType

/**
 * Post-quantum (Kyber1024 / ML-KEM) last-resort prekey for the registration `e_pq_*` bundle
 * (w2.md §2.1, Phase B 2d).
 *
 * Uses the bundled libsignal KEM API (`org.signal.libsignal.protocol.kem`) — already a dependency
 * (verified: `libsignal-client` 0.86.5 ships `KEMKeyPair`/`KEMKeyType.KYBER_1024`). The signature
 * is XEdDSA over `0x08 || pqPublicKey` using the curve25519 identity private key, mirroring the
 * classic signed-prekey convention (`WhatsAppE2E.signSignedPreKey` signs `0x05 || skeyPub`).
 *
 * `KEMPublicKey.serialize()` prepends a 1-byte type tag (`0x08` for Kyber1024); the wire
 * `e_pq_last_resort_val` is the raw 1568-byte public key, so the tag byte is stripped.
 */
object WhatsAppPqPreKey {

    /** A generated post-quantum (Kyber1024) pre-key for WhatsApp registration. */
    data class Generated(
        val keyId: Int,
        /** Raw Kyber1024 public key, 1568 bytes (no type tag). */
        val publicKey: ByteArray,
        /** Serialized KEM secret key (opaque; persisted for later use). */
        val secretKey: ByteArray,
        /** XEdDSA signature over `0x08 || publicKey`, 64 bytes. */
        val signature: ByteArray,
    )

    /**
     * The bytes signed to produce `e_pq_last_resort_sig`: `0x08 || pqPublicKey`. Pure/JVM-testable
     * (no native/libsignal calls) so the signing input framing can be asserted in unit tests.
     */
    fun signingInput(pqPublicKey: ByteArray): ByteArray {
        val out = ByteArray(1 + pqPublicKey.size)
        out[0] = WhatsAppRegistrationConstants.KEY_TYPE_KYBER
        System.arraycopy(pqPublicKey, 0, out, 1, pqPublicKey.size)
        return out
    }

    /** Strip the leading libsignal type tag from a serialized [KEMPublicKey] blob. */
    private fun stripTypeTag(serialized: ByteArray): ByteArray =
        if (serialized.size == WhatsAppRegistrationConstants.PQ_KYBER1024_PUBLIC_LEN) {
            serialized
        } else {
            serialized.copyOfRange(1, serialized.size)
        }

    /**
     * Generate a Kyber1024 last-resort prekey and sign it with the curve25519 [identityPrivate32].
     * @throws RuntimeException if native signing fails.
     */
    fun generate(identityPrivate32: ByteArray, keyId: Int): Generated {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val pub = stripTypeTag(kp.publicKey.serialize())
        val secret = kp.secretKey.serialize()
        val signature = RustWhatsAppCrypto.sign(identityPrivate32, signingInput(pub))
            ?: throw IllegalStateException("Rust sign returned null for PQ prekey")
        return Generated(keyId = keyId, publicKey = pub, secretKey = secret, signature = signature)
    }
}
