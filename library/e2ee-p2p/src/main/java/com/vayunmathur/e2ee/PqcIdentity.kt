package com.vayunmathur.e2ee

/**
 * A device's post-quantum identity for the Office app: an ML-KEM keypair (encryption) plus an
 * ML-DSA keypair (signatures), persisted via an [E2eeKeyStore]. Keys are stored/handled as DER
 * (byte-compatible with the previous Bouncy Castle encoding). Private keys never leave the device;
 * [publicBundle] (both public keys) is what gets registered in the directory / used to encrypt to you.
 */
class PqcIdentity internal constructor(
    val publicBundle: ByteArray,
    private val kemPrivate: ByteArray,
    private val dsaPrivate: ByteArray,
) {
    /** Decrypts data encrypted to this identity via [Pqc.encryptTo]. */
    fun decrypt(ciphertext: ByteArray): ByteArray = Pqc.decrypt(kemPrivate, ciphertext)

    /** Signs data with this identity's ML-DSA key. */
    fun sign(data: ByteArray): ByteArray = Pqc.signWith(dsaPrivate, data)

    /** Loading and generation entry points for [PqcIdentity]. */
    companion object {
        /**
         * Loads the persisted PQC keypairs, generating + storing them on first use.
         * Fixed race: re-read final persisted values after `onlyIfAbsent` stores
         * to avoid returning an ephemeral identity that would fail to decrypt.
         */
        suspend fun loadOrCreate(store: E2eeKeyStore, prefix: String = "pqc"): PqcIdentity {
            readPersisted(store, prefix)?.let { return it }
            generateAndStore(store, prefix)
            // Re-read final persisted (winner of concurrent race).
            return readPersisted(store, prefix) ?: error("PQC identity store returned no keys")
        }

        private suspend fun readPersisted(store: E2eeKeyStore, prefix: String): PqcIdentity? {
            val keys = PqcKeys(
                kemPub = store.getBytes("${prefix}KemPub"),
                kemPriv = store.getBytes("${prefix}KemPriv"),
                dsaPub = store.getBytes("${prefix}DsaPub"),
                dsaPriv = store.getBytes("${prefix}DsaPriv")
            )
            if (!keys.isComplete) return null
            return PqcIdentity(
                Pqc.bundle(keys.kemPub!!, keys.dsaPub!!),
                keys.kemPriv!!,
                keys.dsaPriv!!
            )
        }

        private data class PqcKeys(
            val kemPub: ByteArray?,
            val kemPriv: ByteArray?,
            val dsaPub: ByteArray?,
            val dsaPriv: ByteArray?
        ) {
            val isComplete: Boolean
                get() = kemPub != null && kemPriv != null && hasDsaPair

            private val hasDsaPair: Boolean
                get() = dsaPub != null && dsaPriv != null
        }

        private suspend fun generateAndStore(store: E2eeKeyStore, prefix: String) {
            val (kemPubNew, kemPrivNew) = Pqc.generateKem()
            val (dsaPubNew, dsaPrivNew) = Pqc.generateDsa()
            store.setBytes("${prefix}KemPub", kemPubNew, onlyIfAbsent = true)
            store.setBytes("${prefix}KemPriv", kemPrivNew, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPub", dsaPubNew, onlyIfAbsent = true)
            store.setBytes("${prefix}DsaPriv", dsaPrivNew, onlyIfAbsent = true)
        }
    }
}
