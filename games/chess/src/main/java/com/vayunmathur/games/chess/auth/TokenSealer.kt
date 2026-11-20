package com.vayunmathur.games.chess.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vayunmathur.library.log.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * Seals a secret string for at-rest storage and unseals it on read.
 *
 * Sealed values carry the [KeystoreTokenSealer.PREFIX] marker so callers can tell them
 * apart from legacy plaintext written before sealing existed: [unseal] returns a
 * non-sealed value unchanged (it is legacy plain), while a sealed value that cannot be
 * decrypted (wrong device after a backup restore, rotated/lost Keystore key) yields
 * null — callers must treat that as signed-out, never crash and never fall back to
 * using the ciphertext as the secret.
 */
interface TokenSealer {
    /** Sealed form of [plain], or null when sealing failed (caller must not persist plain). */
    fun seal(plain: String): String?

    /** Plain form of [stored]; legacy non-sealed values pass through, failures give null. */
    fun unseal(stored: String): String?

    /** True when [stored] carries the sealed marker (whether or not it still decrypts). */
    fun isSealed(stored: String): Boolean
}

/**
 * [TokenSealer] backed by a non-exportable AES key in the Android Keystore, copied from
 * everysync's TokenStore: AES/GCM/NoPadding, the 12-byte GCM IV prefixed to the
 * ciphertext, the whole blob Base64-encoded behind [PREFIX] for string storage. The
 * Keystore key never leaves the device, so a sealed value carried off-device by backup
 * or device transfer is undecryptable on the destination.
 */
class KeystoreTokenSealer(private val keyAlias: String) : TokenSealer {

    /** Test seam: a software key replacing the AndroidKeyStore key (which Robolectric lacks). */
    internal var secretKeyForTest: SecretKey? = null

    private fun secretKey(): SecretKey {
        secretKeyForTest?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
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

    override fun seal(plain: String): String? = try {
        PREFIX + Base64.encode(encrypt(plain.toByteArray(Charsets.UTF_8)))
    } catch (expected: Exception) {
        Log.error(TAG, "Failed to seal token", expected)
        null
    }

    override fun unseal(stored: String): String? {
        if (!isSealed(stored)) return stored
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX))
            if (blob.size <= GCM_IV_LEN) {
                null
            } else {
                String(decrypt(blob), Charsets.UTF_8)
            }
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to unseal token", expected)
            null
        }
    }

    override fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** Keystore constants for sealed token storage. */
    companion object {
        /** Marker distinguishing sealed values from legacy plaintext. */
        const val PREFIX = "v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val TAG = "ChessTokenSealer"
    }
}
