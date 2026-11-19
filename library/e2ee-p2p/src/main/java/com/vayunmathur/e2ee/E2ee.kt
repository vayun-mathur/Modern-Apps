package com.vayunmathur.e2ee

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared **symmetric** encryption primitives (AES-256-GCM), used across apps so content is always
 * encrypted the same way: take a key from [newContentKey], then [aesEncrypt] / [aesDecrypt] with it.
 *
 * [Pqc] builds on this as well — `Pqc.encryptTo` / `Pqc.decrypt` AES-encrypt the payload under the
 * ML-KEM shared secret. For asymmetric operations (encrypting to a peer, signatures, and the
 * [SecurityCode] safety number) use [Pqc] and [PqcIdentity].
 */
object E2ee {
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val AES_KEY_BYTES = 32

    /** A fresh random 256-bit content key. */
    fun newContentKey(): ByteArray = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** Encrypts [plaintext] with [key]; the random 12-byte IV is prepended to the ciphertext. */
    fun aesEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypts data produced by [aesEncrypt] (IV prepended). */
    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= IV_LEN) { "ciphertext too short: ${data.size}" }
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
