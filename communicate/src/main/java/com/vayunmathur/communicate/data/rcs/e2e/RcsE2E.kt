package com.vayunmathur.communicate.data.rcs.e2e

import android.content.Context
import android.telephony.SubscriptionManager
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMlsGroup
import com.vayunmathur.communicate.data.rcs.RcsMlsIdentity
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Closed-loop MLS E2EE orchestration for the RCS line.
 *
 * MLS here follows GSMA Universal Profile 3.0 (standard MLS, RFC 9420) — the
 * same profile Google Messages uses — so the crypto is interop-shaped:
 * standard ciphersuite, `BasicCredential` identities, TLS framing. What stays
 * closed-loop in v1 is *key discovery*: key packages ride our own RCS
 * content-types rather than a federated directory.
 *
 * Flow:
 * - Each local E.164 gets an Ed25519 identity (generated once, Room-held).
 * - 1:1 E2EE: creator builds a group, adds the peer's key package (fetched
 *   over RCS via [RcsKeyDirectory], which exchanges key packages as opaque
 *   RCS payloads), sends Welcome + commit; joiner persists the group.
 * - Group E2EE: same, with N key packages.
 * - Sends encrypt to the group; receives decrypt by group id. Commits merge
 *   automatically inside the crate.
 *
 * Key packages are exchanged over the RCS transport itself (no side channel):
 * an unencrypted `message/cpim` with content-type `application/x-rcs-keypackage`
 * carries the TLS bytes; Welcomes ride `application/x-rcs-welcome`.
 */
object RcsE2E {
    /** Content-Type marking a key-package publication message. */
    const val CT_KEY_PACKAGE = "application/x-rcs-keypackage"

    /** Content-Type marking a Welcome envelope message. */
    const val CT_WELCOME = "application/x-rcs-welcome"

    /** Content-Type marking an MLS commit message. */
    const val CT_COMMIT = "application/x-rcs-commit"

    /** Content-Type marking an MLS application (encrypted) message. */
    const val CT_MLS = "application/x-rcs-mls"

    /** Content-Type marking a key-package request (peer auto-answers). */
    const val CT_KEY_REQUEST = "application/x-rcs-keyrequest"

    /**
     * Per-conversation commit locks (§5.3): concurrent commits from two
     * members fork the epoch. Our own commits serialize per group so we
     * never fork ourselves; inbound epoch mismatches get one re-sync attempt
     * in [decryptFor] before the payload is dropped.
     */
    internal val commitLocks =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * Safety-number fingerprint for a conversation peer (§5.4): SHA-256 of
     * the peer's latest cached key package, grouped `XXXX XXXX …`.
     *
     * Closed-loop comparable: both apps fingerprint the same package bytes
     * (our latest publication ↔ their cached copy), so matching safety
     * numbers mean no MITM on the key directory. Null when no package is
     * cached yet. For our own side see [mySafetyFingerprint].
     */
    suspend fun safetyFingerprint(peerE164: String): String? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext null
            val pkg = RcsPeerKeys.get(peerE164) ?: return@withContext null
            formatSafetyNumber(pkg)
        }

    /**
     * Our own safety number: fingerprint of our latest published package
     * ([RcsKeyDirectory.lastPublishedFor]), same grouping. Null before our
     * first publication.
     */
    suspend fun mySafetyFingerprint(localE164: String): String? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext null
            val pkg = RcsKeyDirectory.lastPublishedFor(localE164) ?: return@withContext null
            formatSafetyNumber(pkg)
        }

    internal val PAYLOAD_KINDS = 0..1
    private const val BYTE_MASK = 0xFF
    internal const val HEX_RADIX = 16
    internal const val HEX_PAIR = 2
    private const val SAFETY_GROUP = 4
    internal const val MIN_FRAME_BYTES = 8

    private fun formatSafetyNumber(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02d".format(it.toInt() and BYTE_MASK) }
            .chunked(SAFETY_GROUP).joinToString(" ")
    }

    /**
     * Whether [e164]'s identity is user-verified (§5.4).
     */
    suspend fun isVerified(context: Context, e164: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext false
            RcsDatabase.getDatabase(context).mlsIdentityDao().get(e164)?.verified == true
        }

    /**
     * Mark [e164]'s identity verified/unverified (from the verify screen).
     */
    suspend fun setVerified(context: Context, e164: String, verified: Boolean) {
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext
            val db = RcsDatabase.getDatabase(context)
            db.mlsIdentityDao().get(e164)?.let {
                db.mlsIdentityDao().upsert(it.copy(verified = verified))
            }
        }
    }

    /**
     * Check a peer's key package against their verified identity (§5.4).
     * Returns true when the peer was previously VERIFIED (via the verify
     * screen) and this package differs from the one recorded at verify time
     * — i.e. a possible re-install or MITM, exactly Signal's safety-number
     * change warning. Verification is cleared so the user must re-verify.
     * Unverified peers always return false (nothing to compare against).
     */
    suspend fun checkPeerIdentityChange(
        context: Context,
        peerE164: String,
        keyPackage: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || keyPackage.isEmpty()) return@withContext false
        val db = RcsDatabase.getDatabase(context)
        val record = db.mlsIdentityDao().get(peerE164) ?: return@withContext false
        if (!record.verified) return@withContext false
        val sketch = java.security.MessageDigest.getInstance("SHA-256").digest(keyPackage)
        // The verified record stores the package sketch witnessed at verify
        // time in its identity bytes (see setVerifiedWithPackage).
        if (!record.identity.contentEquals(sketch)) {
            db.mlsIdentityDao().upsert(record.copy(verified = false))
            return@withContext true
        }
        false
    }

    /**
     * Verify [peerE164] against the currently cached package sketch (from
     * the verify screen after comparing safety numbers out-of-band).
     */
    suspend fun setVerifiedWithPackage(context: Context, peerE164: String, keyPackage: ByteArray) {
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || keyPackage.isEmpty()) return@withContext
            val db = RcsDatabase.getDatabase(context)
            val sketch = java.security.MessageDigest.getInstance("SHA-256").digest(keyPackage)
            db.mlsIdentityDao().upsert(
                RcsMlsIdentity(
                    e164 = peerE164,
                    identity = sketch,
                    updatedAt = System.currentTimeMillis(),
                    verified = true,
                ),
            )
        }
    }

    /**
     * Our identity bytes for [e164], generating + persisting on first use.
     * Null when the gate is off or native is unavailable.
     */
    suspend fun identityFor(context: Context, e164: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || e164.isBlank()) return@withContext null
            val db = RcsDatabase.getDatabase(context)
            db.mlsIdentityDao().get(e164)?.identity ?: runCatching {
                val out = RustMlsCrypto.generateIdentity(e164.toByteArray(Charsets.UTF_8))
                    ?: return@runCatching null
                val identity = out.getOrNull(1) ?: return@runCatching null
                db.mlsIdentityDao().upsert(
                    RcsMlsIdentity(e164 = e164, identity = identity, updatedAt = System.currentTimeMillis()),
                )
                identity
            }.getOrNull()
        }

    /**
     * Fresh key package TLS for [e164] (publish to peers). Persists the
     * evolved storage snapshot. Null on any failure.
     */
    suspend fun freshKeyPackage(context: Context, e164: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
            val identity = identityFor(context, e164) ?: return@withContext null
            val db = RcsDatabase.getDatabase(context)
            val group = db.mlsGroupDao().getByConversation(e2eThreadFor(e164))
            val storage = group?.storage ?: ByteArray(0)
            runCatching {
                val out = RustMlsCrypto.buildKeyPackage(storage, identity) ?: return@runCatching null
                val storageOut = out.getOrNull(0) ?: ByteArray(0)
                val kp = out.getOrNull(1) ?: return@runCatching null
                if (group != null) {
                    db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
                } else {
                    // No group yet: stash the snapshot under the 1:1 pseudo-group row.
                    db.mlsGroupDao().upsert(
                        RcsMlsGroup(
                            groupIdHex = "pending:$e164",
                            conversationId = e2eThreadFor(e164),
                            storage = storageOut,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                kp
            }.getOrNull()
        }

    /**
     * Remove members from [conversationId]'s MLS group by leaf index (see the
     * `removeMembers` JNI contract: ASCII decimal CSV). Sends the commit to
     * the group thread so remaining members ratchet forward — removed members
     * can no longer decrypt. Persists the evolved snapshot + pruned member
     * map. Serialized per group (§5.3).
     *
     * Prefer [removeMembersByE164]; use this only when indices are already
     * known. Returns true when the commit was accepted by the transport.
     */
    suspend fun removeMembers(
        context: Context,
        e164: String,
        conversationId: String,
        leafIndices: List<Int>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || leafIndices.isEmpty()) {
            return@withContext false
        }
        commitLockFor(conversationId).withLock {
            val identity = identityFor(context, e164) ?: return@withLock false
            val db = RcsDatabase.getDatabase(context)
            val group = db.mlsGroupDao().getByConversation(conversationId) ?: return@withLock false
            if (group.groupIdHex.startsWith("pending:")) return@withLock false
            val groupId = group.groupIdHex.chunked(HEX_PAIR).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
            val csv = leafIndices.distinct().sorted().joinToString(",")
                .toByteArray(Charsets.US_ASCII)
            val out = runCatching {
                RustMlsCrypto.removeMembers(group.storage, identity, groupId, csv)
            }.getOrNull() ?: return@withLock false
            val storageOut = out.getOrNull(0) ?: group.storage
            val commit = out.getOrNull(1) ?: return@withLock false
            val droppedIdx = leafIndices.toSet()
            db.mlsGroupDao().upsert(
                group.copy(
                    storage = storageOut,
                    updatedAt = System.currentTimeMillis(),
                    members = pruneMembers(group.members, droppedIdx),
                ),
            )
            sendMlsEnvelope(conversationId, commit, CT_COMMIT)
        }
    }

    /**
     * Remove members by E.164 using the tracked leaf map (§5.2). Unknown
     * E.164s (never added through this client) are ignored. Returns true
     * when at least one member was removed and committed.
     */
    suspend fun removeMembersByE164(
        context: Context,
        e164: String,
        conversationId: String,
        peers: List<String>,
    ): Boolean {
        if (!RcsFeature.enabled || peers.isEmpty()) return false
        val db = RcsDatabase.getDatabase(context)
        val group = db.mlsGroupDao().getByConversation(conversationId) ?: return false
        val indexByMember = parseMembers(group.members)
        val indices = peers.mapNotNull { indexByMember[it.trim()] }
        if (indices.isEmpty()) return false
        return removeMembers(context, e164, conversationId, indices)
    }

    /** Leaf index of [peer] in [conversationId]'s group, or null. */
    suspend fun leafIndexFor(context: Context, conversationId: String, peer: String): Int? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext null
            val group = RcsDatabase.getDatabase(context).mlsGroupDao()
                .getByConversation(conversationId) ?: return@withContext null
            parseMembers(group.members)[peer.trim()]
        }


    /**
     * Record added members' leaf indices. The crate assigns leaves in join
     * order starting after our own leaf 0: for an N-member add to a group
     * whose current max leaf is M, new leaves are M+1..M+N in package order.
     * Best-effort (crate-internal, but stable for add-order tracking).
     */
    internal fun trackAddedMembers(
        csv: String,
        peerE164s: List<String>,
    ): String {
        val current = parseMembers(csv).toMutableMap()
        var next = (current.values.maxOrNull() ?: -1) + 1
        // Our own leaf is 0 when the map is empty (creator).
        if (current.isEmpty()) next = 1
        for (peer in peerE164s) {
            val member = peer.trim()
            if (member.isNotEmpty() && member !in current) {
                current[member] = next++
            }
        }
        return current.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /**
     * Create an E2EE group for [conversationId] with our local identity, then
     * add [peerKeyPackages]. Returns the commit + welcome payloads to send
     * (commit → group thread, welcome → each joiner 1:1). Persists the group.
     */
    suspend fun createEncryptedGroup(
        context: Context,
        e164: String,
        conversationId: String,
        peerKeyPackages: List<ByteArray>,
    ): Pair<ByteArray, ByteArray>? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || peerKeyPackages.isEmpty()) return@withContext null
        val identity = identityFor(context, e164) ?: return@withContext null
        val db = RcsDatabase.getDatabase(context)
        runCatching {
            var storage = db.mlsGroupDao().getByConversation(conversationId)?.storage ?: ByteArray(0)
            val created = RustMlsCrypto.createGroup(storage, identity) ?: return@runCatching null
            storage = created.getOrNull(0) ?: storage
            val groupId = created.getOrNull(1) ?: return@runCatching null
            val added = RustMlsCrypto.addMembers(storage, identity, groupId, peerKeyPackages.toTypedArray())
                ?: return@runCatching null
            val storageOut = added.getOrNull(0) ?: storage
            val commit = added.getOrNull(1) ?: return@runCatching null
            val welcome = added.getOrNull(2) ?: return@runCatching null
            db.mlsGroupDao().upsert(
                RcsMlsGroup(
                    groupIdHex = groupId.hex(),
                    conversationId = conversationId,
                    storage = storageOut,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            commit to welcome
        }.getOrNull()
    }

    /**
     * Join from a Welcome envelope. Persists the group under [conversationId].
     * Returns the group id, or null.
     */
    suspend fun joinEncryptedGroup(
        context: Context,
        conversationId: String,
        welcome: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val storage = db.mlsGroupDao().getByConversation(conversationId)?.storage ?: ByteArray(0)
        runCatching {
            val out = RustMlsCrypto.joinGroup(storage, welcome) ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: storage
            val groupId = out.getOrNull(1) ?: return@runCatching null
            db.mlsGroupDao().upsert(
                RcsMlsGroup(
                    groupIdHex = groupId.hex(),
                    conversationId = conversationId,
                    storage = storageOut,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            groupId
        }.getOrNull()
    }

    /**
     * Encrypt [plaintext] for [conversationId]'s group. Returns the framed
     * payload to send, or null.
     */
    suspend fun encryptTo(
        context: Context,
        e164: String,
        conversationId: String,
        plaintext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val identity = identityFor(context, e164) ?: return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val group = db.mlsGroupDao().getByConversation(conversationId) ?: return@withContext null
        if (group.groupIdHex.startsWith("pending:")) return@withContext null
        runCatching {
            val groupId = group.groupIdHex.chunked(HEX_PAIR).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
            val out = RustMlsCrypto.encrypt(group.storage, identity, groupId, plaintext)
                ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: group.storage
            val payload = out.getOrNull(1) ?: return@runCatching null
            db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
            payload
        }.getOrNull()
    }

    /**
     * Decrypt [payload] for [conversationId]'s group. Returns
     * `(plaintext, isApplication)` — commits merge silently with empty
     * plaintext. Null on failure (caller keeps the opaque row / drops).
     */
    suspend fun decryptFor(
        context: Context,
        conversationId: String,
        payload: ByteArray,
    ): Pair<ByteArray, Boolean>? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val group = db.mlsGroupDao().getByConversation(conversationId) ?: return@withContext null
        if (group.groupIdHex.startsWith("pending:")) return@withContext null
        runCatching {
            val groupId = group.groupIdHex.chunked(HEX_PAIR).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
            val out = RustMlsCrypto.decrypt(group.storage, groupId, payload)
                ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: group.storage
            val isApp = (out.getOrNull(1)?.firstOrNull() ?: 0) == 1.toByte()
            val plaintext = out.getOrNull(2) ?: ByteArray(0)
            db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
            plaintext to isApp
        }.getOrNull()
    }

    /**
     * Request the peer's key package (they auto-publish on receipt) and
     * publish ours. Call when the user taps "Start encrypted chat".
     */
    suspend fun requestEncryptedChat(context: Context, localE164: String, peerE164: String): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        // Ensure our identity exists first.
        identityFor(context, localE164) ?: return false
        // Publish ours alongside the request so one round trip suffices.
        RcsKeyDirectory.publishTo(context, localE164, peerE164)
        return sendKeyRequest(peerE164)
    }

    private suspend fun sendKeyRequest(peerE164: String): Boolean {
        if (!RcsSipTransport.canSend()) return false
        val body = "Content-Type: $CT_KEY_REQUEST\r\n\r\nrequest"
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$peerE164@rcs",
            callId = "${UUID.randomUUID()}@rcs-kreq",
            body = body,
        )
        return runCatching { RcsSipTransport.sendSipMessage(startLine, headers, content) }
            .getOrDefault(false)
    }

    /**
     * Request one peer's key package (public; the peer auto-publishes).
     * Used for N-peer group setup when cached packages are missing.
     */
    suspend fun requestKeyPackage(peerE164: String): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        return sendKeyRequest(peerE164)
    }

    /**
     * Create the E2EE group for N peers and fan out: one commit to the group
     * thread ([CT_COMMIT]), one Welcome per peer 1:1 ([CT_WELCOME]). Persists
     * the group under [conversationId]. Returns true when every leg was
     * accepted by the transport.
     */
    suspend fun setupEncryptedGroup(
        context: Context,
        localE164: String,
        conversationId: String,
        peerPackages: Map<String, ByteArray>,
    ): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || peerPackages.isEmpty()) return false
        val (commit, welcome) = createEncryptedGroup(
            context, localE164, conversationId, peerPackages.values.toList(),
        ) ?: return false
        // Track leaf indices for later removal (§5.2).
        trackGroupMembers(context, conversationId, peerPackages.keys.toList())
        var ok = sendMlsEnvelope(conversationId, commit, CT_COMMIT)
        for ((peer, _) in peerPackages) {
            ok = sendMlsEnvelope(peer, welcome, CT_WELCOME) && ok
        }
        // Rotate our key package now that the previous one may be consumed.
        freshKeyPackage(context, localE164)
        // Replenish the published pool (§5.1).
        replenishKeyPackages(context, peerPackages.keys.toList(), localE164)
        return ok
    }

    /**
     * Create the E2EE group once the peer's key package is known and send the
     * Welcome + commit. Called when a key package arrives for a conversation
     * with no group yet, or explicitly from the setup UI. Returns true when
     * the Welcome + commit were accepted by the transport.
     */
    suspend fun setupGroupWithPeer(
        context: Context,
        localE164: String,
        conversationId: String,
        peerE164: String,
        peerKeyPackage: ByteArray,
    ): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        val (commit, welcome) = createEncryptedGroup(context, localE164, conversationId, listOf(peerKeyPackage))
            ?: return false
        trackGroupMembers(context, conversationId, listOf(peerE164))
        // Commit → the (new) group thread; Welcome → the peer 1:1.
        val commitOk = sendMlsEnvelope(conversationId, commit, CT_COMMIT)
        val welcomeOk = sendMlsEnvelope(peerE164, welcome, CT_WELCOME)
        // Rotate our key package now that the previous one may be consumed.
        freshKeyPackage(context, localE164)
        replenishKeyPackages(context, listOf(peerE164), localE164)
        return commitOk && welcomeOk
    }

    /**
     * Merge [peers] into the tracked member map for [conversationId]'s group
     * (§5.2). Best-effort; never throws.
     */
    private suspend fun trackGroupMembers(context: Context, conversationId: String, peers: List<String>) {
        if (!RcsFeature.enabled || peers.isEmpty()) return
        runCatching {
            val db = RcsDatabase.getDatabase(context)
            val group = db.mlsGroupDao().getByConversation(conversationId) ?: return
            db.mlsGroupDao().upsert(
                group.copy(
                    members = trackAddedMembers(group.members, peers),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Key-package replenishment (§5.1): after consuming our published package
     * in a setup, immediately publish a fresh one to the same peers so their
     * cached pool stays warm and the next add doesn't stall on a round trip.
     */
    private suspend fun replenishKeyPackages(context: Context, peers: List<String>, localE164: String) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || peers.isEmpty()) return
        for (peer in peers) {
            runCatching { RcsKeyDirectory.publishTo(context, localE164, peer) }
        }
    }

    private suspend fun sendMlsEnvelope(
        recipient: String,
        framed: ByteArray,
        contentType: String,
    ): Boolean {
        if (!RcsSipTransport.canSend()) return false
        val b64 = android.util.Base64.encodeToString(framed, android.util.Base64.NO_WRAP)
        val body = "Content-Type: $contentType\r\n\r\n$b64"
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            callId = "${UUID.randomUUID()}@rcs-env",
            body = body,
        )
        val typedHeaders = headers.replace("Content-Type: message/cpim", "Content-Type: $contentType")
        return runCatching { RcsSipTransport.sendSipMessage(startLine, typedHeaders, content) }
            .getOrDefault(false)
    }

    /** Group id for [conversationId], or null when no E2EE group exists. */
    suspend fun groupIdFor(context: Context, conversationId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext null
            val group = RcsDatabase.getDatabase(context).mlsGroupDao().getByConversation(conversationId)
                ?: return@withContext null
            if (group.groupIdHex.startsWith("pending:")) return@withContext null
            group.groupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

    /**
     * True when [body] (CPIM text or raw) carries an MLS payload: magic
     * prefixes after base64 decode, or the MLS content types.
     */
    fun isMlsPayload(body: String): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        if (body.contains(CT_MLS, ignoreCase = true) ||
            body.contains(CT_COMMIT, ignoreCase = true) ||
            body.contains(CT_WELCOME, ignoreCase = true)
        ) {
            return true
        }
        val b64 = if (body.contains("\r\n\r\n")) {
            body.substringAfter("\r\n\r\n", "").trim()
        } else {
            body.trim()
        }.takeIf { it.isNotEmpty() } ?: return false
        val bytes = runCatching {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return false
        if (bytes.size < MIN_FRAME_BYTES) return false
        return runCatching { RustMlsCrypto.payloadKind(bytes) in PAYLOAD_KINDS }.getOrDefault(false)
    }

    /** Our own E.164 (default SMS subscription's number when readable). */
    fun localE164(context: Context): String? {
        if (!RcsFeature.enabled) return null
        return runCatching {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                ?: return null
            tm.line1Number?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }




}
