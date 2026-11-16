package com.vayunmathur.communicate.data

import android.content.Context
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Create an encrypted MLS group for [conversationId] with [members].
 *
 * Fast path: every member's key package is cached → create immediately and
 * fan out Welcome + commit. Slow path: publish our package, request the
 * missing ones, park the setup in [RcsPendingGroups]; the sync service
 * completes it as packages arrive (thread opens plaintext meanwhile and
 * upgrades — see the pending banner).
 *
 * Returns true when the setup was created immediately OR parked for later;
 * false only when nothing could be done (gate off, no identity, no transport).
 */
suspend fun CommunicateRepository.createEncryptedRcsGroup(
    context: Context,
    conversationId: String,
    members: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || members.isEmpty()) return@withContext false
    val local = RcsE2E.localE164(context) ?: return@withContext false
    if (!RcsSipTransport.canSend()) return@withContext false
    // Cached packages first (consumes single-use init keys).
    val cached = members.associateWith { RcsPeerKeys.consume(it) }
    if (cached.values.all { it != null }) {
        @Suppress("UNCHECKED_CAST")
        val ok = RcsE2E.setupEncryptedGroup(context, local, conversationId, cached as Map<String, ByteArray>)
        if (!ok) {
            // Restore what we consumed so a retry can proceed.
            cached.forEach { (peer, kp) -> if (kp != null) RcsPeerKeys.store(peer, kp) }
        }
        return@withContext ok
    }
    // Slow path: publish ours, request the missing, park the setup.
    RcsKeyDirectory.publishTo(context, local, members.first())
    cached.forEach { (peer, kp) ->
        if (kp == null) {
            RcsE2E.requestKeyPackage(peer)
        } else {
            RcsPeerKeys.store(peer, kp)
        }
    }
    RcsPendingGroups.add(conversationId, members)
    true
}
