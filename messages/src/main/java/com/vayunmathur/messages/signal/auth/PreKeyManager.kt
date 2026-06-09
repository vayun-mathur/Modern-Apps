package com.vayunmathur.messages.signal.auth

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.security.SecureRandom

object PreKeyManager {
    private const val TAG = "PreKeyManager"
    private const val BATCH_SIZE = 100
    private const val MIN_KEY_COUNT = 10

    suspend fun generateAndUploadPreKeys(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        uploadKeysForIdentity(ws, preKeyStore, "aci", aciIdentityKeyPair)
        uploadKeysForIdentity(ws, preKeyStore, "pni", pniIdentityKeyPair)
        Log.d(TAG, "Pre-keys uploaded for both identities")
    }

    suspend fun checkAndRefreshIfNeeded(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        val aciCount = getPreKeyCount(ws, "aci")
        val pniCount = getPreKeyCount(ws, "pni")
        Log.d(TAG, "Pre-key counts: aci=$aciCount, pni=$pniCount")

        if (aciCount < MIN_KEY_COUNT) {
            uploadKeysForIdentity(ws, preKeyStore, "aci", aciIdentityKeyPair)
        }
        if (pniCount < MIN_KEY_COUNT) {
            uploadKeysForIdentity(ws, preKeyStore, "pni", pniIdentityKeyPair)
        }
    }

    private suspend fun getPreKeyCount(ws: SignalWebSocket, identity: String): Int {
        val response = ws.sendRequest("GET", "/v2/keys?identity=$identity")
        if (response.status != 200) return 0
        return JSONObject(response.body.toStringUtf8()).optInt("count", 0)
    }

    private suspend fun uploadKeysForIdentity(
        ws: SignalWebSocket,
        preKeyStore: SignalPreKeyStore,
        identity: String,
        identityKeyPair: IdentityKeyPair,
    ) {
        val random = SecureRandom()
        val preKeyBase = random.nextInt(Int.MAX_VALUE - BATCH_SIZE)
        val pqKeyBase = random.nextInt(Int.MAX_VALUE - BATCH_SIZE)

        val preKeys = JSONArray().apply {
            for (i in 0 until BATCH_SIZE) {
                val kp = ECKeyPair.generate()
                val id = preKeyBase + i
                val record = PreKeyRecord(id, kp)
                preKeyStore.storePreKey(id, record)
                put(JSONObject().apply {
                    put("keyId", id)
                    put("publicKey", Base64.encodeToString(kp.publicKey.serialize(), Base64.NO_WRAP))
                })
            }
        }

        val spkId = random.nextInt(Int.MAX_VALUE)
        val spkKeyPair = ECKeyPair.generate()
        val spkSignature = identityKeyPair.privateKey.calculateSignature(spkKeyPair.publicKey.serialize())
        val spkRecord = SignedPreKeyRecord(spkId, System.currentTimeMillis(), spkKeyPair, spkSignature)
        preKeyStore.storeSignedPreKey(spkId, spkRecord)
        val signedPreKey = JSONObject().apply {
            put("keyId", spkId)
            put("publicKey", Base64.encodeToString(spkKeyPair.publicKey.serialize(), Base64.NO_WRAP))
            put("signature", Base64.encodeToString(spkSignature, Base64.NO_WRAP))
        }

        val pqPreKeys = JSONArray().apply {
            for (i in 0 until BATCH_SIZE) {
                val kemKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val sig = identityKeyPair.privateKey.calculateSignature(kemKp.publicKey.serialize())
                val id = pqKeyBase + i
                val record = KyberPreKeyRecord(id, System.currentTimeMillis(), kemKp, sig)
                preKeyStore.storeKyberPreKey(id, record)
                put(JSONObject().apply {
                    put("keyId", id)
                    put("publicKey", Base64.encodeToString(kemKp.publicKey.serialize(), Base64.NO_WRAP))
                    put("signature", Base64.encodeToString(sig, Base64.NO_WRAP))
                })
            }
        }

        val payload = JSONObject().apply {
            put("preKeys", preKeys)
            put("signedPreKey", signedPreKey)
            put("pqPreKeys", pqPreKeys)
            put("identityKey", Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
        }

        val response = ws.sendRequest(
            method = "PUT",
            path = "/v2/keys?identity=$identity",
            body = payload.toString().toByteArray(),
            headers = mapOf("Content-Type" to "application/json"),
        )
        if (response.status !in 200..299) {
            throw IOException("Pre-key upload failed for $identity: ${response.status}")
        }
        Log.d(TAG, "Uploaded $identity pre-keys")
    }
}
