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

    // --- Signatures (SHA256withRSA / PKCS#1 v1.5 — available on Android, unlike RSASSA-PSS) ---

    private fun pemToDer(pem: ByteArray): ByteArray {
        val body = pem.decodeToString().lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
        return java.util.Base64.getDecoder().decode(body)
    }

    /** Signs [data] with a private key (PEM). Used to authenticate op authorship / owner roster changes. */
    fun sign(privateKeyPem: ByteArray, data: ByteArray): ByteArray {
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pemToDer(privateKeyPem)))
        return java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(key); update(data); sign()
        }
    }

    /** Verifies a signature produced by [sign] against a public key (PEM). */
    fun verify(publicKeyPem: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(pemToDer(publicKeyPem)))
        java.security.Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(data); verify(signature)
        }
    }.getOrDefault(false)

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

    // --- Hybrid encryption (arbitrary-length payloads to a public key) ---

    /**
     * Seals an **arbitrary-length** payload to a recipient's public key: a fresh AES key encrypts
     * the payload, and only that 32-byte key is RSA-wrapped (so the RSA-OAEP size limit never
     * applies to the payload). Layout: `[2-byte wrappedKeyLen][wrappedKey][aes(iv||ct)]`.
     */
    suspend fun sealTo(recipientPublicKeyPem: ByteArray, plaintext: ByteArray): ByteArray {
        val cek = newContentKey()
        val wrapped = encryptTo(recipientPublicKeyPem, cek)
        val ct = aesEncrypt(cek, plaintext)
        val out = ByteArray(2 + wrapped.size + ct.size)
        out[0] = ((wrapped.size ushr 8) and 0xFF).toByte()
        out[1] = (wrapped.size and 0xFF).toByte()
        wrapped.copyInto(out, 2)
        ct.copyInto(out, 2 + wrapped.size)
        return out
    }

    /** Splits a [sealTo] blob into its wrapped-key and ciphertext parts. */
    internal fun splitSealed(data: ByteArray): Pair<ByteArray, ByteArray> {
        val len = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return data.copyOfRange(2, 2 + len) to data.copyOfRange(2 + len, data.size)
    }
}
