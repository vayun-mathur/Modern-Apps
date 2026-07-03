package com.vayunmathur.e2ee

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512

/**
 * A device's long-lived end-to-end-encryption identity: an RSA-OAEP keypair generated on-device and
 * persisted via an [E2eeKeyStore]. The private key never leaves the device; the public key is what
 * you register in a directory / embed in a share link so others can encrypt to you.
 *
 * All apps share this so key generation, storage, and formats are identical and interoperable.
 */
class E2eeIdentity internal constructor(
    /** This identity's public key in PEM bytes (as stored / registered). */
    val publicKeyPem: ByteArray,
    private val publicKey: RSA.OAEP.PublicKey,
    private val privateKey: RSA.OAEP.PrivateKey,
) {
    /** Canonical DER encoding of the public key (stable fingerprint input). */
    suspend fun publicKeyDer(): ByteArray = publicKey.encodeToByteArray(RSA.PublicKey.Format.DER)

    /** Decrypts data that was encrypted to this identity's public key (see [E2ee.encryptTo]). */
    suspend fun decrypt(ciphertext: ByteArray): ByteArray = privateKey.decryptor().decrypt(ciphertext)

    companion object {
        private val provider get() = CryptographyProvider.Default.get(RSA.OAEP)

        /**
         * Loads the persisted keypair, generating and storing a new one on first use. Uses the same
         * [E2eeKeyStore] keys across launches so a device keeps a stable identity.
         */
        suspend fun loadOrCreate(
            store: E2eeKeyStore,
            publicName: String = "publicKey",
            privateName: String = "privateKey",
        ): E2eeIdentity {
            val p = provider
            val pubBytes = store.getBytes(publicName)
            val privBytes = store.getBytes(privateName)
            if (pubBytes != null && privBytes != null) {
                return E2eeIdentity(
                    pubBytes,
                    p.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM, pubBytes),
                    p.privateKeyDecoder(SHA512).decodeFromByteArray(RSA.PrivateKey.Format.PEM, privBytes),
                )
            }
            val kp = p.keyPairGenerator(digest = SHA512).generateKey()
            val pubPem = kp.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM)
            val privPem = kp.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM)
            store.setBytes(publicName, pubPem, onlyIfAbsent = true)
            store.setBytes(privateName, privPem, onlyIfAbsent = true)
            return E2eeIdentity(pubPem, kp.publicKey, kp.privateKey)
        }
    }
}
