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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class MtProtoConnection(
    private val address: String,
    private val port: Int,
    private val dc: Int,
    private val onUpdate: suspend (TlObject) -> Unit,
) {
    private val TAG = "MtProtoConn"
    private val transport = TcpTransport()
    val rpcEngine = RpcEngine()

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
    private val pendingAcks = mutableListOf<Long>()
    private var scope: CoroutineScope? = null
    @Volatile var connected = false
        private set

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
        }
        connected = true
        seqNo.set(0)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { readLoop() }
        s.launch { pingLoop() }
        s.launch { ackLoop() }
    }

    suspend fun send(payload: ByteArray, contentRelated: Boolean): Long {
        val msgId = MessageId.generate()
        val sn = nextSeqNo(contentRelated)
        val encrypted = MtProtoCipher.encrypt(authKey, salt, sessionId, msgId, sn, payload)
        writeMutex.withLock {
            transport.send(encrypted)
        }
        return msgId
    }

    fun disconnect() {
        connected = false
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
                    handleDecrypted(decrypted.data, decrypted.messageId)
                } catch (e: Exception) {
                    Log.w(TAG, "Decrypt/handle error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "Read loop error: ${e.message}")
                connected = false
                rpcEngine.dropAll(e)
            }
        }
    }

    private suspend fun handleDecrypted(data: ByteArray, msgId: Long) {
        val buf = TlBuffer(data)
        if (buf.remaining < 4) return
        val typeId = buf.peekId()

        when (typeId) {
            MessageFraming.TYPE_MSG_CONTAINER -> {
                buf.int32() // consume type id
                val messages = MessageFraming.parseContainer(buf)
                for (msg in messages) {
                    handleDecrypted(msg.data, msg.msgId)
                }
            }
            MessageFraming.TYPE_RPC_RESULT -> {
                buf.int32() // consume type id
                val (reqMsgId, resultBuf) = MessageFraming.parseRpcResult(buf)
                synchronized(pendingAcks) { pendingAcks.add(msgId) }
                if (resultBuf.remaining >= 4) {
                    val innerType = resultBuf.peekId()
                    if (innerType == MessageFraming.TYPE_GZIP_PACKED) {
                        resultBuf.int32()
                        val decompressed = MessageFraming.gunzipPacked(resultBuf)
                        rpcEngine.notifyResult(reqMsgId, TlBuffer(decompressed))
                    } else if (innerType == MessageFraming.TYPE_RPC_ERROR) {
                        resultBuf.int32()
                        val err = MessageFraming.parseRpcError(resultBuf)
                        rpcEngine.notifyError(reqMsgId, err.errorCode, err.errorMessage)
                    } else {
                        rpcEngine.notifyResult(reqMsgId, resultBuf)
                    }
                }
            }
            MessageFraming.TYPE_GZIP_PACKED -> {
                buf.int32()
                val decompressed = MessageFraming.gunzipPacked(buf)
                handleDecrypted(decompressed, msgId)
            }
            MessageFraming.TYPE_PONG -> {
                buf.int32()
                MessageFraming.parsePong(buf)
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
                Log.d(TAG, "Updated server salt")
                // Re-send the failed message is handled at RPC level
            }
            MessageFraming.TYPE_NEW_SESSION -> {
                buf.int32()
                val ns = MessageFraming.parseNewSession(buf)
                salt = ns.serverSalt
                synchronized(pendingAcks) { pendingAcks.add(msgId) }
            }
            else -> {
                synchronized(pendingAcks) { pendingAcks.add(msgId) }
                try {
                    val obj = TlRegistry.decode(buf)
                    onUpdate(obj)
                } catch (e: Exception) {
                    Log.w(TAG, "Unknown type: 0x${typeId.toUInt().toString(16)}")
                }
            }
        }
    }

    private suspend fun pingLoop() {
        while (connected) {
            delay(60_000)
            if (!connected) break
            try {
                val ping = MessageFraming.writePingDelayDisconnect(System.nanoTime(), 75)
                send(ping, false)
            } catch (e: Exception) {
                Log.w(TAG, "Ping failed: ${e.message}")
            }
        }
    }

    private suspend fun ackLoop() {
        while (connected) {
            delay(15_000)
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
    }

    private fun nextSeqNo(contentRelated: Boolean): Int {
        return if (contentRelated) {
            val s = seqNo.getAndIncrement()
            s * 2 + 1
        } else {
            seqNo.get() * 2
        }
    }

    private suspend fun handleDecrypted(data: com.vayunmathur.messages.telegram.mtproto.crypto.DecryptedMessage) {
        handleDecrypted(data.data, data.messageId)
    }
}
