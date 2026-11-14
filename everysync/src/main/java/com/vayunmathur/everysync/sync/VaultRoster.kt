package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.network.remote.RelaySync
import kotlin.io.encoding.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owner-signed membership roster for a personal E2EE vault.
 *
 * The roster lives on the `members:<vaultId>` relay channel as a batch of
 * [RelaySync.SignedEnvelope] records: each envelope's payload is the JSON list
 * of [RelaySync.Member] and its signature is the owner's PQC signature over the
 * raw payload bytes. Only envelopes with a valid owner signature are honored —
 * the relay is an untrusted log, so authority is enforced entirely client-side
 * (same model as office `OfficeViewModelSyncC.fetchMembers`, but batched into
 * signed envelopes so it verifies what `EverySyncVaults` writes).
 *
 * Last record wins per member id. Revocation is `role="revoked"`; removing read
 * access additionally requires a key rotation (see [VaultKeys.rotateKey]).
 */
object VaultRoster {
    private val json = Json { ignoreUnknownKeys = true }

    fun membersChannel(vaultId: String): String = "members:$vaultId"

    /**
     * Owner-signs [members] and appends them to the roster channel. The caller
     * must be the vault owner (signing uses this device's identity) — enforced
     * by the share flow, not here.
     */
    suspend fun recordMembers(vaultId: String, members: List<RelaySync.Member>): Boolean =
        runCatching {
            val payload = json.encodeToString(members)
            val sig = Base64.encode(RelaySync.sign(payload.encodeToByteArray()))
            val envelope = json.encodeToString(RelaySync.SignedEnvelope(RelaySync.deviceId, sig, payload))
            RelaySync.appendRaw(membersChannel(vaultId), listOf(envelope)) != null
        }.getOrDefault(false)

    /**
     * Reads + verifies the roster: only envelopes with a valid [ownerKey]
     * signature are honored. Returns member id → member, last record wins.
     *
     * A failed pull returns an empty map (fail closed): the merge driver
     * already aborts the record cycle before consulting the roster, and
     * `resolveVault` treats "not on roster" as "skip this vault".
     */
    suspend fun fetchRoster(vaultId: String, ownerKey: ByteArray): Map<String, RelaySync.Member> {
        if (ownerKey.isEmpty()) return emptyMap()
        val raw = runCatching { RelaySync.pullRaw(membersChannel(vaultId), 0) }.getOrNull()
            ?: return emptyMap()
        val byId = LinkedHashMap<String, RelaySync.Member>()
        for (r in raw) {
            verifiedMembers(r, ownerKey)?.forEach { member ->
                if (member.id.isNotBlank()) byId[member.id] = member
            }
        }
        return byId
    }

    private suspend fun verifiedMembers(raw: String, ownerKey: ByteArray): List<RelaySync.Member>? {
        val envelope = runCatching {
            json.decodeFromString<RelaySync.SignedEnvelope>(raw)
        }.getOrNull() ?: return null
        val sig = runCatching { Base64.decode(envelope.sig) }.getOrNull() ?: return null
        if (!RelaySync.verify(ownerKey, envelope.payload.encodeToByteArray(), sig)) return null
        return runCatching {
            json.decodeFromString<List<RelaySync.Member>>(envelope.payload)
        }.getOrNull()
    }

    /** This device's role in the vault, or null when it is not on the verified roster. */
    suspend fun myRole(
        vaultId: String,
        ownerKey: ByteArray,
        deviceId: String = RelaySync.deviceId,
    ): String? = fetchRoster(vaultId, ownerKey)[deviceId]?.role

    /** Owner sets/changes one member's role, or revokes with `role="revoked"`. Owner only. */
    suspend fun setRole(vaultId: String, memberId: String, name: String = "", role: String): Boolean =
        recordMembers(vaultId, listOf(RelaySync.Member(memberId, name, role)))
}
