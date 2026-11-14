package com.vayunmathur.communicate.data.signal.registration

import com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType

/**
 * Post-quantum (Kyber1024 / ML-KEM 1024) last-resort prekey for Signal registration.
 *
 * Wire correctness (from libsignal/rust/protocol/src/kem.rs + state/kyber_prekey.rs):
 * - KEMKeyPair.generate(KYBER_1024) -> publicKey.serialize() is 0x08 || 1568 bytes = 1569B
 *   (includes 0x08 KeyType byte; see kem::KeyType::Kyber1024 = 0x08)
 * - Signature is XEdDSA over the FULL serialized public key INCLUDING the 0x08 type byte:
 *   identityPrivate.calculateSignature(pub.serialize())
 *   (PreKeyUtil.java:157, libsignal KyberPreKeyRecord::generate)
 * - Server validates signature over that exact 1569B buffer; stripping the tag breaks verification.
 * - Secret key serialize() is 0x08 || 3168B = 3169B? Actually 3168 raw + 1 tag = 3169.
 *   Stored as Base64 for upload; the raw 1569B public is what goes in KyberPreKeyEntity.
 */
object SignalPqPreKey {

    private const val KYBER1024_PUB_SIZE = 1569

    /** A generated post-quantum (Kyber1024) pre-key for Signal registration. */
    data class Generated(
        val keyId: Int,
        val publicKey: ByteArray,   // 1569B = 0x08 || 1568 raw Kyber1024 pub (tag INTACT for wire)
        val secretKey: ByteArray,   // KEMSecretKey.serialize() (includes 0x08 tag)
        val signature: ByteArray,   // XEdDSA signature over publicKey (full 1569B)
    )

    /**
     * Signing input is the FULL serialized public key (1569B including 0x08 tag).
     * Do NOT strip the tag - libsignal signs pub.serialize() verbatim.
     */
    fun signingInput(serializedPublicKey1569: ByteArray): ByteArray = serializedPublicKey1569

    fun generate(identityPrivate32: ByteArray, keyId: Int): Generated {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        // KEEP the 0x08 tag - serialize() is already 0x08 || 1568 = 1569B
        val pub = kp.publicKey.serialize() // 1569B wire-correct
        require(pub.size == KYBER1024_PUB_SIZE) { "Kyber1024 pub serialize must be 1569B, got ${pub.size}" }
        val secret = kp.secretKey.serialize()
        // Real libsignal: identityPrivate.calculateSignature(pub.serialize())
        // Use ECPrivateKey bridge for XEdDSA (same curve as identity)
        val identityEcPriv = ECPrivateKey(identityPrivate32)
        val signature = identityEcPriv.calculateSignature(signingInput(pub))
        return Generated(keyId = keyId, publicKey = pub, secretKey = secret, signature = signature)
    }

    /**
     * Variant that takes an ECPrivateKey directly (avoids re-wrapping).
     */
    fun generateWithECPrivate(identityPrivate: ECPrivateKey, keyId: Int): Generated {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val pub = kp.publicKey.serialize()
        require(pub.size == KYBER1024_PUB_SIZE) { "Kyber1024 pub serialize must be 1569B, got ${pub.size}" }
        val secret = kp.secretKey.serialize()
        val signature = identityPrivate.calculateSignature(pub)
        return Generated(keyId = keyId, publicKey = pub, secretKey = secret, signature = signature)
    }
}
