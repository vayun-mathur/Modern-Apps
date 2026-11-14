package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import kotlin.io.encoding.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owner-signed key epochs for a personal E2EE vault: cryptographic
 * read-revocation after a member is removed or downgraded.
 *
 * Epochs live on the `keys:<vaultId>` channel as base64(JSON([RelaySync.KeyEpoch]))
 * records — the same outer encoding office uses on its `keys:<docId>` channel
 * (`OfficeViewModelSyncC.fetchCurrentKey`), so cross-reader tolerance matches.
 * Each epoch wraps the fresh AES content key with every remaining member's PQC
 * bundle; only epochs with a valid owner signature over [epochSigningBytes]
 * are honored.
 *
 * Members resolve the current key with [fetchCurrentKey]: the highest verified
 * epoch they can unseal, falling back to the invite (epoch 0) key.
 */
object VaultKeys {
    private val json = Json { ignoreUnknownKeys = true }

    fun keysChannel(vaultId: String): String = "keys:$vaultId"

    /**
     * Canonical bytes the owner signs for an epoch: binds the vault id, the
     * epoch number and every wrap (sorted by member id for determinism) to the
     * vault — mirrors office `epochSigningBytes`.
     */
    fun epochSigningBytes(vaultId: String, epoch: Int, wraps: Map<String, String>): ByteArray =
        (listOf(vaultId, epoch.toString()) +
            wraps.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" })
            .joinToString("|").encodeToByteArray()

    /**
     * Resolves the current content key: the highest owner-signed epoch this
     * device can unseal, falling back to ([inviteEpoch], [inviteKey]).
     * Returns (epoch, key).
     *
     * A failed pull keeps the invite key (the epoch loop simply finds nothing
     * newer); epoch-bearing history stays unreadable until the pull succeeds,
     * which is fail-closed — the record cycle aborts before merging.
     */
    suspend fun fetchCurrentKey(
        vaultId: String,
        inviteKey: ByteArray,
        ownerKey: ByteArray?,
        inviteEpoch: Int = 0,
        deviceId: String = RelaySync.deviceId,
    ): Pair<Int, ByteArray> {
        if (ownerKey == null || ownerKey.isEmpty()) return inviteEpoch to inviteKey
        var bestEpoch = inviteEpoch
        var bestKey = inviteKey
        val blobs = runCatching { RelaySync.pullRaw(keysChannel(vaultId), 0) }.getOrNull()
            ?: return inviteEpoch to inviteKey
        for (blob in blobs) {
            val candidate = verifiedEpochKey(blob, vaultId, ownerKey, deviceId) ?: continue
            if (candidate.first >= bestEpoch) {
                bestEpoch = candidate.first
                bestKey = candidate.second
            }
        }
        return bestEpoch to bestKey
    }

    private suspend fun verifiedEpochKey(
        blob: String,
        vaultId: String,
        ownerKey: ByteArray,
        deviceId: String,
    ): Pair<Int, ByteArray>? {
        val epoch = decodeEpoch(blob) ?: return null
        val sig = runCatching { Base64.decode(epoch.sig) }.getOrNull() ?: return null
        if (!RelaySync.verify(ownerKey, epochSigningBytes(vaultId, epoch.epoch, epoch.wraps), sig)) return null
        val wrap = epoch.wraps[deviceId] ?: return null
        val key = runCatching { RelaySync.unseal(Base64.decode(wrap)) }.getOrNull() ?: return null
        return epoch.epoch to key
    }

    /**
     * Owner mints a new content key sealed to [memberBundles] (member id →
     * PQC public bundle) and publishes the signed epoch. Always includes
     * [ownerDeviceId] so the owner keeps access. Returns the new key, or null
     * when the publish fails. The caller re-baselines the vault's record log
     * under the new key (task 1's relay driver) — rotation without a re-push
     * would leave remaining members unable to read history.
     */
    suspend fun rotateKey(
        vaultId: String,
        currentEpoch: Int,
        memberBundles: Map<String, ByteArray>,
        ownerDeviceId: String = RelaySync.deviceId,
        ownerBundle: ByteArray = RelaySync.publicBundle,
    ): ByteArray? {
        val newEpoch = currentEpoch + 1
        val newKey = RelaySync.newVaultKey()
        val wraps = HashMap<String, String>()
        for ((id, bundle) in memberBundles) {
            if (bundle.isEmpty()) continue
            wraps[id] = Base64.encode(RelaySync.seal(bundle, newKey))
        }
        // Always include ourselves so the owner keeps access even when the
        // roster fetch raced (mirrors office `rotateKey`).
        wraps[ownerDeviceId] = Base64.encode(RelaySync.seal(ownerBundle, newKey))
        val sig = Base64.encode(RelaySync.sign(epochSigningBytes(vaultId, newEpoch, wraps)))
        val record = Base64.encode(json.encodeToString(RelaySync.KeyEpoch(newEpoch, wraps, sig)).encodeToByteArray())
        val ok = runCatching { RelaySync.appendRaw(keysChannel(vaultId), listOf(record)) }.getOrNull() != null
        return if (ok) newKey else null
    }

    /**
     * Downgrades/removes [memberId] from the roster and rotates the content key
     * so the removed member can no longer decrypt new content. Owner only.
     * Returns the new key, or null on failure.
     */
    suspend fun revokeAndRotate(
        vaultId: String,
        memberId: String,
        memberName: String = "",
        role: String = RelayRoles.REVOKED,
        currentEpoch: Int,
        memberBundles: Map<String, ByteArray>,
        ownerKey: ByteArray,
        ownerDeviceId: String = RelaySync.deviceId,
    ): ByteArray? {
        if (!VaultRoster.setRole(vaultId, memberId, memberName, role)) return null
        val remaining = VaultRoster.fetchRoster(vaultId, ownerKey)
            .filter { it.key != memberId && it.value.role != RelayRoles.REVOKED }
            .mapNotNull { (id, _) -> memberBundles[id]?.let { id to it } }
            .toMap()
        return rotateKey(vaultId, currentEpoch, remaining, ownerDeviceId)
    }

    private fun decodeEpoch(blob: String): RelaySync.KeyEpoch? = runCatching {
        json.decodeFromString<RelaySync.KeyEpoch>(Base64.decode(blob).decodeToString())
    }.getOrNull()
}
