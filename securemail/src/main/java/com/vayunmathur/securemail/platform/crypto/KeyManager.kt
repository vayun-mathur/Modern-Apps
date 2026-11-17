package com.vayunmathur.securemail.platform.crypto

import android.content.Context
import androidx.core.content.edit
import android.util.Base64
import com.vayunmathur.library.util.DatabaseHelper

/**
 * Custody for the SecureMail identity: the passphrase-wrapped PGP secret ring
 * plus the S2K passphrase itself and the relay address token, all wrapped by
 * an AndroidKeyStore key reusing the repo's [DatabaseHelper] pattern (see
 * backup's KeyManager).
 *
 * Layers: the PGP private key is S2K-wrapped by the user's passphrase (needed
 * once at setup, and for account recovery); the passphrase itself is then
 * sealed in Keystore-wrapped storage, so S2K runs transparently after first
 * unlock and is never asked for again. Decrypt/sign operations additionally
 * gate on the biometric prompt (see MailViewModel.unlock) before either
 * secret is read.
 */
class KeyManager(context: Context) : DatabaseHelper(context) {

    private companion object {
        const val GCM_TAG_BITS = 128
    }
    override val keyStoreAlias = "securemail_master_key"
    override val sharedPrefsName = "securemail_secure_prefs"
    override val passphraseKey = "encrypted_pgp_secret"
    override val ivKey = "pgp_secret_iv"

    // S2K passphrase custody: same Keystore wrap, distinct prefs slot.
    // Stored raw (not base64) via storePassphrase-equivalent below — the
    // DatabaseHelper encrypts at rest either way.
    private val s2kPassphraseKey = "encrypted_s2k_passphrase"
    private val s2kIvKey = "s2k_passphrase_iv"

    private val tokenKey = "relay_address_token"
    private val addressKey = "own_address"

    fun hasIdentity(): Boolean = isKeyGenerated()

    /** Persists the passphrase-wrapped armored secret ring. */
    fun storeSecretArmor(armoredSecret: String) {
        storePassphrase(Base64.encodeToString(armoredSecret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    }

    /** Returns the armored secret ring. Call only after the biometric gate. */
    fun getSecretArmor(): String =
        String(Base64.decode(getPassphrase(), Base64.NO_WRAP), Charsets.UTF_8)

    /**
     * Seals the S2K passphrase in Keystore-wrapped storage (separate slot
     * from the secret ring so either can be rotated independently).
     */
    fun storeKeyPassphrase(passphrase: String) {
        if (!isKeyGenerated()) generateKey()
        val cipher = getCipherForEncryption()
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit {
            putString(s2kPassphraseKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            putString(s2kIvKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    /** Returns the sealed S2K passphrase, or null when never stored. */
    fun getKeyPassphrase(): String? {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val enc = prefs.getString(s2kPassphraseKey, null) ?: return null
        val iv = prefs.getString(s2kIvKey, null) ?: return null
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                getSecretKey(),
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun storeRelayToken(token: String) {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit { putString(tokenKey, token) }
    }

    fun getRelayToken(): String? =
        context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
            .getString(tokenKey, null)

    fun storeOwnAddress(address: String) {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit { putString(addressKey, address) }
    }

    fun getOwnAddress(): String? =
        context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
            .getString(addressKey, null)

    fun clear() {
        deleteKey()
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit {
            remove(tokenKey)
            remove(addressKey)
            remove(s2kPassphraseKey)
            remove(s2kIvKey)
        }
    }
}
