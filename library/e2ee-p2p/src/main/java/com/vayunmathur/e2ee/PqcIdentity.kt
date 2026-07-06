package com.vayunmathur.e2ee

import java.security.PrivateKey

/**
 * A device's post-quantum identity for the Office app: an ML-KEM keypair (encryption) plus an
 * ML-DSA keypair (signatures), persisted via an [E2eeKeyStore]. Private keys never leave the device;
 * [publicBundle] (both public keys) is what gets registered in the directory / used to encrypt to you.
 */
class PqcIdentity internal constructor(
    val publicBundle: ByteArray,
    private val kemPrivate: PrivateKey,
    private val dsaPrivate: PrivateKey,
) {
    /** Decrypts data encrypted to this identity via [Pqc.encryptTo]. */
    fun decrypt(ciphertext: ByteArray): ByteArray = Pqc.decrypt(kemPrivate, ciphertext)

    /** Signs data with this identity's ML-DSA key. */
    fun sign(data: ByteArray): ByteArray = Pqc.signWith(dsaPrivate, data)

    companion object {
        /** Loads the persisted PQC keypairs, generating + storing them on first use. */
        suspend fun loadOrCreate(store: E2eeKeyStore, prefix: String = "pqc"): PqcIdentity {
            Pqc.ensureProvider()
            val kemPub = store.getBytes("${prefix}KemPub")
            val kemPriv = store.getBytes("${prefix}KemPriv")
            val dsaPub = store.getBytes("${prefix}DsaPub")
            val dsaPriv = store.getBytes("${prefix}DsaPriv")
            if (kemPub != null && kemPriv != null && dsaPub != null && dsaPriv != null) {
                return PqcIdentity(Pqc.bundle(kemPub, dsaPub), Pqc.kemPrivate(kemPriv), Pqc.dsaPrivate(dsaPriv))
            }
            val kem = Pqc.generateKem()
            val dsa = Pqc.generateDsa()
            store.setBytes("${prefix}KemPub", kem.public.encoded, onlyIfAbsent = true)
            store.setBytes("${prefix}KemPriv", kem.private.encoded, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPub", dsa.public.encoded, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPriv", dsa.private.encoded, onlyIfAbsent = true)
            return PqcIdentity(Pqc.bundle(kem.public.encoded, dsa.public.encoded), kem.private, dsa.private)
        }
    }
}
