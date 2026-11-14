package com.vayunmathur.library.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owns the AndroidKeyStore key that encrypts the SQLCipher database passphrase. */
open class DatabaseHelper(val context: Context) {
    protected open val keyStoreAlias = "db_no_auth_key"
    protected open val sharedPrefsName = "secure_prefs"
    protected open val passphraseKey = "encrypted_passphrase_no_auth"
    protected open val ivKey = "passphrase_iv_no_auth"

    fun isKeyGenerated(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val keyExists = keyStore.containsAlias(keyStoreAlias)
        if (!keyExists) return false
        
        // Also check if IV exists in SharedPreferences
        // If key exists but IV doesn't, the data is corrupted (e.g., user cleared app data)
        // In that case, we need to regenerate the key
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val ivExists = prefs.contains(ivKey)
        if (!ivExists) {
            // Key exists but IV doesn't - clean up the orphaned key
            try {
                keyStore.deleteEntry(keyStoreAlias)
            } catch (expected: KeyStoreException) {
                Log.status(TAG, "Failed to delete orphaned key", expected)
            }
            return false
        }
        return true
    }
    
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(keyStoreAlias)
        } catch (expected: KeyStoreException) {
            Log.status(TAG, "Failed to delete key", expected)
        }
        // Also clear the SharedPreferences
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit {
            remove(passphraseKey)
            remove(ivKey)
        }
    }

    open fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyStoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    protected fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.getKey(keyStoreAlias, null) as SecretKey
    }

    private fun persist(passphrase: String, cipher: Cipher) {
        val encryptedBytes = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit {
            putString(passphraseKey, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
            putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
        }
    }

    fun createAndStorePassphrase(cipher: Cipher): String {
        val random = SecureRandom()
        val passphraseBytes = ByteArray(32)
        random.nextBytes(passphraseBytes)
        val passphrase = Base64.encodeToString(passphraseBytes, Base64.NO_WRAP)
        persist(passphrase, cipher)
        return passphrase
    }

    fun decryptPassphrase(cipher: Cipher): String {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val encryptedPassphrase =
            prefs.getString(passphraseKey, null) ?: throw IllegalStateException("Passphrase not found")
        val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.NO_WRAP)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun getCipherForEncryption(): Cipher {
        val cipher =
            Cipher.getInstance("$AES_ALGORITHM/$AES_BLOCK_MODE/$AES_PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return cipher
    }

    fun getCipherForDecryption(): Cipher {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val ivBase64 =
            prefs.getString(ivKey, null) ?: throw IllegalStateException("IV not found")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("$AES_ALGORITHM/$AES_BLOCK_MODE/$AES_PADDING")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher
    }

    fun storePassphrase(passphrase: String) {
        if (!isKeyGenerated()) generateKey()
        persist(passphrase, getCipherForEncryption())
    }

    fun getPassphrase(): String {
        return decryptPassphrase(getCipherForDecryption())
    }

    /** KeyStore algorithm and cipher constants for [DatabaseHelper]. */
    companion object {
        private const val TAG = "DatabaseHelper"
        private const val AES_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val AES_BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val AES_PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val GCM_TAG_BITS = 128
    }
}
