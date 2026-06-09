package com.vayunmathur.messages.telegram.mtproto.rpc

import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class RpcException(val errorCode: Int, override val message: String) : Exception("RPC error $errorCode: $message")

class RpcEngine {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<TlBuffer>>()
    private val TAG = "RpcEngine"

    suspend fun <R : TlObject> execute(
        method: TlMethod<R>,
        sendFn: suspend (ByteArray, Long) -> Long,
        decoder: (TlBuffer) -> R,
    ): R {
        val buf = TlBuffer()
        method.encode(buf)
        val deferred = CompletableDeferred<TlBuffer>()
        val msgId = sendFn(buf.raw, 0)
        pending[msgId] = deferred
        payloads[msgId] = buf.raw

        return try {
            val result = withTimeout(30_000) { deferred.await() }
            decoder(result)
        } catch (e: Exception) {
            pending.remove(msgId)
            throw e
        }
    }

    fun notifyResult(msgId: Long, buffer: TlBuffer) {
        payloads.remove(msgId)
        val deferred = pending.remove(msgId)
        if (deferred != null) {
            deferred.complete(buffer)
        } else {
            Log.w(TAG, "No pending request for msgId=$msgId")
        }
    }

    fun notifyError(msgId: Long, code: Int, message: String) {
        payloads.remove(msgId)
        val deferred = pending.remove(msgId)
        if (deferred != null) {
            deferred.completeExceptionally(RpcException(code, message))
        }
    }

    fun notifyAck(msgIds: List<Long>) {
        // ACKs don't resolve requests, just confirm receipt
    }

    fun dropAll(error: Exception) {
        val entries = pending.entries.toList()
        pending.clear()
        payloads.clear()
        for ((_, deferred) in entries) {
            deferred.completeExceptionally(error)
        }
    }

    fun hasPending(msgId: Long): Boolean = pending.containsKey(msgId)

    private val payloads = ConcurrentHashMap<Long, ByteArray>()

    fun storePayload(msgId: Long, data: ByteArray) {
        payloads[msgId] = data
    }

    fun getPendingPayload(msgId: Long): ByteArray? = payloads.remove(msgId)
}
