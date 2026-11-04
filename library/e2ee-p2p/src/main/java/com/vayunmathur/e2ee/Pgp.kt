package com.vayunmathur.e2ee

/**
 * OpenPGP (RFC 9580) primitives for SecureMail, backed by native Rust (rPGP)
 * instead of BouncyCastle.
 *
 * Suite: Ed25519 signing primary + X25519 encryption subkey, AES-256 session
 * keys, SHA-512 digests — interoperable with ProtonMail and any OpenPGP
 * client. All key handling speaks ASCII armor; the relay wire carries raw
 * bytes and converts at the boundary only, mirroring the FindFamily relay
 * contract.
 */
object Pgp {

    /** A generated identity: armored secret ring (passphrase-wrapped) + armored public ring. */
    data class Identity(
        val armoredSecret: String,
        val armoredPublic: String,
        val fingerprintHex: String,
    )

    private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8)
    private fun ByteArray.toUtf8String(): String = toString(Charsets.UTF_8)

    /**
     * Generates an Ed25519 primary + X25519 encryption subkey for [userId]
     * (e.g. "alice <alice@maemail.org>"). The secret key material is wrapped
     * with [passphrase] via S2K so the stored blob alone is useless.
     */
    fun generateIdentity(userId: String, passphrase: String): Identity {
        val parts = PgpNative.nativePgpGenerate(userId.toUtf8(), passphrase.toUtf8())
            ?: error("OpenPGP key generation failed")
        require(parts.size == 3) { "OpenPGP key generation returned ${parts.size} parts" }
        return Identity(
            armoredSecret = parts[0].toUtf8String(),
            armoredPublic = parts[1].toUtf8String(),
            fingerprintHex = parts[2].toUtf8String(),
        )
    }

    /** Fingerprint hex (uppercase, no spaces) of an armored public key. */
    fun fingerprintHex(armoredPublic: String): String =
        PgpNative.nativePgpFingerprint(armoredPublic.toUtf8())?.toUtf8String()
            ?: error("Invalid OpenPGP public key")

    /**
     * Normalizes a WKD record (binary key or armor) to canonical armor text.
     * Throws when the input is not a parseable OpenPGP public key.
     */
    fun normalizeKey(record: ByteArray): String =
        PgpNative.nativePgpNormalizeKey(record)?.toUtf8String()
            ?: error("Invalid OpenPGP public key")

    /**
     * Encrypts [plaintext] (raw message bytes, e.g. a PGP/MIME body) to the
     * holder of [armoredPublic]. Returns raw (unarmored) PGP bytes for the
     * relay wire; armor via [armorMessage] for SMTP federation.
     */
    fun encrypt(armoredPublic: String, plaintext: ByteArray): ByteArray =
        PgpNative.nativePgpEncrypt(armoredPublic.toUtf8(), plaintext)
            ?: error("OpenPGP encryption failed")

    /**
     * Encrypts + signs: as [encrypt], additionally signing with the sender's
     * secret key. Empty [senderSecret] disables signing (same as [encrypt]).
     */
    fun encryptSigned(
        armoredPublic: String,
        senderSecret: String,
        senderPassphrase: String,
        plaintext: ByteArray,
    ): ByteArray =
        PgpNative.nativePgpEncryptSigned(
            armoredPublic.toUtf8(),
            senderSecret.toUtf8(),
            senderPassphrase.toUtf8(),
            plaintext,
        ) ?: error("OpenPGP signed encryption failed")

    /**
     * Decrypts raw PGP bytes with [armoredSecret] + [passphrase]. Returns the
     * literal payload.
     */
    fun decrypt(armoredSecret: String, passphrase: String, ciphertext: ByteArray): ByteArray =
        PgpNative.nativePgpDecrypt(armoredSecret.toUtf8(), passphrase.toUtf8(), ciphertext)
            ?: error("OpenPGP decryption failed")

    /** Result of [decryptVerify]: payload plus signer fingerprint (empty when unsigned). */
    data class Verified(val payload: ByteArray, val signerFingerprintHex: String)

    /**
     * Decrypts + verifies: as [decrypt], additionally verifying an embedded
     * signature against [verifierPublic] (empty = skip verification).
     * Unsigned messages verify cleanly with an empty fingerprint (accepted);
     * tampered signatures throw.
     */
    fun decryptVerify(
        armoredSecret: String,
        passphrase: String,
        ciphertext: ByteArray,
        verifierPublic: String,
    ): Verified {
        val parts = PgpNative.nativePgpDecryptVerify(
            armoredSecret.toUtf8(),
            passphrase.toUtf8(),
            ciphertext,
            verifierPublic.toUtf8(),
        ) ?: error("OpenPGP decrypt/verify failed")
        require(parts.size == 2) { "decryptVerify returned ${parts.size} parts" }
        return Verified(
            payload = parts[0],
            signerFingerprintHex = parts[1].toUtf8String(),
        )
    }

    /** Armors raw PGP bytes as a `PGP MESSAGE` block (for SMTP federation). */
    fun armorMessage(raw: ByteArray): String =
        PgpNative.nativePgpArmorMessage(raw)?.toUtf8String()
            ?: error("OpenPGP armor failed")

    /** Raw binary detached signature over [data] with the Ed25519 primary. */
    fun signDetached(armoredSecret: String, passphrase: String, data: ByteArray): ByteArray =
        PgpNative.nativePgpSignDetached(armoredSecret.toUtf8(), passphrase.toUtf8(), data)
            ?: error("OpenPGP detached signing failed")
}
