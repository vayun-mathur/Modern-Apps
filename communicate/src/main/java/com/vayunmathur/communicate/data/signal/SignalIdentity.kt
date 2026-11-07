package com.vayunmathur.communicate.data.signal

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.e2e.acceptIdentity
import com.vayunmathur.communicate.data.signal.e2e.storedIdentityKey
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.IdentityKey
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * SignalClient identity + contact resolution (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 */

/** A profile key can arrive on any DataMessage, including the one wrapped inside an edit. */
internal fun SignalClient.profileKeyFrom(parsed: SignalProtocol.ParsedContent): ByteArray? {
    val dm = when (parsed) {
        is SignalProtocol.ParsedContent.Data -> parsed.dataMessage
        is SignalProtocol.ParsedContent.Edit ->
            if (parsed.editMessage.hasDataMessage()) parsed.editMessage.dataMessage else null
        else -> null
    } ?: return null
    return if (dm.hasProfileKey()) dm.profileKey.toByteArray() else null
}

/**
 * A peer's identity key changed. Reported rather than absorbed: this is either a reinstall or a key
 * substitution, and only the user comparing safety numbers can tell the difference.
 */
internal fun SignalClient.reportIdentityChange(peerAci: String, newIdentityKey: ByteArray) {
    val hex = SignalGroups.run { newIdentityKey.toHex() }
    pendingIdentityChanges[peerAci] = newIdentityKey
    Log.status(TAG, "identity key changed for $peerAci")
    scope.launch {
        eventsMutable.emit(
            SignalEvent.IdentityKeyChanged(
                conversationId = SignalProtocol.toConversationId(peerAci, null as ByteArray?),
                peerAci = peerAci,
                newIdentityKeyHex = hex,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }
}

/** The unaccepted identity key a peer is presenting, if any. */
fun SignalClient.pendingIdentityChange(peerAci: String): ByteArray? = pendingIdentityChanges[peerAci]

/**
 * The safety number to show for [peerAci] — for the key they are currently presenting when that
 * differs from the one on record, otherwise for the accepted one. Null when it cannot be computed.
 */
suspend fun SignalClient.safetyNumber(peerAci: String): String? {
    val e = e2e ?: return null
    val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: return null
    val remoteKey = pendingIdentityChanges[peerAci] ?: e.storedIdentityKey(peerAci) ?: return null
    return SignalSafetyNumber.compute(
        localAci = localAci,
        localIdentityKey = e.ownIdentityPublicKeySerialized,
        remoteAci = peerAci,
        remoteIdentityKey = remoteKey,
    )
}

/**
 * Accept the identity key [peerAci] is presenting, after the user has compared safety numbers.
 *
 * [expectedKeyHex] must match the key currently pending, so accepting can only ever apply to the key
 * whose safety number was actually shown — otherwise a key swapped in between display and tap would
 * be trusted instead. Existing sessions are archived so the next send builds against the new key.
 */
suspend fun SignalClient.acceptIdentityChange(peerAci: String, expectedKeyHex: String): Boolean {
    val e = e2e ?: return false
    val pending = pendingIdentityChanges[peerAci] ?: run {
        Log.status(TAG, "no pending identity change for $peerAci")
        return false
    }
    if (!SignalGroups.run { pending.toHex() }.equals(expectedKeyHex, ignoreCase = true)) {
        Log.status(TAG, "refusing to accept a different key than the one shown for $peerAci")
        return false
    }
    val accepted = e.acceptIdentity(peerAci, pending)
    if (accepted) {
        pendingIdentityChanges.remove(peerAci)
        Log.status(TAG, "accepted the new identity key for $peerAci")
    }
    return accepted
}

/**
 * Associate a sender's ACI with the PNI we already knew them by, when they prove the link.
 *
 * `PniSignatureMessage` carries their PNI plus a signature *by* the PNI identity key *of* the ACI
 * identity key, so the two can be merged without trusting the server. Until they are merged, a reply
 * arrives under the ACI while everything we sent went to the PNI — which shows up as a second
 * conversation with a UUID for a name.
 */
internal suspend fun SignalClient.linkPniToAci(
    senderAci: String,
    pniSignature: SignalServiceProtos.PniSignatureMessage,
) {
    val e = e2e ?: return
    val database = db ?: return
    val pniRaw = try {
        SignalProtocol.bytesToUuidString(pniSignature.pni.toByteArray())
    } catch (_: Exception) {
        null
    }
    if (pniRaw.isNullOrEmpty()) return
    val pniServiceId = "$PNI_PREFIX$pniRaw"

    val aciIdentity = e.storedIdentityKey(senderAci)
    val pniIdentity = e.storedIdentityKey(pniServiceId)
    if (aciIdentity == null || pniIdentity == null) {
        Log.status(TAG, "cannot verify the PNI signature for $senderAci: missing an identity key")
        return
    }
    if (!verifyPniSignature(senderAci, pniServiceId, aciIdentity, pniIdentity, pniSignature)) return
    mergePniContact(database, senderAci, pniServiceId, pniRaw)
}

/** Merge the PNI contact row into the ACI after verification. */
private suspend fun SignalClient.mergePniContact(
    database: SignalDatabase,
    senderAci: String,
    pniServiceId: String,
    pniRaw: String,
) {
    val contact = try {
        database.contactDao().getByPni(pniServiceId) ?: database.contactDao().getByPni(pniRaw)
    } catch (_: Exception) {
        null
    }
    if (contact == null) {
        Log.status(TAG, "verified PNI signature from $senderAci but no contact holds $pniServiceId")
        return
    }
    if (contact.aci == senderAci) return
    try {
        // The row is keyed by aci, so replace it rather than update in place.
        database.contactDao().deleteByPhone(contact.phoneE164)
        database.contactDao().upsert(contact.copy(aci = senderAci))
        Log.status(TAG, "associated $senderAci with ${contact.phoneE164} via a verified PNI signature")
    } catch (expected: Throwable) {
        Log.status(TAG, "could not associate $senderAci with ${contact.phoneE164}", expected)
    }
}

/** Verify a PNI alternate-identity signature, logging failures. */
private suspend fun SignalClient.verifyPniSignature(
    senderAci: String,
    pniServiceId: String,
    aciIdentity: ByteArray,
    pniIdentity: ByteArray,
    pniSignature: SignalServiceProtos.PniSignatureMessage,
): Boolean {
    val verified = try {
        IdentityKey(pniIdentity).verifyAlternateIdentity(
            IdentityKey(aciIdentity),
            pniSignature.signature.toByteArray(),
        )
    } catch (expected: Throwable) {
        Log.status(TAG, "PNI signature verification failed for $senderAci", expected)
        false
    }
    if (!verified) {
        Log.status(TAG, "invalid PNI signature from $senderAci; not associating it with $pniServiceId")
        return false
    }
    return true
}

/** The contact behind a service id, matched on ACI or PNI. */
private suspend fun SignalClient.contactFor(serviceId: String): SignalContact? = try {
    db?.contactDao()?.let { dao ->
        dao.get(serviceId) ?: dao.getByPni(serviceId) ?: dao.getByPni(serviceId.removePrefix(PNI_PREFIX))
    }
} catch (_: Exception) {
    null
}

/** A human name for a sender, falling back through phone number to the raw id. */
internal suspend fun SignalClient.displayNameFor(serviceId: String): String {
    val contact = contactFor(serviceId) ?: return serviceId
    return contact.displayName.takeIf { it.isNotBlank() }
        ?: contact.phoneE164.takeIf { it.isNotBlank() }
        ?: serviceId
}

/**
 * The conversation a sender belongs to. Anchored on the contact's phone number when we know it, so a
 * reply from an ACI lands in the same thread as everything we sent to their PNI.
 */
internal suspend fun SignalClient.conversationIdFor(serviceId: String): String =
    contactFor(serviceId)?.phoneE164?.takeIf { it.isNotBlank() } ?: serviceId
