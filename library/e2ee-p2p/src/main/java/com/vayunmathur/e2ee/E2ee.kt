package com.vayunmathur.e2ee

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared end-to-end-encryption operations used across apps so they always use identical techniques:
 *  - **asymmetric** (RSA-OAEP): encrypt small payloads / wrap keys to a peer's public key,
 *  - **symmetric** (AES-256-GCM): encrypt document content with a random content key,
 *  - **verification**: the [SecurityCode] safety number from two public keys.
 *
 * The hybrid pattern for sharing a document: generate a content key with [newContentKey], encrypt
 * the document with [aesEncrypt], and give each recipient the content key wrapped via [encryptTo]
 * (to their public key). Recipients [E2eeIdentity.decrypt] the wrapped key, then [aesDecrypt].
 */
object E2ee {
    private val rsa get() = CryptographyProvider.Default.get(RSA.OAEP)

    /** Decodes a peer's public key from PEM bytes. */
    suspend fun decodePublicKeyPem(pem: ByteArray): RSA.OAEP.PublicKey =
        rsa.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM, pem)

    /** Encrypts a (small) payload to a recipient identified by their PEM public key. */
    suspend fun encryptTo(recipientPublicKeyPem: ByteArray, plaintext: ByteArray): ByteArray =
        decodePublicKeyPem(recipientPublicKeyPem).encryptor().encrypt(plaintext)

    /** Canonical DER form of a PEM public key (stable across incidental formatting differences). */
    suspend fun canonicalDer(pem: ByteArray): ByteArray =
        decodePublicKeyPem(pem).encodeToByteArray(RSA.PublicKey.Format.DER)

    /**
     * Verification security code between this device and a peer, from their PEM public keys.
     * Identical on both devices; compare out-of-band to confirm no key was substituted.
     */
    suspend fun securityCode(myPublicKeyPem: ByteArray, peerPublicKeyPem: ByteArray): String =
        SecurityCode.compute(canonicalDer(myPublicKeyPem), canonicalDer(peerPublicKeyPem))

    /** Generates a fresh RSA keypair (e.g. for an anonymous share link), returned as PEM bytes. */
    suspend fun generateKeyPair(): KeyPairPem {
        val kp = rsa.keyPairGenerator(digest = SHA512).generateKey()
        return KeyPairPem(
            kp.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM),
            kp.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM),
        )
    }

    class KeyPairPem(val publicKeyPem: ByteArray, val privateKeyPem: ByteArray)

    // --- Symmetric content encryption (AES-256-GCM) ---

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** A fresh random 256-bit content key. */
    fun newContentKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Encrypts [plaintext] with [key]; the random 12-byte IV is prepended to the ciphertext. */
    fun aesEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypts data produced by [aesEncrypt] (IV prepended). */
    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
