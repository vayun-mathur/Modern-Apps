package com.vayunmathur.communicate.data.whatsapp.e2e

import com.vayunmathur.library.log.NativeLoader


/**
 * JNI wrapper around `libwhatsapp_signal.so` (Rust crate `whatsapp_signal`).
 * All methods are pure — Kotlin owns Room persistence and passes opaque blobs.
 *
 * The nativelib is loaded once; [isAvailable] is false when the `.so` is missing
 * (e.g. on unsupported ABI) so callers can degrade gracefully.
 */
object RustWhatsAppCrypto {

    private const val TAG = "RustWhatsAppCrypto"
    private const val KEYPAIR_SIZE = 64
    private const val PRIVATE_KEY_SIZE = 32
    private const val ENCRYPT_PARTS = 3

    val isAvailable: Boolean = NativeLoader.load("communicate_signal", TAG)
    // -- Primitive key operations --

    /**
     * Generate a fresh X25519 key pair.
     * @return 64 bytes: private(32) || public(32), or null.
     */
    @JvmStatic
    external fun generateKeyPair(): ByteArray?

    @JvmStatic
    external fun publicFromPrivate(privateKey: ByteArray): ByteArray?

    @JvmStatic
    external fun x25519Agreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray?

    @JvmStatic
    external fun sign(privateKey: ByteArray, message: ByteArray): ByteArray?

    @JvmStatic
    external fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    // -- Session --

    /**
     * X3DH initiator: process a peer bundle.
     *
     * @param preKeyId -1 if no one-time prekey, else its id.
     * @param preKeyPublic null / empty when absent, else 32 bytes.
     * @return serialized SessionState bytes (Rust record version 1), or null on error.
     */
    @JvmStatic
    external fun processPreKeyBundle(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        localRegistrationId: Int,
        registrationId: Int,
        preKeyId: Int,
        preKeyPublic: ByteArray?,
        signedPreKeyId: Int,
        signedPreKeyPublic: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKey: ByteArray,
    ): ByteArray?

    /**
     * Encrypt with an existing session.
     * @return Array[3] = [isPreKey(1 byte 0/1), ciphertextBody, newSessionBytes] or null.
     */
    @JvmStatic
    external fun encrypt(sessionBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?

    /**
     * Decrypt a normal `msg`.
     * @return Array[2] = [plaintext, newSessionBytes] or null.
     */
    @JvmStatic
    external fun decryptMessage(sessionBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    /**
     * Decrypt an inbound `pkmsg`: X3DH (Bob) + first Double-Ratchet message.
     * @param oneTimePrivate null / empty if the bundle had no one-time prekey.
     * @return Array[2] = [plaintext, newSessionBytes] or null.
     */
    @JvmStatic
    external fun decryptPreKeyMessage(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): Array<ByteArray>?

    // -- Group (Sender Keys) --

    /** @return Array[2] = [stateBytes, skdmBytes] */
    @JvmStatic
    external fun createSenderKey(): Array<ByteArray>?

    @JvmStatic
    external fun processSenderKey(skdmBytes: ByteArray): ByteArray?

    /** @return Array[2] = [ciphertext, newStateBytes] */
    @JvmStatic
    external fun encryptGroup(stateBytes: ByteArray, plaintext: ByteArray): Array<ByteArray>?

    /** @return Array[2] = [plaintext, newStateBytes] */
    @JvmStatic
    external fun decryptGroup(stateBytes: ByteArray, ciphertext: ByteArray): Array<ByteArray>?

    // -- Convenience helpers used by callers --

    /** An E2E key pair from the Rust crypto core (Curve25519, split private/public). */
    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPairSplit(): KeyPair {
        val blob = generateKeyPair() ?: throw IllegalStateException("Rust generateKeyPair returned null")
        if (blob.size != KEYPAIR_SIZE) {
            throw IllegalStateException("generateKeyPair expected 64 bytes, got ${blob.size}")
        }
        return KeyPair(
            privateKey = blob.copyOfRange(0, PRIVATE_KEY_SIZE),
            publicKey = blob.copyOfRange(PRIVATE_KEY_SIZE, KEYPAIR_SIZE),
        )
    }

    /** The result of a Rust 1:1 encrypt: whether a pre-key message and the updated session record. */
    data class EncryptResult(val isPreKey: Boolean, val body: ByteArray, val newSession: ByteArray)
    fun encryptSplit(sessionBytes: ByteArray, plaintext: ByteArray): EncryptResult {
        val out = encrypt(sessionBytes, plaintext) ?: throw IllegalStateException("Rust encrypt returned null")
        if (out.size != ENCRYPT_PARTS) throw IllegalStateException("encrypt expected 3 parts, got ${out.size}")
        return EncryptResult(
            isPreKey = out[0].isNotEmpty() && out[0][0].toInt() != 0,
            body = out[1],
            newSession = out[2],
        )
    }

    /** The result of a Rust 1:1 decrypt: plaintext and the updated session record. */
    data class DecryptResult(val plaintext: ByteArray, val newSession: ByteArray)
    fun decryptMessageSplit(sessionBytes: ByteArray, ciphertext: ByteArray): DecryptResult {
        val out = decryptMessage(sessionBytes, ciphertext) ?: throw IllegalStateException("Rust decryptMessage null")
        if (out.size != 2) throw IllegalStateException("decryptMessage expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    fun decryptPreKeySplit(
        localIdentityPrivate: ByteArray,
        localIdentityPublic: ByteArray,
        signedPreKeyPrivate: ByteArray,
        oneTimePrivate: ByteArray?,
        preKeyMessageBytes: ByteArray,
    ): DecryptResult {
        val out = decryptPreKeyMessage(
            localIdentityPrivate, localIdentityPublic, signedPreKeyPrivate,
            oneTimePrivate, preKeyMessageBytes,
        ) ?: throw IllegalStateException("Rust decryptPreKeyMessage null")
        if (out.size != 2) throw IllegalStateException("decryptPreKey expected 2 parts")
        return DecryptResult(out[0], out[1])
    }

    /** A new group sender-key state plus its distribution message (SKDM). */
    data class SenderKeyCreateResult(val state: ByteArray, val skdm: ByteArray)
    fun createSenderKeySplit(): SenderKeyCreateResult {
        val out = createSenderKey() ?: throw IllegalStateException("Rust createSenderKey null")
        if (out.size != 2) throw IllegalStateException("createSenderKey expected 2 parts")
        return SenderKeyCreateResult(out[0], out[1])
    }

    /** A group ciphertext plus the updated sender-key state. */
    data class GroupCipherResult(val data: ByteArray, val newState: ByteArray)
    fun encryptGroupSplit(stateBytes: ByteArray, plaintext: ByteArray): GroupCipherResult {
        val out = encryptGroup(stateBytes, plaintext) ?: throw IllegalStateException("Rust encryptGroup null")
        if (out.size != 2) throw IllegalStateException("encryptGroup expected 2 parts")
        return GroupCipherResult(out[0], out[1])
    }

    fun decryptGroupSplit(stateBytes: ByteArray, ciphertext: ByteArray): GroupCipherResult {
        val out = decryptGroup(stateBytes, ciphertext) ?: throw IllegalStateException("Rust decryptGroup null")
        if (out.size != 2) throw IllegalStateException("decryptGroup expected 2 parts")
        return GroupCipherResult(out[0], out[1])
    }
}
