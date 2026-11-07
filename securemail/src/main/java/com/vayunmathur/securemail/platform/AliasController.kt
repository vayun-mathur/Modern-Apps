package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.MailStore
import com.vayunmathur.securemail.domain.mail.AliasGenerator
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.MailSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Claims own addresses (the primary at onboarding, random aliases later) over
 * the relay socket. Each address gets its own keypair so correspondents cannot
 * link aliases by fingerprint; nothing is stored unless the relay accepts the
 * signed SUB. Call from Dispatchers.IO.
 */
class AliasController(private val repo: MailStore) {

    /** Waits briefly for the relay session (CHALLENGE received + SUBs sent). */
    suspend fun awaitRelay(): Boolean? =
        withTimeoutOrNull(RELAY_WAIT_MS) { MailSocket.connectedFlow.first { it } }

    /**
     * Generates a keypair for [address], claims it and stores it. Returns null
     * when the address is taken; throws on any other relay outcome.
     */
    suspend fun claim(address: String, passphrase: String, label: String, isPrimary: Boolean): MailIdentity? {
        val key = PgpCrypto.generateIdentity("$address <$address>", passphrase)
        val status = MailSocket.claim(address, key.armoredPublic) {
            PgpCrypto.signDetached(key.armoredSecret, passphrase, it)
        }
        when (status) {
            MailSocket.SUB_OK -> Unit
            MailSocket.SUB_TAKEN -> return null
            MailSocket.SUB_RATE_LIMITED -> error("Too many new addresses from this network. Try again later.")
            null -> error("Relay did not respond — try again.")
            else -> error("Relay rejected the address (code $status).")
        }
        val identity = MailIdentity(
            address = address,
            label = label,
            publicArmor = key.armoredPublic,
            fingerprintHex = key.fingerprintHex,
            secretArmor = key.armoredSecret,
            isPrimary = isPrimary,
            createdMillis = System.currentTimeMillis(),
        )
        repo.identityDao().upsert(identity)
        return identity
    }

    /** Claims a random readable alias, retrying on collisions. Returns its address. */
    suspend fun createRandom(passphrase: String, label: String): String {
        if (!MailSocket.connected) error("Relay offline — try again when connected.")
        repeat(ALIAS_ATTEMPTS) {
            val address = AliasGenerator.generate()
            if (repo.identityDao().get(address) == null) {
                claim(address, passphrase, label.trim(), isPrimary = false)?.let { return it.address }
            }
        }
        error("Couldn't find a free alias — try again.")
    }

    private companion object {
        const val RELAY_WAIT_MS = 15_000L
        const val ALIAS_ATTEMPTS = 5
    }
}
