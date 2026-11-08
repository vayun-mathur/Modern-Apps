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
    suspend fun resolve(context: Context, address: String): String? {
        val app = context.applicationContext
        val clean = address.trim().lowercase()
        if (clean.isEmpty()) return null
        val repo = MailRepository.get(app)
        repo.keyDao().get(clean)?.let { return it.armoredPublic }
        if (AddressValidator.isOwnDomain(clean)) {
            val hit = runCatching { MailSocket.getKey(clean) }.getOrNull()
            if (hit?.armoredPublic != null) {
                cacheKey(repo, clean, hit.armoredPublic, "OwnDirectory")
                return hit.armoredPublic
            }
        }
        for (url in KeyLookupPolicy.wkdUrls(clean)) {
            val wkd = KeyLookupHttp.fetchWkdKey(url) ?: continue
            cacheKey(repo, clean, wkd, "Wkd")
            return wkd
        }
        return null
    }

    private suspend fun cacheKey(repo: MailRepository, address: String, armor: String, source: String) {
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
