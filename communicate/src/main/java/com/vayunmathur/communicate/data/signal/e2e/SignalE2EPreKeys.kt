package com.vayunmathur.communicate.data.signal.e2e

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.Companion.ONE_TIME_PREKEY_BATCH
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.Companion.ONE_TIME_PREKEY_FLOOR
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.Companion.b64
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.Companion.signSignedPreKey
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E.PreKeyUpload

private const val X25519_POINT_LEN = 32

/**
 * Pre-key lifecycle for [SignalE2E] (split for file length).
 * Internal functions on [SignalE2E]; behavior identical, call sites unchanged.
 */

internal fun SignalE2E.ensureLocalPreKeys(): PreKeyUpload {
    val signed = ensureSignedPreKey()
    val kyber = ensureLastResortKyberPreKey()
    val oneTime = ensureOneTimePreKeys()
    return PreKeyUpload(signedPreKey = signed, lastResortKyber = kyber, oneTimeEcPreKeys = oneTime)
}

/**
 * Returns a payload when the signed pre-key had to be regenerated and needs registering, null when the
 * stored one was sound and simply seeded.
 */
internal fun SignalE2E.ensureSignedPreKey(): PreKeyUpload.KeyEntity? {
    val storedId = auth.signedPreKeyId
    if (storedId != 0 && protocolStore.containsSignedPreKey(storedId)) return null
    if (storedId != 0 && seedStoredSignedPreKey(storedId)) return null
    return rotateSignedPreKey()
}

/** True when the stored material verified and was seeded into the store. */
internal fun SignalE2E.seedStoredSignedPreKey(id: Int): Boolean {
    val pub = b64(auth.signedPreKeyPublic)
    val priv = b64(auth.signedPreKeyPrivate)
    val signature = b64(auth.signedPreKeySignature)
    if (pub.isEmpty() || priv.isEmpty()) {
        Log.status(SignalE2E.TAG, "no stored signed pre-key material; rotating")
        return false
    }
    return try {
        val privateKey = ECPrivateKey(priv)
        val derivedPublic = RustSignalCrypto.publicFromPrivate(priv)
        if (derivedPublic == null) {
            Log.status(SignalE2E.TAG, "could not derive public half for signed pre-key $id; rotating")
            return false
        }
        // ECPublicKey.serialize() is 0x05-prefixed (33B) while publicFromPrivate returns the raw
        // 32B point; compare the trailing point bytes so the two serializations line up.
        if (!derivedPublic.takeLast(X25519_POINT_LEN).toByteArray()
                .contentEquals(pub.takeLast(X25519_POINT_LEN).toByteArray())) {
            // The server serves the public half; without the matching private half every inbound
            // pre-key message fails in the key agreement rather than at lookup.
            Log.status(
                SignalE2E.TAG,
                "stored signed pre-key $id is inconsistent (private half derives a different public key); rotating")
            return false
        }
        val identityOk = signature.isNotEmpty() &&
            ECPublicKey(ownIdentityPublicKey).verifySignature(pub, signature)
        if (!identityOk) {
            Log.status(
                SignalE2E.TAG,
                "stored signed pre-key $id signature does not verify under our identity key; rotating")
            return false
        }
        protocolStore.storeSignedPreKey(
            id,
            SignedPreKeyRecord(id, System.currentTimeMillis(), ECKeyPair(ECPublicKey(pub), privateKey), signature),
        )
        Log.status(SignalE2E.TAG, "seeded signed pre-key $id into the protocol store")
        true
    } catch (expected: Throwable) {
        Log.status(SignalE2E.TAG, "could not rebuild signed pre-key $id; rotating", expected)
        false
    }
}

internal fun SignalE2E.rotateSignedPreKey(): PreKeyUpload.KeyEntity? = try {
    val keyPair = ECKeyPair.generate()
    val publicKey = keyPair.publicKey.serialize()
    val signature = signSignedPreKey(ownIdentityPrivate, publicKey)
    val id = auth.signedPreKeyId.takeIf { it != 0 }?.plus(1) ?: 1
    protocolStore.storeSignedPreKey(
        id,
        SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature),
    )
    Log.status(SignalE2E.TAG, "rotated signed pre-key to $id; needs registering")
    PreKeyUpload.KeyEntity(id, publicKey, signature)
} catch (expected: Throwable) {
    Log.error(SignalE2E.TAG, "could not rotate the signed pre-key", expected)
    null
}

internal fun SignalE2E.ensureLastResortKyberPreKey(): PreKeyUpload.KeyEntity? {
    val existing = runBlocking { db.e2eKyberPreKeyDao().getAll() }
    if (existing.any { it.lastResort }) return null
    return try {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicKey = keyPair.publicKey.serialize()
        val signature = signSignedPreKey(ownIdentityPrivate, publicKey)
        val id = (runBlocking { db.e2eKyberPreKeyDao().getAll() }.maxOfOrNull { it.id } ?: 0) + 1
        val record = KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        protocolStore.storeKyberPreKey(id, record, lastResort = true)
        Log.status(SignalE2E.TAG, "generated last-resort Kyber pre-key $id; needs registering")
        PreKeyUpload.KeyEntity(id, publicKey, signature)
    } catch (expected: Throwable) {
        Log.status(SignalE2E.TAG, "could not generate a last-resort Kyber pre-key", expected)
        null
    }
}

internal fun SignalE2E.ensureOneTimePreKeys(): List<PreKeyUpload.KeyEntity> {
    val have = runBlocking { db.e2ePreKeyDao().getAll() }.size
    if (have >= ONE_TIME_PREKEY_FLOOR) return emptyList()
    val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
    return (1..ONE_TIME_PREKEY_BATCH).mapNotNull { offset ->
        val id = maxId + offset
        try {
            val keyPair = ECKeyPair.generate()
            // Stored through the store so it lands as a libsignal record, not a bespoke blob.
            protocolStore.storePreKey(id, PreKeyRecord(id, keyPair))
            PreKeyUpload.KeyEntity(id, keyPair.publicKey.serialize())
        } catch (expected: Throwable) {
            Log.status(SignalE2E.TAG, "could not generate one-time pre-key $id", expected)
            null
        }
    }
}
