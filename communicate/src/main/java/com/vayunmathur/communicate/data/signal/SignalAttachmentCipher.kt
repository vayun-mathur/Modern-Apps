package com.vayunmathur.communicate.data.signal

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signal's attachment encryption.
 *
 * The blob on the CDN is `IV || AES-256-CBC(plaintext) || HMAC-SHA256(IV || ciphertext)`, and the
 * `AttachmentPointer` carries the 64-byte combined key (32 AES then 32 HMAC) plus a SHA-256 digest over
 * the whole blob. The server never sees the key, so an attachment uploaded without this is both readable
 * by the CDN and undecryptable by any real peer.
 */
object SignalAttachmentCipher {
    private const val KEY_SIZE = 64
    private const val AES_KEY_SIZE = 32
    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32

    /** Thrown when an attachment blob MAC does not verify (tampered or wrong key). */
    class MacMismatchException : Exception("attachment MAC did not verify")
    /** Thrown when an attachment blob digest does not match its pointer. */
    class DigestMismatchException : Exception("attachment digest did not verify")

    /** An encrypted attachment blob plus the key and digest for its AttachmentPointer. */
    data class Encrypted(
        /** The 64-byte combined key for `AttachmentPointer.key`. */
        val key: ByteArray,
        /** SHA-256 over [blob], for `AttachmentPointer.digest`. */
        val digest: ByteArray,
        /** What gets uploaded. */
        val blob: ByteArray,
        /** Plaintext length, for `AttachmentPointer.size`. */
        val plaintextSize: Int,
    )

    fun generateKey(): ByteArray = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }

    fun encrypt(plaintext: ByteArray, key: ByteArray = generateKey()): Encrypted {
        require(key.size == KEY_SIZE) { "attachment key must be $KEY_SIZE bytes, was ${key.size}" }
        val aesKey = key.copyOfRange(0, AES_KEY_SIZE)
        val macKey = key.copyOfRange(AES_KEY_SIZE, KEY_SIZE)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        val auth = mac.doFinal(ciphertext)

        val blob = ByteArray(iv.size + ciphertext.size + auth.size)
        iv.copyInto(blob)
        ciphertext.copyInto(blob, iv.size)
        auth.copyInto(blob, iv.size + ciphertext.size)

        return Encrypted(
            key = key,
            digest = MessageDigest.getInstance("SHA-256").digest(blob),
            blob = blob,
            plaintextSize = plaintext.size,
        )
    }

    /**
     * Reverse of [encrypt]. The MAC is checked before decrypting, and [digest] before that when supplied —
     * a blob that fails either is discarded rather than decrypted, since it did not come from the sender.
     *
     * [plaintextSize] trims CBC padding to the length the pointer declared; omit it to keep whatever
     * unpadding yields.
     */
    fun decrypt(
        blob: ByteArray,
        key: ByteArray,
        digest: ByteArray? = null,
        plaintextSize: Int? = null,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "attachment key must be $KEY_SIZE bytes, was ${key.size}" }
        require(blob.size > IV_SIZE + MAC_SIZE) { "attachment blob is too short: ${blob.size}" }

        if (digest != null) {
            val actual = MessageDigest.getInstance("SHA-256").digest(blob)
            if (!MessageDigest.isEqual(actual, digest)) throw DigestMismatchException()
        }

        val aesKey = key.copyOfRange(0, AES_KEY_SIZE)
        val macKey = key.copyOfRange(AES_KEY_SIZE, KEY_SIZE)

        val macOffset = blob.size - MAC_SIZE
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(blob, 0, macOffset)
        // Constant-time compare; a byte-by-byte early exit would leak the expected MAC.
        if (!MessageDigest.isEqual(mac.doFinal(), blob.copyOfRange(macOffset, blob.size))) {
            throw MacMismatchException()
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(blob.copyOfRange(0, IV_SIZE)),
        )
        val plaintext = cipher.doFinal(blob, IV_SIZE, macOffset - IV_SIZE)
        return if (plaintextSize != null && plaintextSize in 0..plaintext.size) {
            plaintext.copyOfRange(0, plaintextSize)
        } else {
            plaintext
        }
    }
}
