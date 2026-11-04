package com.vayunmathur.securemail.domain.pgp

import com.vayunmathur.e2ee.Pgp

/**
 * OpenPGP facade for SecureMail. All crypto runs in the native Rust core
 * ([Pgp] in `:library:e2ee-p2p`, rPGP) — no BouncyCastle anywhere.
 *
 * Suite: Ed25519 signing primary + X25519 encryption subkey, AES-256 session
 * keys, SHA-512 digests. Keys cross as ASCII armor; the relay wire carries raw
 * bytes and converts at the boundary only, mirroring the FindFamily relay
 * contract.
 */
object PgpCrypto {

    /** A generated identity: armored secret ring (passphrase-wrapped) + armored public ring. */
    data class Identity(
        val armoredSecret: String,
        val armoredPublic: String,
        val fingerprintHex: String,
    )

    /**
     * Generates an Ed25519 primary + X25519 encryption subkey for [userId]
     * (e.g. "alice <alice@maemail.org>"). The secret key material is wrapped
     * with [passphrase] via S2K so the stored blob alone is useless.
     */
    fun generateIdentity(userId: String, passphrase: String): Identity {
        val native = Pgp.generateIdentity(userId, passphrase)
        return Identity(
            armoredSecret = native.armoredSecret,
            armoredPublic = native.armoredPublic,
            fingerprintHex = native.fingerprintHex,
        )
    }

    /** Fingerprint hex (uppercase, no spaces) of an armored public key. */
    fun fingerprintHex(armoredPublic: String): String =
        Pgp.fingerprintHex(armoredPublic)

    /**
     * Encrypts [plaintext] (raw message bytes, e.g. a PGP/MIME body) to the
     * holder of [armoredPublic]. Returns raw (unarmored) PGP bytes for the
     * relay wire; armor for SMTP federation.
     */
    fun encrypt(plaintext: ByteArray, armoredPublic: String): ByteArray =
        Pgp.encrypt(armoredPublic, plaintext)

    /**
     * Encrypts + signs with the sender's secret key. Empty [senderSecret]
     * disables signing (same as [encrypt]).
     */
    fun encryptSigned(
        plaintext: ByteArray,
        armoredPublic: String,
        senderSecret: String,
        senderPassphrase: String,
    ): ByteArray = Pgp.encryptSigned(armoredPublic, senderSecret, senderPassphrase, plaintext)

    /**
     * Decrypts raw PGP bytes with [armoredSecret] + [passphrase]. Returns the
     * literal payload.
     */
    fun decrypt(ciphertext: ByteArray, armoredSecret: String, passphrase: String): ByteArray =
        Pgp.decrypt(armoredSecret, passphrase, ciphertext)

    /** Result of [decryptVerify]: payload plus signer fingerprint (empty when unsigned). */
    data class Verified(val payload: ByteArray, val signerFingerprintHex: String)

    /**
     * Decrypts + verifies against [verifierPublic] (empty = skip verification).
     * Unsigned messages verify cleanly with an empty fingerprint (accepted);
     * tampered signatures throw.
     */
    fun decryptVerify(
        ciphertext: ByteArray,
        armoredSecret: String,
        passphrase: String,
        verifierPublic: String,
    ): Verified {
        val native = Pgp.decryptVerify(armoredSecret, passphrase, ciphertext, verifierPublic)
        return Verified(
            payload = native.payload,
            signerFingerprintHex = native.signerFingerprintHex,
        )
    }

    /** Armors raw PGP bytes as a `PGP MESSAGE` block (for SMTP federation). */
    fun armorMessage(raw: ByteArray): String = Pgp.armorMessage(raw)

    /** Raw detached signature over [data] (relay SUB challenge). */
    fun signDetached(armoredSecret: String, passphrase: String, data: ByteArray): ByteArray =
        Pgp.signDetached(armoredSecret, passphrase, data)
}
