package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import kotlin.io.encoding.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ownership transfers for a personal E2EE vault.
 *
 * Transfers live on the `owner:<vaultId>` channel as base64(JSON([RelaySync.OwnerTransfer]))
 * records signed by the *current* owner — the same outer encoding office uses on
 * its `owner:<docId>` channel (`OfficeViewModelSyncC.fetchOwnerKey`), so
 * cross-reader tolerance matches. Members follow the signed chain from the
 * vault's base owner key ([fetchOwnerKey]) to determine the current owner; a
 * transfer whose signature does not verify against the running key is skipped,
 * and each record is consumed at most once so a forked chain cannot loop.
 */
object OwnerTransfer {
    private val json = Json { ignoreUnknownKeys = true }

    fun ownerChannel(vaultId: String): String = "owner:$vaultId"

    /**
     * Canonical bytes the current owner signs for a handoff: binds the vault
     * id, the new owner's device id and their PQC public bundle — mirrors
     * office `ownerSigningBytes`.
     */
    fun ownerSigningBytes(vaultId: String, newOwnerId: String, newOwnerKeyB64: String): ByteArray =
        "$vaultId|owner|$newOwnerId|$newOwnerKeyB64".encodeToByteArray()

    /**
     * Follows the signed transfer chain from [baseOwnerKeyB64] (the owner's PQC
     * bundle as issued in the invite / [com.vayunmathur.everysync.data.VaultMeta.ownerKeyB64])
     * to the current owner key. Returns null when the base key is missing/corrupt.
     */
    suspend fun fetchOwnerKey(vaultId: String, baseOwnerKeyB64: String): ByteArray? {
        var ownerKey = baseOwnerKeyB64.takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        // A failed pull keeps the base key: a transfer chain we cannot read is
        // not a chain that doesn't exist. resolveVault re-fetches every cycle,
        // so a recovered relay is picked up on retry.
        val transfers = runCatching { RelaySync.pullRaw(ownerChannel(vaultId), 0) }
            .getOrNull()?.mapNotNull { decodeTransfer(it) } ?: return ownerKey
        val used = HashSet<Int>()
        var advanced = true
        while (advanced) {
            advanced = false
            transfers.withIndex().forEach { (idx, transfer) ->
                if (idx !in used) {
                    verifiedTransferKey(transfer, vaultId, ownerKey)?.let { decoded ->
                        ownerKey = decoded
                        used.add(idx)
                        advanced = true
                    }
                }
            }
        }
        return ownerKey
    }

    /**
     * Transfers vault ownership to [newOwnerId]. Signs with this device's
     * identity (the caller must be the current owner), records the roster
     * change (new owner promoted, self demoted to editor) and appends the
     * signed transfer. Returns true on success.
     */
    suspend fun transferOwnership(
        vaultId: String,
        newOwnerId: String,
        newOwnerName: String = "",
        selfName: String = "",
        selfDeviceId: String = RelaySync.deviceId,
    ): Boolean = runCatching {
        val newBundle = RelaySync.getKey(newOwnerId) ?: return@runCatching false
        val newKeyB64 = Base64.encode(newBundle)
        // Last owner-signed roster change: promote the new owner, demote self.
        // The new-owner record must land before the transfer so verifiers see a
        // consistent roster even mid-handoff.
        if (!VaultRoster.recordMembers(
                vaultId,
                listOf(
                    RelaySync.Member(newOwnerId, newOwnerName, RelayRoles.OWNER),
                    RelaySync.Member(selfDeviceId, selfName, RelayRoles.EDITOR),
                ),
            )
        ) {
            return@runCatching false
        }
        val sig = Base64.encode(RelaySync.sign(ownerSigningBytes(vaultId, newOwnerId, newKeyB64)))
        val transfer = json.encodeToString(RelaySync.OwnerTransfer(newOwnerId, newKeyB64, sig))
        RelaySync.appendRaw(ownerChannel(vaultId), listOf(Base64.encode(transfer.encodeToByteArray()))) != null
    }.getOrDefault(false)

    private suspend fun verifiedTransferKey(
        transfer: RelaySync.OwnerTransfer,
        vaultId: String,
        ownerKey: ByteArray,
    ): ByteArray? {
        val sig = runCatching { Base64.decode(transfer.sig) }.getOrNull() ?: return null
        if (!RelaySync.verify(
                ownerKey,
                ownerSigningBytes(vaultId, transfer.newOwnerId, transfer.newOwnerKey),
                sig,
            )
        ) {
            return null
        }
        return runCatching { Base64.decode(transfer.newOwnerKey) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun decodeTransfer(blob: String): RelaySync.OwnerTransfer? = runCatching {
        json.decodeFromString<RelaySync.OwnerTransfer>(Base64.decode(blob).decodeToString())
    }.getOrNull()
}
