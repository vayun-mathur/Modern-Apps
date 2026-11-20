package com.vayunmathur.communicate.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vayunmathur.library.log.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seal/unseal for communicate's persisted secrets, mirroring everysync's
 * `TokenStore`: AES-GCM with a non-exportable AndroidKeyStore key, ciphertext
 * prefixed with its 12-byte GCM IV. The stored string form is
 * `"v1:" + Base64(iv || ciphertext)` so a sealed value is unambiguously
 * distinguishable from a legacy plaintext value (which never carries the
 * prefix) — that distinction is what makes migration-on-first-read possible.
 *
 * Failure contract (all stores using this):
 * - [seal] returns null on failure; callers must not fall back to plaintext.
 * - [unseal] returns null on failure (bad prefix, bad Base64, GCM tag
 *   mismatch, missing key); callers return signed-out / not-registered and
 *   must leave the stored blob untouched for a later retry.
 */
interface SecretSealer {
    /** Sealed form of [plain], or null if sealing failed. */
    fun seal(plain: String): String?

    /** Plaintext for a value produced by [seal], or null if it cannot be unsealed. */
    fun unseal(sealed: String): String?

    /** True if [value] carries the sealed prefix (regardless of whether it decrypts). */
    fun isSealed(value: String): Boolean
}

/**
 * AndroidKeyStore-backed [SecretSealer] (everysync `TokenStore` pattern, same
 * alias style / transformation / IV-prefix layout, communicate-scoped alias).
 */
class KeystoreSecretSealer : SecretSealer {

    /** Test seam: a software key replacing the AndroidKeyStore key (which Robolectric lacks). */
    internal var secretKeyForTest: SecretKey? = null

    // Ephemeral software key, used only when AndroidKeyStore is unavailable
    // (JVM/Robolectric tests). On a real device the Keystore path always
    // succeeds, so production secrets never touch this key; if it were ever
    // used on-device, its loss on process death yields unseal-failure
    // (signed-out, blob intact), never plaintext.
    @Volatile
    private var softwareFallbackKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        secretKeyForTest?.let { return it }
        try {
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
        } catch (expected: Exception) {
            Log.error(TAG, "AndroidKeyStore unavailable, using ephemeral software key", expected)
            return softwareFallbackKey ?: synchronized(this) {
                softwareFallbackKey ?: KeyGenerator.getInstance("AES").apply { init(256) }
                    .generateKey().also { softwareFallbackKey = it }
            }
        }
    }

    override fun seal(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size).also {
            iv.copyInto(it, 0)
            ct.copyInto(it, iv.size)
        }
        SEALED_PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    } catch (expected: Exception) {
        Log.error(TAG, "Failed to seal secret", expected)
        null
    }

    override fun unseal(sealed: String): String? {
        if (!isSealed(sealed)) return null
        return try {
            val blob = Base64.decode(sealed.removePrefix(SEALED_PREFIX), Base64.NO_WRAP)
            if (blob.size <= GCM_IV_LEN) return null
            val iv = blob.copyOfRange(0, GCM_IV_LEN)
            val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to unseal secret", expected)
            null
        }
    }

    override fun isSealed(value: String): Boolean = value.startsWith(SEALED_PREFIX)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "communicate_secret_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val SEALED_PREFIX = "v1:"
        private const val TAG = "SecretSealer"

        /** Process-wide instance shared by all communicate secret stores. */
        val shared: KeystoreSecretSealer = KeystoreSecretSealer()
    }
}
