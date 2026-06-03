package com.vayunmathur.messages.signal.auth

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalDeviceData
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import java.io.IOException
import java.security.SecureRandom

object PreKeyManager {
    private const val TAG = "PreKeyManager"
    private const val BATCH_SIZE = 100
    private const val MIN_KEY_COUNT = 10

    suspend fun generateAndUploadPreKeys(
        ws: SignalWebSocket,
        deviceData: SignalDeviceData,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        uploadKeysForIdentity(ws, "aci", aciIdentityKeyPair)
        uploadKeysForIdentity(ws, "pni", pniIdentityKeyPair)
        Log.d(TAG, "Pre-keys uploaded for both identities")
    }

    suspend fun checkAndRefreshIfNeeded(
        ws: SignalWebSocket,
        deviceData: SignalDeviceData,
        aciIdentityKeyPair: IdentityKeyPair,
        pniIdentityKeyPair: IdentityKeyPair,
    ) {
        val aciCount = getPreKeyCount(ws, "aci")
        val pniCount = getPreKeyCount(ws, "pni")
        Log.d(TAG, "Pre-key counts: aci=$aciCount, pni=$pniCount")

        if (aciCount < MIN_KEY_COUNT) {
            uploadKeysForIdentity(ws, "aci", aciIdentityKeyPair)
        }
        if (pniCount < MIN_KEY_COUNT) {
            uploadKeysForIdentity(ws, "pni", pniIdentityKeyPair)
        }
    }

    private suspend fun getPreKeyCount(ws: SignalWebSocket, identity: String): Int {
        val response = ws.sendRequest("GET", "/v2/keys?identity=$identity")
        if (response.status != 200) return 0
        return JSONObject(response.body.toStringUtf8()).optInt("count", 0)
    }

    private suspend fun uploadKeysForIdentity(
        ws: SignalWebSocket,
        identity: String,
        identityKeyPair: IdentityKeyPair,
    ) {
        val random = SecureRandom()
        val preKeyBase = random.nextInt(Int.MAX_VALUE - BATCH_SIZE)
        val pqKeyBase = random.nextInt(Int.MAX_VALUE - BATCH_SIZE)

        val preKeys = JSONArray().apply {
            for (i in 0 until BATCH_SIZE) {
                val kp = Curve.generateKeyPair()
                put(JSONObject().apply {
                    put("keyId", preKeyBase + i)
                    put("publicKey", Base64.encodeToString(kp.publicKey.serialize(), Base64.NO_WRAP))
                })
            }
        }

        val spkKeyPair = Curve.generateKeyPair()
        val spkSignature = Curve.calculateSignature(identityKeyPair.privateKey, spkKeyPair.publicKey.serialize())
        val signedPreKey = JSONObject().apply {
            put("keyId", random.nextInt(Int.MAX_VALUE))
            put("publicKey", Base64.encodeToString(spkKeyPair.publicKey.serialize(), Base64.NO_WRAP))
            put("signature", Base64.encodeToString(spkSignature, Base64.NO_WRAP))
        }

        val pqPreKeys = JSONArray().apply {
            for (i in 0 until BATCH_SIZE) {
                val kemKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val sig = Curve.calculateSignature(identityKeyPair.privateKey, kemKp.publicKey.serialize())
                put(JSONObject().apply {
                    put("keyId", pqKeyBase + i)
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
