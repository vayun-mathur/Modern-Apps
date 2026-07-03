package com.vayunmathur.e2ee

/**
 * Minimal persistence abstraction used by [E2eeIdentity] to store the device keypair. Apps adapt
 * their own secure storage (e.g. an encrypted DataStore) to this interface so the e2ee module stays
 * decoupled from any particular storage implementation.
 */
interface E2eeKeyStore {
    /** Returns the stored bytes for [name], or null if absent. */
    fun getBytes(name: String): ByteArray?

    /** Persists [value] under [name]. When [onlyIfAbsent], an existing value is not overwritten. */
    suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean = false)
}
