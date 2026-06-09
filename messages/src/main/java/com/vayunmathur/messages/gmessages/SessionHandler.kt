package com.vayunmathur.messages.gmessages

import android.util.Log
import com.google.protobuf.Message
import com.vayunmathur.messages.gmessages.PairFlow.ConfigVersion
import authentication.Authentication.AuthMessage
import rpc.Rpc.ActionType
import rpc.Rpc.BugleRoute
import rpc.Rpc.MessageType
import rpc.Rpc.OutgoingRPCData
import rpc.Rpc.OutgoingRPCMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Port of `pkg/libgm/session_handler.go`.
 *
 * Responsibilities:
 *  - Build outgoing RPC envelopes (sets the Auth fields, encrypts the
 *    payload with [AuthData.crypto], assembles the OutgoingRPCMessage).
 *  - Send via [RpcClient.postPbLite].
 *  - Match relay responses (delivered out-of-band via the long-poll)
 *    back to in-flight requests by request-ID.
 */
class SessionHandler(
    private val rpc: RpcClient,
    private val authProvider: () -> AuthData,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<IncomingRpc>>()

    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()

    /**
     * Send an RPC and wait up to [timeoutMs] ms for the matching
     * response. Returns null on timeout.
     */
    suspend fun sendAndWait(
        action: ActionType,
        payload: Message?,
        timeoutMs: Long = 30_000,
        messageType: MessageType = MessageType.BUGLE_MESSAGE,
    ): IncomingRpc? {
        val (requestId, envelope) = buildEnvelope(action, payload, messageType)
        val deferred = CompletableDeferred<IncomingRpc>()
        waiters[requestId] = deferred
        Log.i(TAG, "sendAndWait $action requestID=$requestId")
        try {
            // Dump the actual pblite payload so we can compare against
            // what libgm's working implementation sends.
            val pbliteBody = PbLite.encode(envelope)
            Log.i(TAG, "sendAndWait envelope: $pbliteBody")
            val resp = rpc.postPbLite(Endpoints.SendMessageUrl, envelope)
            // Dump the response body so we can see if the relay is
            // returning an error in the body (vs. just 200-with-no-result).
            val respBytes = try { resp.bodyAsBytes() } catch (_: Throwable) { ByteArray(0) }
            Log.i(TAG, "SendMessage HTTP ${resp.status.value}, response body: ${String(respBytes, Charsets.UTF_8).take(500)}")
            if (resp.status.value !in 200..299) {
                Log.w(TAG, "sendAndWait $action: HTTP ${resp.status.value}")
                waiters.remove(requestId)
                return null
            }
            Log.d(TAG, "sendAndWait $action: HTTP 200, waiting for relay response…")
            return withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
                waiters.remove(requestId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendAndWait $action failed", t)
            waiters.remove(requestId)
            return null
        }
    }

    /** Fire-and-forget: don't register a waiter. Used for things like
     *  ack messages where we don't care about the response. */
    suspend fun sendNoWait(action: ActionType, payload: Message?): Boolean {
        val (_, envelope) = buildEnvelope(action, payload, MessageType.BUGLE_MESSAGE)
        return try {
            val resp = rpc.postPbLite(Endpoints.SendMessageUrl, envelope)
            resp.status.value in 200..299
        } catch (t: Throwable) {
            Log.e(TAG, "sendNoWait $action failed", t)
            false
        }
    }

    /**
     * The "wake up" call libgm makes after each pair / reconnect.
     * Resets the internal session UUID (matching Go's ResetSessionID),
     * then sends GET_UPDATES with requestID == the new sessionID and
     * TTL = 0. Without this, the relay won't forward data events.
     */
    suspend fun setActiveSession(): Boolean {
        currentSessionId = UUID.randomUUID().toString()
        Log.i(TAG, "setActiveSession (GET_UPDATES, requestID=sessionID=$currentSessionId)")
        val envelope = buildEnvelopeWithFixedRequestId(
            action = ActionType.GET_UPDATES,
            payload = null,
            messageType = MessageType.BUGLE_MESSAGE,
            requestId = currentSessionId,
            omitTtl = true,
        )
        return try {
            val resp = rpc.postPbLite(Endpoints.SendMessageUrl, envelope)
            val body = try { resp.bodyAsBytes() } catch (_: Throwable) { ByteArray(0) }
            Log.i(TAG, "setActiveSession HTTP ${resp.status.value}, body: ${String(body, Charsets.UTF_8).take(200)}")
            resp.status.value in 200..299
        } catch (t: Throwable) {
            Log.e(TAG, "setActiveSession failed", t)
            false
        }
    }

    /** Called by the long-poll dispatcher when a response arrives. */
    fun deliverResponse(requestId: String, msg: IncomingRpc) {
        val waiter = waiters.remove(requestId)
        if (waiter == null) {
            Log.d(TAG, "no waiter for requestID=$requestId (action=${msg.action})")
            return
        }
        Log.i(TAG, "delivering response for requestID=$requestId action=${msg.action}")
        waiter.complete(msg)
    }

    /** Drop everything (called on stop / reset). */
    fun cancelAll() {
        waiters.values.forEach { it.cancel() }
        waiters.clear()
    }

    private fun buildEnvelope(
        action: ActionType,
        payload: Message?,
        messageType: MessageType,
    ): Pair<String, OutgoingRPCMessage> {
        val requestId = UUID.randomUUID().toString()
        return requestId to buildEnvelopeWithFixedRequestId(
            action = action,
            payload = payload,
            messageType = messageType,
            requestId = requestId,
            omitTtl = false,
        )
    }

    /**
     * Build an envelope with a caller-chosen [requestId]. Used by
     * [setActiveSession] so the requestID can be the sessionID — a
     * libgm-style convention that the relay treats as a wake-up.
     */
    private fun buildEnvelopeWithFixedRequestId(
        action: ActionType,
        payload: Message?,
        messageType: MessageType,
        requestId: String,
        omitTtl: Boolean,
    ): OutgoingRPCMessage {
        val auth = authProvider()

        val serializedPayload = payload?.toByteArray() ?: ByteArray(0)
        val encryptedPayload = if (serializedPayload.isNotEmpty()) {
            auth.crypto().encrypt(serializedPayload)
        } else ByteArray(0)

        val rpcData = OutgoingRPCData.newBuilder()
            .setRequestID(requestId)
            .setAction(action)
            .setEncryptedProtoData(encryptedPayload.toByteString())
            .setSessionID(currentSessionId)
            .build()

        val builder = OutgoingRPCMessage.newBuilder()
            .setData(
                OutgoingRPCMessage.Data.newBuilder()
                    .setRequestID(requestId)
                    .setBugleRoute(BugleRoute.DataEvent)
                    .setMessageData(rpcData.toByteString())
                    .setMessageTypeData(
                        OutgoingRPCMessage.Data.Type.newBuilder()
                            .setEmptyArr(util.Util.EmptyArr.getDefaultInstance())
                            .setMessageType(messageType)
                    )
            )
            .setAuth(
                OutgoingRPCMessage.Auth.newBuilder()
                    .setRequestID(requestId)
                    .setTachyonAuthToken((auth.tachyonToken()
                        ?: error("tachyon token is null — not paired or token expired")).toByteString())
                    .setConfigVersion(PairFlow.ConfigVersion)
            )

        if (!omitTtl) builder.setTTL(auth.tachyonTtlUs)

        auth.mobile()?.let { builder.setMobile(it) }
        return builder.build()
    }

    companion object {
        private const val TAG = "GMessages/Session"
    }
}

/**
 * Decoded incoming RPC message — either a paired event, a response to
 * one of our outbound RPCs, or a server-pushed update.
 *
 * Mirrors libgm's `IncomingRPCMessage` struct (event_handler.go) but
 * keeps just the v1-essential fields.
 */
data class IncomingRpc(
    val responseId: String,
    val requestId: String?,
    val action: ActionType?,
    /** Decrypted payload bytes, if any. */
    val decryptedData: ByteArray?,
    /** Raw decoded pair event (BugleRoute=PairEvent), if applicable. */
    val pairEvent: PairEventKind = PairEventKind.None,
    /** Paired data when [pairEvent] is [PairEventKind.Paired]. */
    val pairedDeviceMobileB64: String? = null,
    val pairedDeviceBrowserB64: String? = null,
    val pairedTachyonTokenB64: String? = null,
    val pairedTachyonTtlUs: Long = 0L,
)

enum class PairEventKind { None, Paired, Revoked }
