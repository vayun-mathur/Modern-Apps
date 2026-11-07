package com.vayunmathur.everysync.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A personal E2EE vault this device owns or has joined: everything needed to
 * sync its record log and to invite others, without ever holding another
 * device's secrets.
 *
 * Key material ([keyB64]) is the AES vault key, Keystore-encrypted at rest
 * (see `readKey`/`writeKey` below — never the raw base64 in DataStore);
 * for joined vaults it arrives sealed in a
 * [RelaySync.Invite](com.vayunmathur.everysync.network.remote.RelaySync.Invite).
 * [ownerKeyB64] is the owner's PQC public bundle, used to verify roster, key
 * epoch, ownership and title signatures client-side — the relay is a pure log.
 */
@Serializable
data class VaultMeta(
    /** Relay record-log id, e.g. `contacts:<uuid>` lives under [vaultId]. */
    val vaultId: String,
    /** One of `contacts`, `calendar`, `passwords`, `notes`. */
    val dataType: String,
    val title: String,
    val keyB64: String,
    val ownerDeviceId: String,
    val ownerKeyB64: String = "",
    val epoch: Int = 0,
    val isOwner: Boolean = true,
)

/** Persists owned + joined [VaultMeta]s as JSON in DataStore (mirrors office's docs index). */
object VaultStore {
    private const val KEY = "everysync_vaults"
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(context: Context): List<VaultMeta> {
        val raw = DataStoreUtils.getInstance(context).getString(KEY)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<VaultMeta>>(raw) }.getOrDefault(emptyList())
    }

    fun get(context: Context, vaultId: String): VaultMeta? =
        getAll(context).firstOrNull { it.vaultId == vaultId }

    suspend fun upsert(context: Context, meta: VaultMeta) {
        // Never persist a raw vault key: re-wrap through the Keystore store so
        // even a programming slip that passes plaintext cannot land it in JSON.
        val safe = if (meta.keyB64.isNotBlank() && !VaultKeyStore.looksSealed(meta.keyB64)) {
            meta.copy(keyB64 = VaultKeyStore.seal(context, meta.keyB64) ?: return)
        } else {
            meta
        }
        val current = getAll(context).filterNot { it.vaultId == safe.vaultId }
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(current + safe))
    }

    suspend fun remove(context: Context, vaultId: String) {
        val current = getAll(context).filterNot { it.vaultId == vaultId }
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(current))
    }

    suspend fun saveAll(context: Context, vaults: List<VaultMeta>) {
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(vaults))
    }

    /**
     * One-time migration: seals any plaintext `keyB64` values already stored
     * (written before the Keystore migration) in place. Idempotent — sealed
     * values are left alone. Called from `EverySyncApplication.onCreate`.
     */
    suspend fun migrateKeysToKeystore(context: Context) {
        val all = getAll(context)
        if (all.isEmpty()) return
        var migrated = 0
        val sealed = all.map { meta ->
            if (meta.keyB64.isNotBlank() && !VaultKeyStore.looksSealed(meta.keyB64)) {
                val wrapped = VaultKeyStore.seal(context, meta.keyB64) ?: return@map meta
                migrated++
                meta.copy(keyB64 = wrapped)
            } else {
                meta
            }
        }
        if (migrated > 0) {
            DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(sealed))
        }
    }
}

/**
 * Reads the plaintext AES vault key for [meta], unsealing the Keystore-wrapped
 * value stored in [VaultMeta.keyB64]. Returns null when the key is missing or
 * cannot be unsealed (fail closed — the vault is skipped, never merged).
 */
fun VaultMeta.readKey(context: Context): ByteArray? =
    VaultKeyStore.unseal(context, keyB64)?.let { b64 ->
        runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

/**
 * Keystore-encrypted storage for AES vault keys (same AES-GCM pattern as
 * `TokenStore`: a non-exportable Keystore key wraps a per-value random
 * content key; the sealed blob is base64 for the `keyB64` field).
 *
 * Sealed values carry the `v1:` prefix so `looksSealed` can tell them apart
 * from legacy plaintext base64 for the one-time migration.
 */
object VaultKeyStore {
    private const val PREFIX = "v1:"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "everysync_vault_keystore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun looksSealed(keyB64: String): Boolean = keyB64.startsWith(PREFIX)

    fun seal(context: Context, plainB64: String): String? = runCatching {
        val contentKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(contentKey, "AES"))
        val iv = cipher.iv
        val ct = cipher.doFinal(plainB64.encodeToByteArray())
        val wrapCipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        wrapCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey())
        val wrapIv = wrapCipher.iv
        val wrappedKey = wrapCipher.doFinal(contentKey)
        PREFIX + kotlin.io.encoding.Base64.encode(
            wrapIv.size.toBytes() + wrapIv + wrappedKey.size.toBytes() + wrappedKey +
                iv.size.toBytes() + iv + ct,
        )
    }.getOrNull()

    fun unseal(context: Context, sealedB64: String): String? = runCatching {
        if (!looksSealed(sealedB64)) return@runCatching null
        val sealed = kotlin.io.encoding.Base64.decode(sealedB64.removePrefix(PREFIX))
        var off = 0
        fun readChunk(): ByteArray {
            val len = sealed.copyOfRange(off, off + 4).toInt()
            off += 4
            val chunk = sealed.copyOfRange(off, off + len)
            off += len
            return chunk
        }
        val wrapIv = readChunk()
        val wrappedKey = readChunk()
        val iv = readChunk()
        val ct = sealed.copyOfRange(off, sealed.size)
        val unwrapCipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        unwrapCipher.init(
            javax.crypto.Cipher.DECRYPT_MODE, secretKey(),
            javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, wrapIv),
        )
        val contentKey = unwrapCipher.doFinal(wrappedKey)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(contentKey, "AES"),
            javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.doFinal(ct).decodeToString()
    }.getOrNull()

    /** Test seam: a software key replacing the AndroidKeyStore key (which Robolectric lacks). */
    internal var secretKeyForTest: javax.crypto.SecretKey? = null

    private fun secretKey(): javax.crypto.SecretKey {
        secretKeyForTest?.let { return it }
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun Int.toBytes(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte(),
    )

    private fun ByteArray.toInt(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)
}
