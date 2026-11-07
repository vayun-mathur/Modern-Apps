package com.vayunmathur.everysync.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * Encrypted secret storage. Secrets are AES-GCM encrypted with a non-exportable
 * key held in the Android Keystore, then the ciphertext is stored as a byte
 * array in [DataStoreUtils]. Nothing sensitive is ever written in plaintext.
 */
class TokenStore private constructor(context: Context) {
    private val ds = DataStoreUtils.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    /** Test seam: a software key replacing the AndroidKeyStore key (which Robolectric lacks). */
    internal var secretKeyForTest: SecretKey? = null

    private fun secretKey(): SecretKey {
        secretKeyForTest?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        // Prefix the 12-byte GCM IV so we can recover it on decrypt.
        return ByteArray(iv.size + ct.size).also {
            iv.copyInto(it, 0)
            ct.copyInto(it, iv.size)
        }
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, GCM_IV_LEN)
        val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    suspend fun putSyncKey(accountName: String, dataType: String, keyB64: String) {
        store("$SYNC_KEY_PREFIX$accountName:$dataType", keyB64)
    }

    fun getSyncKey(accountName: String, dataType: String): String? =
        load("$SYNC_KEY_PREFIX$accountName:$dataType")

    suspend fun clear(accountName: String) {
        // Overwrite with empty blobs; DataStore has no per-key delete here.
        // Vault keys are per (account, type); clear the known relay types. Personal
        // vaults use the five relay data types below.
        for (type in SYNC_KEY_TYPES) {
            ds.setByteArray("$SYNC_KEY_PREFIX$accountName:$type", ByteArray(0))
        }
    }

    private suspend fun store(key: String, value: String) {
        try {
            ds.setByteArray(key, encrypt(value.toByteArray()))
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to store secret", expected)
        }
    }

    private fun load(key: String): String? {
        val blob = ds.getByteArray(key) ?: return null
        if (blob.size <= GCM_IV_LEN) return null
        return try {
            String(decrypt(blob))
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to load secret", expected)
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "everysync_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val SYNC_KEY_PREFIX = "tok_synckey_"
        /** Relay data types with per-type vault keys (mirrors the five relay channels). */
        private val SYNC_KEY_TYPES = listOf("contacts", "calendar", "passwords", "notes", "todos")
        private const val TAG = "TokenStore"

        @Volatile
        private var instance: TokenStore? = null

        fun getInstance(context: Context): TokenStore =
            instance ?: synchronized(this) {
                instance ?: TokenStore(context.applicationContext).also { instance = it }
            }
    }
}
