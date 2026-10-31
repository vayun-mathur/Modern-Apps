package com.vayunmathur.backup.platform.crypto

import android.content.Context
import android.util.Base64
import com.vayunmathur.e2ee.Hkdf
import com.vayunmathur.library.util.DatabaseHelper

/**
 * Persists the AES-256 master backup key, wrapped by an AndroidKeyStore key, reusing
 * the repo's [DatabaseHelper] keystore pattern. The master key is derived from the
 * BIP-0039 seed via [Hkdf] so the twelve-word recovery code alone can reconstruct it
 * on a new device; here it is cached (keystore-wrapped) so routine backups don't
 * re-prompt for the code. An optional biometric gate can wrap this via
 * :library:biometric's BiometricDatabaseHelper.
 */
class KeyManager(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "backup_master_key"
    override val sharedPrefsName = "backup_secure_prefs"
    override val passphraseKey = "encrypted_master_key"
    override val ivKey = "master_key_iv"

    fun hasMasterKey(): Boolean = isKeyGenerated()

    /** Derives and persists the AES-256 master key from a BIP-0039 [seed]. */
    fun storeSeed(seed: ByteArray) {
        storePassphrase(Base64.encodeToString(deriveMasterKey(seed), Base64.NO_WRAP))
    }

    fun getMasterKey(): ByteArray = Base64.decode(getPassphrase(), Base64.NO_WRAP)

    fun clear() = deleteKey()

    companion object {
        private val MASTER_KEY_INFO =
            "com.vayunmathur.backup/master-key".toByteArray(Charsets.UTF_8)
        private const val MASTER_KEY_SIZE_BYTES = 32

        /** Derives the 32-byte AES-256 master key from a BIP-0039 seed. */
        fun deriveMasterKey(seed: ByteArray): ByteArray =
            Hkdf.derive(seed, MASTER_KEY_INFO, MASTER_KEY_SIZE_BYTES)
    }
}
