package com.vayunmathur.e2ee

// PACKAGE STRUCTURE EXCEPTION (JNI)

/**
 * JNI surface for the native Rust OpenPGP module (libe2ee_pgp.so, `pgp.rs` via
 * rPGP): Ed25519 primary + X25519 subkey generation, armor, encrypt, decrypt.
 *
 * Keys cross as ASCII armor (UTF-8 bytes); ciphertext crosses as raw PGP bytes
 * (armored only for SMTP federation via [nativePgpArmorMessage]). Null on any
 * failure — callers in [Pgp] map null to errors.
 */
internal object PgpNative {
    external fun nativePgpGenerate(userId: ByteArray, passphrase: ByteArray): Array<ByteArray>?
    external fun nativePgpFingerprint(armoredPublic: ByteArray): ByteArray?
    external fun nativePgpNormalizeKey(input: ByteArray): ByteArray?
    external fun nativePgpEncrypt(armoredPublic: ByteArray, plaintext: ByteArray): ByteArray?
    external fun nativePgpEncryptSigned(
        armoredPublic: ByteArray,
        armoredSecret: ByteArray,
        passphrase: ByteArray,
        plaintext: ByteArray,
    ): ByteArray?
    external fun nativePgpDecrypt(
        armoredSecret: ByteArray,
        passphrase: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray?
    external fun nativePgpDecryptVerify(
        armoredSecret: ByteArray,
        passphrase: ByteArray,
        ciphertext: ByteArray,
        verifierPublic: ByteArray,
    ): Array<ByteArray>?
    external fun nativePgpArmorMessage(raw: ByteArray): ByteArray?
    external fun nativePgpSignDetached(
        armoredSecret: ByteArray,
        passphrase: ByteArray,
        data: ByteArray,
    ): ByteArray?

    init {
        System.loadLibrary("e2ee_pgp")
    }
}
