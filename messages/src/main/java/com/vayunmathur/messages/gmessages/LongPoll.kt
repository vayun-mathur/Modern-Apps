package com.vayunmathur.messages.gmessages

import android.util.Base64
import android.util.Log
import authentication.Authentication.AuthMessage
import authentication.Authentication.PairedData
import client.Client.ReceiveMessagesRequest
import com.google.protobuf.InvalidProtocolBufferException
import events.Events.RPCPairData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rpc.Rpc.BugleRoute
import rpc.Rpc.IncomingRPCMessage
import rpc.Rpc.LongPollingPayload
import rpc.Rpc.RPCMessageData

/**
 * Port of `pkg/libgm/longpoll.go` (minus the ditto pinger).
 *
 * Long-poll response body framing:
 *   - Opens with `[[`
 *   - Each message: a pblite-encoded JSON array delimited by `,`
 *   - Closes with `]]`
 *
 * We accumulate bytes into a buffer; once the buffer parses as valid
 * pblite, we dispatch the [LongPollingPayload] and clear the
 * accumulator. Reconnect on any clean close or error with exponential
 * backoff (5 s → 60 s).
 */
class LongPoll(
    private val rpc: RpcClient,
    private val authProvider: () -> AuthData,
    private val sessionHandler: SessionHandler,
    private val onEvent: suspend (LongPollEvent) -> Unit,
    private val refreshToken: suspend () -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        var backoffMs = 0L
        while (true) {
            if (backoffMs > 0) {
                Log.i(TAG, "reconnecting in ${backoffMs / 1000}s")
                delay(backoffMs)
            }
            val ok = try {
                openAndRead()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "long-poll error: ${t.message}")
                false
            }
            if (!kotlin.coroutines.coroutineContext.isActive) return
            backoffMs = if (ok) 0L else (if (backoffMs == 0L) 5_000L else minOf(60_000L, backoffMs * 2))
        }
    }

    private suspend fun openAndRead(): Boolean {
        // Refresh token before each poll iteration, matching Go's doLongPoll.
        try { refreshToken() } catch (t: Throwable) {
            Log.w(TAG, "token refresh before long-poll failed: ${t.message}")
        }
        val auth = authProvider()
        val token = auth.tachyonToken() ?: run {
            Log.w(TAG, "no tachyon token, can't open long-poll")
            return false
        }

        val payload = ReceiveMessagesRequest.newBuilder()
            .setAuth(
                AuthMessage.newBuilder()
                    .setRequestID(java.util.UUID.randomUUID().toString())
                    .setNetwork(PairFlow.QrNetwork)
                    .setTachyonAuthToken(token.toByteString())
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
            .setUnknown(
                ReceiveMessagesRequest.UnknownEmptyObject2.newBuilder()
                    .setUnknown(ReceiveMessagesRequest.UnknownEmptyObject1.getDefaultInstance())
            )
            .build()

        // Log the actual JSON we send so we can spot encoder bugs.
        val pbliteBody = PbLite.encode(payload)
        Log.i(TAG, "opening long-poll with body: $pbliteBody")
        return rpc.openLongPoll(Endpoints.ReceiveMessagesUrl, payload) { response ->
            if (response.status.value !in 200..299) {
                Log.e(TAG, "long-poll HTTP ${response.status.value}")
                return@openLongPoll false
            }
            Log.i(TAG, "long-poll open (HTTP ${response.status.value})")
            consumeBody(response.bodyAsChannel())
        }
    }

    private suspend fun consumeBody(channel: io.ktor.utils.io.ByteReadChannel): Boolean {
        // Body framing per `pkg/libgm/longpoll.go.readLongPoll`:
        //   - First two bytes: `[[`
        //   - Each message: a pblite-encoded JSON array (also starting
        //     with `[`), separated by `,`
        //   - Stream end: `]]`
        // We walk bytes once, tracking JSON bracket depth with string-
        // escape awareness, and emit one message every time depth returns
        // to 0 at the top level of the outer array.
        val readBuf = ByteArray(64 * 1024)
        val accumulator = ByteArrayBuilder()
        var sawAnyEvent = false
        var depth = 0
        var inString = false
        var escape = false
        var skippedOpening = false
        var openingBracketsSeen = 0
        var totalBytesRead = 0L

        while (!channel.isClosedForRead) {
            val n = channel.readAvailable(readBuf, 0, readBuf.size)
            if (n <= 0) break
            totalBytesRead += n
            if (totalBytesRead <= 512) {
                Log.i(
                    TAG,
                    "first $n bytes (acc=${totalBytesRead}): ${String(readBuf, 0, minOf(n, 200))}",
                )
            }

            for (i in 0 until n) {
                val b = readBuf[i]
                val c = b.toInt().toChar()

                if (!skippedOpening) {
                    if (c == '[') {
                        openingBracketsSeen++
                        if (openingBracketsSeen >= 2) skippedOpening = true
                        continue
                    }
                    if (c.isWhitespace()) continue
                    skippedOpening = true
                }

                if (depth == 0 && accumulator.isEmpty()) {
                    if (c == ',' || c.isWhitespace()) continue
                    if (c == ']') continue  // stream-end marker
                }

                accumulator.append(b)

                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val snapshot = accumulator.toByteArray()
                            accumulator.reset()
                            val parsed = try {
                                PbLite.decode<LongPollingPayload>(
                                    String(snapshot, Charsets.UTF_8),
                                    LongPollingPayload.newBuilder(),
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "pblite decode failed: ${t.message}; raw=${String(snapshot, Charsets.UTF_8).take(200)}")
                                null
                            }
                            if (parsed != null) {
                                dispatchPayload(parsed)
                                sawAnyEvent = true
                            }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "long-poll closed (totalBytes=$totalBytesRead events=$sawAnyEvent)")
        return sawAnyEvent
    }

    private suspend fun dispatchPayload(payload: LongPollingPayload) {
        when {
            payload.hasData() -> {
                Log.d(TAG, "dispatch: data (bugleRoute=${payload.data.bugleRoute})")
                handleData(payload.data)
            }
            payload.hasHeartbeat() -> Log.d(TAG, "dispatch: heartbeat")
            payload.hasAck() -> Log.d(TAG, "got startup ack count=${payload.ack.count}")
            payload.hasStartRead() -> Log.d(TAG, "got startRead marker")
            else -> Log.d(TAG, "long-poll unknown payload type")
        }
    }

    private suspend fun handleData(data: IncomingRPCMessage) {
        when (data.bugleRoute) {
            BugleRoute.PairEvent -> handlePairEvent(data)
            BugleRoute.DataEvent -> handleDataEvent(data)
            else -> Log.d(TAG, "skipping bugle route ${data.bugleRoute}")
        }
    }

    private suspend fun handlePairEvent(data: IncomingRPCMessage) {
        val pair: RPCPairData = try {
            RPCPairData.parseFrom(data.messageData)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "failed to decode RPCPairData", e)
            return
        }
        when {
            pair.hasPaired() -> {
                val p: PairedData = pair.paired
                onEvent(
                    LongPollEvent.Paired(
                        mobileDeviceB64 = Base64.encodeToString(p.mobile.toByteArray(), Base64.NO_WRAP),
                        browserDeviceB64 = Base64.encodeToString(p.browser.toByteArray(), Base64.NO_WRAP),
                        tachyonTokenB64 = Base64.encodeToString(p.tokenData.tachyonAuthToken.toByteArray(), Base64.NO_WRAP),
                        tachyonTtlUs = p.tokenData.ttl,
                    )
                )
            }
            pair.hasRevoked() -> onEvent(LongPollEvent.Revoked)
            else -> Log.d(TAG, "unknown pair event")
        }
    }

    private suspend fun handleDataEvent(data: IncomingRPCMessage) {
        val msg: RPCMessageData = try {
            RPCMessageData.parseFrom(data.messageData)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "failed to decode RPCMessageData", e)
            return
        }
        Log.d(
            TAG,
            "data event: action=${msg.action} sessionID=${msg.sessionID} encrypted=${msg.encryptedData.size()}B",
        )
        var decrypted: ByteArray? = null
        if (msg.encryptedData.size() > 0) {
            decrypted = try {
                authProvider().crypto().decrypt(msg.encryptedData.toByteArray())
            } catch (t: Throwable) {
                Log.w(TAG, "failed to decrypt data event payload: ${t.message}")
                null
            }
        }
        val incoming = IncomingRpc(
            responseId = data.responseID,
            requestId = msg.sessionID.takeIf { it.isNotEmpty() },
            action = msg.action,
            decryptedData = decrypted,
        )
        val reqId = msg.sessionID
        if (reqId.isNotEmpty()) sessionHandler.deliverResponse(reqId, incoming)
        onEvent(LongPollEvent.Data(incoming))
    }

    companion object {
        private const val TAG = "GMessages/LongPoll"
    }
}

/** Events the long-poll surfaces to the GMessagesClient. */
sealed interface LongPollEvent {
    data class Paired(
        val mobileDeviceB64: String,
        val browserDeviceB64: String,
        val tachyonTokenB64: String,
        val tachyonTtlUs: Long,
    ) : LongPollEvent

    data object Revoked : LongPollEvent

    data class Data(val msg: IncomingRpc) : LongPollEvent
}

/** Minimal mutable byte buffer to avoid string-conversion overhead. */
private class ByteArrayBuilder(initial: Int = 4096) {
    private var buf = ByteArray(initial)
    private var len = 0
    fun isEmpty(): Boolean = len == 0
    fun append(b: Byte) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b
    }
    fun toByteArray(): ByteArray = buf.copyOf(len)
    fun reset() { len = 0 }
}
