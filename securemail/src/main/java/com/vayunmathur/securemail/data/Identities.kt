package com.vayunmathur.securemail.data

import android.content.Context
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.platform.crypto.KeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Own-address lookups shared by the service, sync worker, inbound path and ViewModel. */
object Identities {

    private val migrateLock = Mutex()

    /**
     * Backfills the primary [MailIdentity] for installs that predate aliases
     * (identity in [MailConfig] + secret in [KeyManager]). Idempotent.
     */
    suspend fun ensureMigrated(context: Context) {
        val app = context.applicationContext
        ensureMigrated(
            dao = MailRepository.get(app).identityDao(),
            settings = MailConfig(app).settings.first(),
            secretArmor = runCatching { KeyManager(app).getSecretArmor() }.getOrNull(),
        )
    }

    /** Store-injected core of [ensureMigrated] (same idempotent backfill). */
    internal suspend fun ensureMigrated(dao: IdentityDao, settings: MailSettings, secretArmor: String?) {
        if (dao.count() > 0) return
        migrateLock.withLock {
            if (dao.count() > 0) return
            if (!settings.isConfigured) return
            val secret = secretArmor ?: return
            dao.upsert(
                MailIdentity(
                    address = settings.address,
                    publicArmor = settings.publicArmor,
                    fingerprintHex = settings.fingerprintHex,
                    secretArmor = secret,
                    isPrimary = true,
                    createdMillis = 0L,
                ),
            )
        }
    }

    suspend fun active(context: Context): List<MailIdentity> {
        ensureMigrated(context)
        return MailRepository.get(context.applicationContext).identityDao().active()
    }

    /** The identity a message was delivered to, falling back to the primary. */
    suspend fun forDelivery(context: Context, deliveredTo: String?): MailIdentity? {
        ensureMigrated(context)
        return forDelivery(MailRepository.get(context.applicationContext).identityDao(), deliveredTo)
    }

    /** Store-injected [forDelivery] without the migration step (callers that already migrated). */
    internal suspend fun forDelivery(dao: IdentityDao, deliveredTo: String?): MailIdentity? =
        deliveredTo?.let { dao.get(it.lowercase()) } ?: dao.primary()

    /** Signs a relay SUB challenge payload with [address]'s key; null when unavailable. */
    suspend fun sign(context: Context, address: String, payload: ByteArray): ByteArray? {
        val app = context.applicationContext
        val identity = MailRepository.get(app).identityDao().get(address) ?: return null
        val passphrase = KeyManager(app).getKeyPassphrase() ?: return null
        return runCatching { PgpCrypto.signDetached(identity.secretArmor, passphrase, payload) }.getOrNull()
    }
}
