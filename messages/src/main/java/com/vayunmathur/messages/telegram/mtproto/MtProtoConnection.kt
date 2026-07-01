package com.vayunmathur.messages.telegram.mtproto

import android.util.Log
import com.vayunmathur.messages.telegram.api.TlRegistry
import com.vayunmathur.messages.telegram.mtproto.crypto.AuthResult
import com.vayunmathur.messages.telegram.mtproto.crypto.KeyExchange
import com.vayunmathur.messages.telegram.mtproto.crypto.MtProtoCipher
import com.vayunmathur.messages.telegram.mtproto.proto.MessageFraming
import com.vayunmathur.messages.telegram.mtproto.proto.MessageId
import com.vayunmathur.messages.telegram.mtproto.rpc.RpcEngine
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import com.vayunmathur.messages.telegram.mtproto.transport.TcpTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

class MtProtoConnection(
    private val address: String,
    private val port: Int,
    private val dc: Int,
    private val onUpdate: suspend (TlObject) -> Unit,
    private val onDisconnected: (suspend () -> Unit)? = null,
) {
    private val TAG = "MtProtoConn"
    private val transport = TcpTransport()
    val rpcEngine = RpcEngine()
    private val secureRandom = SecureRandom()

    var authKey: ByteArray = ByteArray(0)
        private set
    var authKeyId: ByteArray = ByteArray(0)
        private set
    var salt: Long = 0L
        private set
    var sessionId: Long = 0L
        private set

    private val seqNo = AtomicInteger(0)
    private val writeMutex = Mutex()
    private val sendMutex = Mutex()
    private val pendingAcks = mutableListOf<Long>()
    private var scope: CoroutineScope? = null
    @Volatile var connected = false
        private set

    // Outgoing batching: content-related sends are funnelled through this channel and the
    // batchSendLoop coalesces any that arrive within a short debounce window into a single
    // msg_container, cutting the number of encrypted transport packets under bursty load.
    private data class OutgoingItem(
        val payload: ByteArray,
        val contentRelated: Boolean,
        val result: CompletableDeferred<Long>,
    )
    private val outgoingQueue = kotlinx.coroutines.channels.Channel<OutgoingItem>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    private val futureSalts = mutableListOf<MessageFraming.FutureSalt>()
    private val pongDeferreds = mutableMapOf<Long, CompletableDeferred<Unit>>()

    private companion object {
        const val ACK_BATCH_SIZE = 20
        const val SEND_BATCH_DEBOUNCE_MS = 5L
        const val SEND_BATCH_MAX = 16
    }

    fun setAuthData(authKey: ByteArray, authKeyId: ByteArray, salt: Long, sessionId: Long) {
        this.authKey = authKey
        this.authKeyId = authKeyId
        this.salt = salt
        this.sessionId = sessionId
    }

    suspend fun connect() {
        transport.connect(address, port)
        if (authKey.isEmpty()) {
            val exchange = KeyExchange(transport, dc)
            val result = exchange.perform()
            authKey = result.authKey
            authKeyId = result.authKeyId
            salt = result.serverSalt
            sessionId = result.sessionId
            // Learn the server-clock offset straight from the handshake so the FIRST
            // authenticated request already uses server-based msg_ids (and incoming
            // msg_id validation uses the right clock). Stored globally in MessageId so
            // generation and validation share one value. (On reconnect with a persisted
            // auth key this block is skipped — the readLoop then syncs from the first
            // inbound server message instead.)
            val handshakeOffset = result.serverTime.toLong() - (System.currentTimeMillis() / 1000)
            MessageId.setTimeOffsetSeconds(handshakeOffset)
            MessageId.reset()
            Log.d(TAG, "Server time offset from handshake: ${handshakeOffset}s (applied to msg_id gen + validation)")
        }
        connected = true
        seqNo.set(0)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { readLoop() }
        s.launch { pingLoop() }
        s.launch { ackLoop() }
        s.launch { batchSendLoop() }
    }

    suspend fun send(payload: ByteArray, contentRelated: Boolean): Long {
        sendMutex.withLock {
            val msgId = MessageId.generate()
            val sn = nextSeqNo(contentRelated)
            val encrypted = MtProtoCipher.encrypt(authKey, salt, sessionId, msgId, sn, payload)
            writeMutex.withLock {
                transport.send(encrypted)
            }
            return msgId
        }
    }

    // Enqueues a content message for batched sending and suspends until it has been assigned an
    // inner msg_id and written (possibly inside a msg_container). Returns the inner msg_id so the
    // RpcEngine can track the pending request exactly as with a direct send. // UNVERIFIED runtime
    suspend fun sendBatched(payload: ByteArray, contentRelated: Boolean): Long {
        if (!connected) return send(payload, contentRelated)
        val item = OutgoingItem(payload, contentRelated, CompletableDeferred())
        outgoingQueue.send(item)
        return item.result.await()
    }

    // Drains the outgoing queue, coalescing a debounced burst of messages into one msg_container
    // (single-item bursts are sent directly). Pending acks are piggybacked into the same packet.
    private suspend fun batchSendLoop() {
        try {
            while (connected) {
                val first = outgoingQueue.receive()
                val batch = mutableListOf(first)
                try {
                    if (SEND_BATCH_DEBOUNCE_MS > 0) delay(SEND_BATCH_DEBOUNCE_MS)
                    while (batch.size < SEND_BATCH_MAX) {
                        val next = outgoingQueue.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }
                    val acks = drainPendingAcksForContainer()
                    if (batch.size == 1 && acks.isEmpty()) {
                        val only = batch[0]
                        only.result.complete(send(only.payload, only.contentRelated))
                    } else {
                        sendContainer(batch, acks)
                    }
                } catch (e: Throwable) {
                    // Fail the whole in-flight batch so their callers don't hang (covers send
                    // errors and cancellation mid-debounce on disconnect).
                    batch.forEach { it.result.completeExceptionally(e) }
                    if (e is CancellationException) throw e
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // queue closed on disconnect
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "batchSendLoop error: ${e.message}")
        }
    }

    private fun drainPendingAcksForContainer(): List<Long> {
        synchronized(pendingAcks) {
            if (pendingAcks.isEmpty()) return emptyList()
            val ids = pendingAcks.toList()
            pendingAcks.clear()
            return ids
        }
    }

    // Builds one msg_container holding the batched content messages (each with its own msg_id /
    // seqno) plus an optional piggybacked msgs_ack, encrypts it under a single outer msg_id, and
    // writes it. Inner msg_ids are reported back through each item's deferred.
    private suspend fun sendContainer(batch: List<OutgoingItem>, acks: List<Long>) {
        sendMutex.withLock {
            val inner = mutableListOf<MessageFraming.InnerMessage>()
            val assigned = ArrayList<Pair<OutgoingItem, Long>>(batch.size)
            for (item in batch) {
                val msgId = MessageId.generate()
                val sn = nextSeqNo(item.contentRelated)
                inner.add(MessageFraming.InnerMessage(msgId, sn, item.payload))
                assigned.add(item to msgId)
            }
            if (acks.isNotEmpty()) {
                val ackMsgId = MessageId.generate()
                val ackSn = nextSeqNo(false)
                inner.add(MessageFraming.InnerMessage(ackMsgId, ackSn, MessageFraming.writeMsgsAck(acks)))
            }
            val containerBody = MessageFraming.writeContainer(inner)
            val outerMsgId = MessageId.generate()
            val outerSn = nextSeqNo(false)
            val encrypted = MtProtoCipher.encrypt(authKey, salt, sessionId, outerMsgId, outerSn, containerBody)
            writeMutex.withLock {
                transport.send(encrypted)
            }
            for ((item, msgId) in assigned) item.result.complete(msgId)
        }
    }

    fun disconnect() {
        connected = false
        // Fail any batched sends still waiting so their callers don't hang past disconnect.
        while (true) {
            val item = outgoingQueue.tryReceive().getOrNull() ?: break
            item.result.completeExceptionally(CancellationException("Disconnected"))
        }
        scope?.cancel()
        scope = null
        rpcEngine.dropAll(CancellationException("Disconnected"))
        transport.close()
    }

    private suspend fun readLoop() {
        try {
            while (connected) {
                val raw = transport.receive()
                if (raw.size < 24) continue
                try {
                    val decrypted = MtProtoCipher.decrypt(authKey, raw)
                    if (decrypted.sessionId != sessionId) {
                        Log.w(TAG, "Session ID mismatch, rejecting message")
                        continue
                    }
                    val serverMsgId = decrypted.messageId
                    // Reject only genuinely malformed ids (wrong type bits) and duplicates.
                    // We do NOT time-window-reject inbound messages, and we do NOT derive
                    // the time offset from arbitrary inbound ids: a msg_container / msgs_ack /
                    // service message can carry a msg_id near the LOCAL clock, which would
                    // (a) get wrongly rejected against our offset, or (b) clobber the correct
                    // offset back to ~0. The offset is sourced ONLY from authoritative
                    // server-time sources — the handshake and new_session_created (and
                    // bad_msg 16/17) — matching gotd/tdesktop. Replay is prevented by
                    // consume(); authenticity by the auth key + session id.
                    if (!MessageId.isServerType(serverMsgId)) {
                        Log.w(TAG, "Rejecting non-server msg_id 0x${serverMsgId.toULong().toString(16)}")
                        continue
                    }
                    if (!MessageId.consume(serverMsgId)) {
                        Log.w(TAG, "Duplicate message ID detected, rejecting")
                        continue
                    }
                    handleDecrypted(decrypted.data, decrypted.messageId, decrypted.seqNo)
                } catch (e: Exception) {
                    Log.w(TAG, "Decrypt/handle error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "Read loop error: ${e.message}")
                connected = false
                rpcEngine.dropAll(e)
                onDisconnected?.let { scope?.launch { it() } }
            }
        }
    }

    private suspend fun handleDecrypted(data: ByteArray, msgId: Long, seqNo: Int, fromContainer: Boolean = false) {
        val buf = TlBuffer(data)
        if (buf.remaining < 4) return
        val typeId = buf.peekId()

        when (typeId) {
            MessageFraming.TYPE_MSG_CONTAINER -> {
                buf.int32() // consume type id
                val messages = MessageFraming.parseContainer(buf)
                for (msg in messages) {
                    handleDecrypted(msg.data, msg.msgId, msg.seqNo, fromContainer = true)
                }
            }
            MessageFraming.TYPE_RPC_RESULT -> {
                buf.int32() // consume type id
                val (reqMsgId, resultBuf) = MessageFraming.parseRpcResult(buf)
                if (resultBuf.remaining >= 4) {
                    var innerType = resultBuf.peekId()
                    val effectiveBuf = if (innerType == MessageFraming.TYPE_GZIP_PACKED) {
                        resultBuf.int32()
                        val decompressed = MessageFraming.gunzipPacked(resultBuf)
                        val decompBuf = TlBuffer(decompressed)
                        if (decompBuf.remaining >= 4) {
                            innerType = decompBuf.peekId()
                        }
                        decompBuf
                    } else {
                        resultBuf
                    }
                    if (innerType == MessageFraming.TYPE_RPC_ERROR) {
                        effectiveBuf.int32()
                        val err = MessageFraming.parseRpcError(effectiveBuf)
                        rpcEngine.notifyError(reqMsgId, err.errorCode, err.errorMessage)
                    } else if (innerType == MessageFraming.TYPE_PONG) {
                        handlePong(effectiveBuf)
                    } else {
                        rpcEngine.notifyResult(reqMsgId, effectiveBuf)
                    }
                }
            }
            MessageFraming.TYPE_GZIP_PACKED -> {
                buf.int32()
                val decompressed = MessageFraming.gunzipPacked(buf)
                handleDecrypted(decompressed, msgId, seqNo)
            }
            MessageFraming.TYPE_PONG -> {
                handlePong(buf)
            }
            MessageFraming.TYPE_MSGS_ACK -> {
                buf.int32()
                val acked = MessageFraming.parseMsgsAck(buf)
                rpcEngine.notifyAck(acked)
            }
            MessageFraming.TYPE_BAD_SERVER_SALT -> {
                buf.int32()
                val bss = MessageFraming.parseBadServerSalt(buf)
                salt = bss.newSalt
                Log.d(TAG, "Updated server salt, re-sending msgId=${bss.badMsgId}")
                rpcEngine.getPendingPayload(bss.badMsgId)?.let { payload ->
                    try {
                        val newMsgId = send(payload, true)
                        rpcEngine.migratePending(bss.badMsgId, newMsgId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Re-send after salt update failed: ${e.message}")
                    }
                }
            }
            MessageFraming.TYPE_BAD_MSG_NOTIFICATION -> {
                buf.int32()
                val notification = MessageFraming.parseBadMsgNotification(buf)
                // Codes 16/17/20 are clock-related (msg_id too low/high, msg too old).
                // Resync the server time offset and transparently resend the request
                // rather than failing it. Ref gotd mtproto bad-msg handling.
                val code = notification.errorCode
                // 16/17 = msg_id too low/high, 20 = msg too old → our clock is skewed.
                val isTimeError = code == 16 || code == 17 || code == 20
                // 32/33/34/35 = bad msg_seqno (too low/high / even-odd mismatch).
                val isSeqError = code == 32 || code == 33 || code == 34 || code == 35
                if (isTimeError) {
                    // Always resync the offset from this server message's msg_id (the
                    // clock may have drifted since the handshake) and regenerate ids.
                    val newOffset = MessageId.timeSeconds(msgId) - (System.currentTimeMillis() / 1000)
                    MessageId.setTimeOffsetSeconds(newOffset)
                    MessageId.reset()
                    updateSalt()
                    Log.d(TAG, "Time resynced: offset=${newOffset}s (bad_msg $code)")
                    resendPending(notification.badMsgId, code)
                } else if (isSeqError) {
                    // Resend with a fresh msg_id + the next seqno (send() assigns both),
                    // instead of blind-retrying the same bad seqno to the retry limit.
                    Log.d(TAG, "Seqno error bad_msg $code, resending badMsgId=${notification.badMsgId}")
                    resendPending(notification.badMsgId, code)
                } else {
                    rpcEngine.notifyError(notification.badMsgId, code,
                        "bad_msg_notification error code $code")
                }
            }
            MessageFraming.TYPE_FUTURE_SALTS -> {
                buf.int32()
                val fs = MessageFraming.parseFutureSalts(buf)
                synchronized(futureSalts) {
                    futureSalts.clear()
                    futureSalts.addAll(fs.salts)
                }
                Log.d(TAG, "Stored ${fs.salts.size} future salts")
            }
            MessageFraming.TYPE_MSG_DETAILED_INFO,
            MessageFraming.TYPE_MSG_NEW_DETAILED_INFO -> {
                buf.int32()
                buf.data() // silently ignore
            }
            MessageFraming.TYPE_NEW_SESSION -> {
                buf.int32()
                val ns = MessageFraming.parseNewSession(buf)
                salt = ns.serverSalt
                // new_session_created carries an authoritative server msg_id — refresh
                // the (global) time offset used for generation + validation.
                val nsOffset = MessageId.timeSeconds(ns.firstMsgId) - (System.currentTimeMillis() / 1000)
                MessageId.setTimeOffsetSeconds(nsOffset)
                Log.d(TAG, "Server time offset from new session: ${nsOffset}s")
            }
            else -> {
                try {
                    val obj = TlRegistry.decode(buf)
                    onUpdate(obj)
                } catch (e: Exception) {
                    Log.w(TAG, "Unknown type: 0x${typeId.toUInt().toString(16)}")
                }
            }
        }

        if (!fromContainer && MessageFraming.needsAck(seqNo)) {
            synchronized(pendingAcks) { pendingAcks.add(msgId) }
            checkAckBatchFlush()
        }
    }

    private fun handlePong(buf: TlBuffer) {
        buf.int32()
        val pong = MessageFraming.parsePong(buf)
        synchronized(pongDeferreds) {
            pongDeferreds.remove(pong.pingId)?.complete(Unit)
        }
    }

    private suspend fun pingLoop() {
        while (connected) {
            delay(60_000)
            if (!connected) break
            try {
                val pingId = secureRandom.nextLong()
                val deferred = CompletableDeferred<Unit>()
                synchronized(pongDeferreds) { pongDeferreds[pingId] = deferred }
                val ping = MessageFraming.writePingDelayDisconnect(pingId, 75)
                send(ping, false)
                try {
                    withTimeout(15_000) { deferred.await() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "Pong timeout, disconnecting")
                    disconnect()
                    onDisconnected?.invoke()
                    return
                } finally {
                    synchronized(pongDeferreds) { pongDeferreds.remove(pingId) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping failed: ${e.message}")
            }
        }
    }

    private suspend fun ackLoop() {
        while (connected) {
            delay(15_000)
            flushAcks()
        }
    }

    private suspend fun checkAckBatchFlush() {
        val shouldFlush = synchronized(pendingAcks) { pendingAcks.size >= ACK_BATCH_SIZE }
        if (shouldFlush) flushAcks()
    }

    private suspend fun flushAcks() {
        val ids: List<Long>
        synchronized(pendingAcks) {
            ids = pendingAcks.toList()
            pendingAcks.clear()
        }
        if (ids.isNotEmpty()) {
            try {
                val ack = MessageFraming.writeMsgsAck(ids)
                send(ack, false)
            } catch (e: Exception) {
                Log.w(TAG, "ACK send failed: ${e.message}")
            }
        }
    }

    private fun nextSeqNo(contentRelated: Boolean): Int {
        return if (contentRelated) {
            val s = seqNo.getAndIncrement()
            s * 2 + 1
        } else {
            seqNo.get() * 2
        }
    }

    private fun updateSalt() {
        val now = MessageId.serverNowSeconds()
        synchronized(futureSalts) {
            val valid = futureSalts.firstOrNull { it.validSince <= now && now < it.validUntil }
            if (valid != null) {
                salt = valid.salt
                Log.d(TAG, "Salt updated from future salts")
            }
        }
    }

    // Re-send the payload of a message the server rejected with a recoverable
    // bad_msg_notification, using a fresh msg_id + seqno (and current salt/offset).
    private suspend fun resendPending(badMsgId: Long, code: Int) {
        val payload = rpcEngine.getPendingPayload(badMsgId)
        if (payload == null) {
            rpcEngine.notifyError(badMsgId, code, "bad_msg_notification error code $code")
            return
        }
        try {
            val newMsgId = send(payload, true)
            rpcEngine.migratePending(badMsgId, newMsgId)
        } catch (e: Exception) {
            Log.w(TAG, "Re-send after bad_msg $code failed: ${e.message}")
            rpcEngine.notifyError(badMsgId, code, "bad_msg_notification error code $code")
        }
    }

    private suspend fun handleDecrypted(data: com.vayunmathur.messages.telegram.mtproto.crypto.DecryptedMessage) {
        handleDecrypted(data.data, data.messageId, data.seqNo)
    }
}
