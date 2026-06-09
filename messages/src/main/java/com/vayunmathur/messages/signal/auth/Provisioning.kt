package com.vayunmathur.messages.signal.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.ProvisioningProtos
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalDeviceData
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Provisioning {
    private const val TAG = "Provisioning"
    private const val WS_PROVISIONING_URL = "wss://chat.signal.org/v1/websocket/provisioning/"

    sealed interface ProvisioningEvent {
        data class QrUrl(val url: String) : ProvisioningEvent
        data class Success(val deviceData: SignalDeviceData) : ProvisioningEvent
        data class Error(val message: String) : ProvisioningEvent
    }

    fun startProvisioning(context: Context): Flow<ProvisioningEvent> = channelFlow {
        try {
            val cipher = ProvisioningCipher()
            val ws = SignalWebSocket(context)
            val requests = Channel<com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketRequestMessage>(Channel.UNLIMITED)
            ws.incomingRequestHandler = { requests.trySend(it) }

            ws.connect(WS_PROVISIONING_URL, autoReconnect = false)
            ws.connectionEvents.first { it is SignalWebSocket.ConnectionEvent.Connected }

            // Step 1: Receive provisioning UUID via PUT /v1/address
            var addressReq = requests.receive()
            while (addressReq.verb != "PUT" || addressReq.path != "/v1/address") {
                Log.d(TAG, "Skipping non-address request: ${addressReq.verb} ${addressReq.path} (${addressReq.body.size()} bytes)")
                ws.sendResponse(addressReq.id, 200)
                addressReq = requests.receive()
            }
            val provAddress = ProvisioningProtos.ProvisioningAddress.parseFrom(addressReq.body)
            ws.sendResponse(addressReq.id, 200)
            Log.d(TAG, "Got provisioning address: ${provAddress.address}")

            // Step 2: Emit QR code URL
            val pubKeyBase64 = Base64.encodeToString(cipher.getPublicKey().serialize(), Base64.NO_WRAP)
            val qrUrl = "sgnl://linkdevice?uuid=${Uri.encode(provAddress.address)}&pub_key=${Uri.encode(pubKeyBase64)}&capabilities=${Uri.encode("backup4,backup5")}"
            send(ProvisioningEvent.QrUrl(qrUrl))
            Log.d(TAG, "QR URL generated")

            // Step 3: Receive encrypted provisioning message via PUT /v1/message
            var msgReq = requests.receive()
            while (msgReq.verb != "PUT" || msgReq.path != "/v1/message") {
                Log.d(TAG, "Skipping non-message request: ${msgReq.verb} ${msgReq.path} (${msgReq.body.size()} bytes)")
                ws.sendResponse(msgReq.id, 200)
                msgReq = requests.receive()
            }
            Log.d(TAG, "Got provision envelope: ${msgReq.body.size()} bytes")
            val envelope = ProvisioningProtos.ProvisionEnvelope.parseFrom(msgReq.body)
            Log.d(TAG, "Envelope publicKey: ${envelope.publicKey.size()} bytes, body: ${envelope.body.size()} bytes")
            ws.sendResponse(msgReq.id, 200)
            ws.disconnect()

            // Step 4: Decrypt provisioning message
            val provMsg = cipher.decrypt(envelope)

            // Ensure HTTP client is initialized
            SignalHttpClient.init(context)

            // Step 5: Extract identity key pairs
            val aciPublicKey = ECPublicKey(provMsg.aciIdentityKeyPublic.toByteArray())
            val aciPrivateKey = ECPrivateKey(provMsg.aciIdentityKeyPrivate.toByteArray())
            val aciIdentityKeyPair = IdentityKeyPair(IdentityKey(aciPublicKey), aciPrivateKey)

            val pniPublicKey = ECPublicKey(provMsg.pniIdentityKeyPublic.toByteArray())
            val pniPrivateKey = ECPrivateKey(provMsg.pniIdentityKeyPrivate.toByteArray())
            val pniIdentityKeyPair = IdentityKeyPair(IdentityKey(pniPublicKey), pniPrivateKey)

            val number = provMsg.number
            val provisioningCode = provMsg.provisioningCode

            // Step 6: Generate credentials
            val random = SecureRandom()
            val alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val password = (1..22).map { alphanumeric[random.nextInt(alphanumeric.length)] }.joinToString("")
            val aciRegistrationId = random.nextInt(16383) + 1
            val pniRegistrationId = random.nextInt(16383) + 1

            // Step 7: Generate signed pre-keys and Kyber last-resort pre-keys
            val aciSignedPreKey = generateSignedPreKey(1, aciIdentityKeyPair)
            val pniSignedPreKey = generateSignedPreKey(1, pniIdentityKeyPair)
            val aciPqLastResort = generateKyberPreKey(1, aciIdentityKeyPair)
            val pniPqLastResort = generateKyberPreKey(1, pniIdentityKeyPair)

            // Store generated keys in the local protocol stores
            val database = SignalDatabase.getInstance(context)
            val preKeyStore = SignalPreKeyStore(database)
            preKeyStore.storeSignedPreKey(aciSignedPreKey.id, aciSignedPreKey)
            preKeyStore.storeSignedPreKey(pniSignedPreKey.id, pniSignedPreKey)
            preKeyStore.storeLastResortKyberPreKey(aciPqLastResort.id, aciPqLastResort)
            preKeyStore.storeLastResortKyberPreKey(pniPqLastResort.id, pniPqLastResort)

            // Step 8: Encrypt device name
            val encryptedName = encryptDeviceName("Android", aciIdentityKeyPair.publicKey.publicKey)

            // Step 9: Build and send device confirmation
            val payload = JSONObject().apply {
                put("verificationCode", provisioningCode)
                put("accountAttributes", JSONObject().apply {
                    put("fetchesMessages", true)
                    put("name", encryptedName)
                    put("registrationId", aciRegistrationId)
                    put("pniRegistrationId", pniRegistrationId)
                    put("capabilities", JSONObject().apply {
                        put("attachmentBackfill", true)
                        put("spqr", true)
                    })
                })
                put("aciSignedPreKey", signedPreKeyToJson(aciSignedPreKey))
                put("pniSignedPreKey", signedPreKeyToJson(pniSignedPreKey))
                put("aciPqLastResortPreKey", kyberPreKeyToJson(aciPqLastResort))
                put("pniPqLastResortPreKey", kyberPreKeyToJson(pniPqLastResort))
            }

            val response = SignalHttpClient.request(
                method = "PUT",
                path = "/v1/devices/link",
                body = payload.toString().toByteArray(),
                username = number,
                password = password,
            )
            if (!response.isSuccessful) {
                throw IOException("Device link failed: ${response.code}")
            }

            val respJson = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
            val aci = respJson.getString("uuid")
            val pni = respJson.getString("pni")
            val deviceId = respJson.optInt("deviceId", 1)

            // Step 10: Build and emit device data
            val deviceData = SignalDeviceData(
                aci = aci,
                pni = pni,
                deviceId = deviceId,
                number = number,
                password = password,
                aciIdentityKeyPair = Base64.encodeToString(aciIdentityKeyPair.serialize(), Base64.NO_WRAP),
                pniIdentityKeyPair = Base64.encodeToString(pniIdentityKeyPair.serialize(), Base64.NO_WRAP),
                aciRegistrationId = aciRegistrationId,
                pniRegistrationId = pniRegistrationId,
                profileKey = provMsg.profileKey?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) },
            )
            send(ProvisioningEvent.Success(deviceData))
            Log.d(TAG, "Provisioning complete, deviceId=$deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning failed", e)
            send(ProvisioningEvent.Error(e.message ?: "Unknown error"))
        }
    }

    private fun generateSignedPreKey(id: Int, identityKeyPair: IdentityKeyPair): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    private fun generateKyberPreKey(id: Int, identityKeyPair: IdentityKeyPair): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    private fun signedPreKeyToJson(record: SignedPreKeyRecord) = JSONObject().apply {
        put("keyId", record.id)
        put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
        put("signature", Base64.encodeToString(record.signature, Base64.NO_WRAP))
    }

    private fun kyberPreKeyToJson(record: KyberPreKeyRecord) = JSONObject().apply {
        put("keyId", record.id)
        put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
        put("signature", Base64.encodeToString(record.signature, Base64.NO_WRAP))
    }

    private fun encryptDeviceName(name: String, identityPublicKey: ECPublicKey): String {
        val ephemeral = ECKeyPair.generate()
        val masterSecret = ephemeral.privateKey.calculateAgreement(identityPublicKey)

        val syntheticIvKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(masterSecret, "HmacSHA256"))
            doFinal("auth".toByteArray())
        }

        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val syntheticIv = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(syntheticIvKey, "HmacSHA256"))
            doFinal(nameBytes)
        }.copyOfRange(0, 16)

        val cipherKeyBase = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(masterSecret, "HmacSHA256"))
            doFinal("cipher".toByteArray())
        }
        val cipherKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(cipherKeyBase, "HmacSHA256"))
            doFinal(syntheticIv)
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(ByteArray(16)))
        val ciphertext = cipher.doFinal(nameBytes)

        val deviceName = com.vayunmathur.messages.signal.proto.DeviceNameProtos.DeviceName.newBuilder()
            .setEphemeralPublic(com.google.protobuf.ByteString.copyFrom(ephemeral.publicKey.serialize()))
            .setSyntheticIv(com.google.protobuf.ByteString.copyFrom(syntheticIv))
            .setCiphertext(com.google.protobuf.ByteString.copyFrom(ciphertext))
            .build()

        return Base64.encodeToString(deviceName.toByteArray(), Base64.NO_WRAP)
    }
}
