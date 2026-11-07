package com.vayunmathur.securemail.data

import android.content.Context
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.domain.mail.KeyLookupPolicy
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.KeyLookupHttp
import com.vayunmathur.securemail.network.MailSocket

/**
 * Sender public-key resolution shared by the receive path ([InboundStore])
 * and the send path (`MailViewModel.resolveTrust`, which keeps its own
 * trust-decision wrapper). Order: vault cache → live socket directory (own
 * domain) → WKD advanced/direct (external). Resolved keys are cached in the
 * vault for signature verification and future sends.
 */
object SenderKeys {

    /**
     * Returns the armored public key for [address], or null when no WKD key
     * exists (caller sends/stores unencrypted instead — never an error).
     */
    suspend fun resolve(context: Context, address: String): String? =
        resolve(
            store = MailRepository.get(context.applicationContext),
            address = address,
            socketKeyFetcher = { runCatching { MailSocket.getKey(it) }.getOrNull()?.armoredPublic },
            wkdKeyFetcher = { KeyLookupHttp.fetchWkdKey(it) },
        )

    /**
     * Testable core of [resolve]: the vault and both network fetchers are
     * injected so tests never reach the real relay or WKD hosts.
     */
    internal suspend fun resolve(
        store: MailStore,
        address: String,
        socketKeyFetcher: suspend (String) -> String?,
        wkdKeyFetcher: suspend (String) -> String?,
    ): String? {
        val clean = address.trim().lowercase()
        if (clean.isEmpty()) return null
        val repo = store
        repo.keyDao().get(clean)?.let { return it.armoredPublic }
        if (AddressValidator.isOwnDomain(clean)) {
            val hit = socketKeyFetcher(clean)
            if (hit != null) {
                cacheKey(repo, clean, hit, "OwnDirectory")
                return hit
            }
        }
        for (url in KeyLookupPolicy.wkdUrls(clean)) {
            val wkd = wkdKeyFetcher(url) ?: continue
            cacheKey(repo, clean, wkd, "Wkd")
            return wkd
        }
        return null
    }

    private suspend fun cacheKey(repo: MailStore, address: String, armor: String, source: String) {
        val fingerprint = runCatching { PgpCrypto.fingerprintHex(armor) }.getOrNull() ?: return
        repo.keyDao().upsert(
            CachedKey(
                address = address,
                armoredPublic = armor,
                fingerprintHex = fingerprint,
                source = source,
                updatedMillis = System.currentTimeMillis(),
            ),
        )
    }
}
