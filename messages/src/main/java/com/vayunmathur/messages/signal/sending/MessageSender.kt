package com.vayunmathur.messages.signal.sending

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore

class MessageSender(
    private val ws: SignalWebSocket,
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
    private val deviceManager: DeviceManager,
) {
    private val protocolStore = SignalProtocolStoreImpl(
        sessionStore, identityKeyStore, preKeyStore, signedPreKeyStore, kyberPreKeyStore, senderKeyStore
    )

    data class SendResult(val success: Boolean, val error: String? = null)

    suspend fun sendMessage(
        recipientAci: String,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): SendResult {
        val paddedContent = padContent(content.toByteArray())
        return try {
            sendToRecipient(recipientAci, paddedContent, timestamp)
            sendSyncMessage(recipientAci, content, timestamp)
            SendResult(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to $recipientAci", e)
            SendResult(success = false, error = e.message)
        }
    }

    suspend fun sendGroupMessage(
        groupId: String,
        memberAcis: List<String>,
        content: SignalServiceProtos.Content,
        timestamp: Long,
    ): List<SendResult> {
        val paddedContent = padContent(content.toByteArray())
        val results = memberAcis.map { aci ->
            if (aci == selfAci) return@map SendResult(success = true)
            try {
                sendToRecipient(aci, paddedContent, timestamp)
                SendResult(success = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group message to $aci", e)
                SendResult(success = false, error = e.message)
            }
        }
        try {
            sendSyncMessage(null, content, timestamp, groupId, memberAcis)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send group sync message", e)
        }
        return results
    }

    private suspend fun sendToRecipient(
        recipientAci: String,
        paddedContent: ByteArray,
        timestamp: Long,
        retryCount: Int = 0,
    ) {
        if (retryCount > 2) throw IllegalStateException("Too many retries sending to $recipientAci")

        val deviceIds = deviceManager.getDeviceIds(recipientAci)
        val messages = JSONArray()

        for (deviceId in deviceIds) {
            deviceManager.ensureSession(recipientAci, deviceId)
            val address = SignalProtocolAddress(recipientAci, deviceId)
            val encrypted = encryptFor(address, paddedContent)
            messages.put(JSONObject().apply {
                put("type", encrypted.first)
                put("destinationDeviceId", deviceId)
                put("destinationRegistrationId", encrypted.second)
                put("content", encrypted.third)
            })
        }

        val payload = JSONObject().apply {
            put("timestamp", timestamp)
            put("online", false)
            put("urgent", true)
            put("messages", messages)
        }

        val response = ws.sendRequest(
            "PUT",
            "/v1/messages/$recipientAci",
            payload.toString().toByteArray(),
            mapOf("Content-Type" to "application/json")
        )

        when (response.status) {
            in 200..299 -> return
            409 -> {
                Log.w(TAG, "Stale devices for $recipientAci, handling mismatched devices")
                handleMismatchedDevices(recipientAci, response.body.toByteArray())
                sendToRecipient(recipientAci, paddedContent, timestamp, retryCount + 1)
            }
            410 -> {
                Log.w(TAG, "Removed devices for $recipientAci, clearing sessions")
                handleGoneDevices(recipientAci, response.body.toByteArray())
                sendToRecipient(recipientAci, paddedContent, timestamp, retryCount + 1)
            }
            else -> throw IllegalStateException("Send failed with status ${response.status}")
        }
    }

    private fun encryptFor(
        address: SignalProtocolAddress,
        paddedContent: ByteArray,
    ): Triple<Int, Int, String> {
        val cipher = SessionCipher(protocolStore, address)
        val ciphertext = cipher.encrypt(paddedContent)
        val type = when (ciphertext.type) {
            CiphertextMessage.PREKEY_TYPE -> 3
            else -> 1
        }
        val regId = protocolStore.loadSession(address).remoteRegistrationId
        return Triple(type, regId, Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP))
    }

    private suspend fun sendSyncMessage(
        recipientAci: String?,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        groupId: String? = null,
        memberAcis: List<String>? = null,
    ) {
        val sentBuilder = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setTimestamp(timestamp)

        if (content.hasDataMessage()) {
            sentBuilder.setMessage(content.dataMessage)
        }

        if (recipientAci != null) {
            sentBuilder.setDestinationServiceId(recipientAci)
        }

        val syncContent = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(
                SignalServiceProtos.SyncMessage.newBuilder()
                    .setSent(sentBuilder.build())
                    .build()
            ).build()

        val paddedSync = padContent(syncContent.toByteArray())
        val selfDevices = deviceManager.getDeviceIds(selfAci)
        for (deviceId in selfDevices) {
            if (deviceId == selfDeviceId) continue
            try {
                deviceManager.ensureSession(selfAci, deviceId)
                val address = SignalProtocolAddress(selfAci, deviceId)
                val encrypted = encryptFor(address, paddedSync)
                val messages = JSONArray().put(JSONObject().apply {
                    put("type", encrypted.first)
                    put("destinationDeviceId", deviceId)
                    put("destinationRegistrationId", encrypted.second)
                    put("content", encrypted.third)
                })
                val payload = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("online", false)
                    put("urgent", true)
                    put("messages", messages)
                }
                ws.sendRequest(
                    "PUT",
                    "/v1/messages/$selfAci",
                    payload.toString().toByteArray(),
                    mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send sync to device $deviceId", e)
            }
        }
    }

    private fun handleGoneDevices(recipientAci: String, responseBody: ByteArray?) {
        if (responseBody == null) return
        try {
            val json = JSONObject(String(responseBody))
            val staleDevices = json.optJSONArray("staleDevices") ?: return
            for (i in 0 until staleDevices.length()) {
                val deviceId = staleDevices.getInt(i)
                val address = SignalProtocolAddress(recipientAci, deviceId)
                sessionStore.deleteSession(address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing gone devices response", e)
        }
    }

    private suspend fun handleMismatchedDevices(recipientAci: String, responseBody: ByteArray?) {
        if (responseBody == null) {
            deviceManager.refreshDevices(recipientAci)
            return
        }
        try {
            val json = JSONObject(String(responseBody))
            val missingDevices = json.optJSONArray("missingDevices")
            val extraDevices = json.optJSONArray("extraDevices")
            val staleDevices = json.optJSONArray("staleDevices")
            if (missingDevices != null) {
                for (i in 0 until missingDevices.length()) {
                    deviceManager.ensureSession(recipientAci, missingDevices.getInt(i))
                }
            }
            if (extraDevices != null) {
                for (i in 0 until extraDevices.length()) {
                    val address = SignalProtocolAddress(recipientAci, extraDevices.getInt(i))
                    sessionStore.deleteSession(address)
                }
            }
            if (staleDevices != null) {
                for (i in 0 until staleDevices.length()) {
                    val address = SignalProtocolAddress(recipientAci, staleDevices.getInt(i))
                    sessionStore.deleteSession(address)
                    deviceManager.ensureSession(recipientAci, staleDevices.getInt(i))
                }
            }
            deviceManager.refreshDevices(recipientAci)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing mismatched devices response", e)
            deviceManager.refreshDevices(recipientAci)
        }
    }

    companion object {
        private const val TAG = "SignalSender"

        fun padContent(content: ByteArray): ByteArray {
            val paddedLength = ((content.size + 160) / 160) * 160
            val padded = ByteArray(paddedLength)
            content.copyInto(padded)
            padded[content.size] = 0x80.toByte()
            return padded
        }
    }
}
