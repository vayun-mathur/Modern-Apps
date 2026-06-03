package com.vayunmathur.messages.telegram.mtproto

import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.rpc.RpcException
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TelegramApiClient {
    private val TAG = "TelegramApiClient"

    private var connection: MtProtoConnection? = null
    private var currentDc: Int = 2
    val dc: Int get() = currentDc

    private val _updates = MutableSharedFlow<TlObject>(extraBufferCapacity = 256)
    val updates: SharedFlow<TlObject> = _updates.asSharedFlow()

    var authKey: ByteArray = ByteArray(0)
        private set
    var authKeyId: ByteArray = ByteArray(0)
        private set
    var salt: Long = 0L
        private set
    var sessionId: Long = 0L
        private set
    val isConnected: Boolean get() = connection?.connected == true

    companion object {
        val DC_ADDRESSES = mapOf(
            1 to ("149.154.175.53" to 443),
            2 to ("149.154.167.51" to 443),
            3 to ("149.154.175.100" to 443),
            4 to ("149.154.167.91" to 443),
            5 to ("91.108.56.130" to 443),
        )
    }

    suspend fun connect(dc: Int = 2, existingAuthKey: ByteArray? = null, existingAuthKeyId: ByteArray? = null, existingSalt: Long? = null, existingSessionId: Long? = null) {
        currentDc = dc
        val (address, port) = DC_ADDRESSES[dc] ?: throw IllegalArgumentException("Unknown DC: $dc")

        val conn = MtProtoConnection(address, port, dc) { update ->
            _updates.emit(update)
        }

        if (existingAuthKey != null && existingAuthKeyId != null && existingSalt != null && existingSessionId != null) {
            conn.setAuthData(existingAuthKey, existingAuthKeyId, existingSalt, existingSessionId)
        }

        conn.connect()
        connection = conn
        authKey = conn.authKey
        authKeyId = conn.authKeyId
        salt = conn.salt
        sessionId = conn.sessionId
    }

    suspend fun <R : TlObject> invoke(method: TlMethod<R>, decoder: (TlBuffer) -> R): R {
        val conn = connection ?: throw IllegalStateException("Not connected")
        return try {
            conn.rpcEngine.execute(method, { data, _ -> conn.send(data, true) }, decoder)
        } catch (e: RpcException) {
            if (isMigrateError(e.message)) {
                val newDc = extractMigrateDc(e.message)
                Log.i(TAG, "Migrating to DC $newDc")
                disconnect()
                connect(newDc)
                return invoke(method, decoder)
            }
            throw e
        }
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    suspend fun reconnect() {
        val key = authKey.copyOf()
        val keyId = authKeyId.copyOf()
        val s = salt
        disconnect()
        var backoff = 1000L
        for (attempt in 1..10) {
            try {
                connect(currentDc, key, keyId, s, null)
                Log.i(TAG, "Reconnected on attempt $attempt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
        throw IllegalStateException("Failed to reconnect after 10 attempts")
    }

    private fun isMigrateError(msg: String): Boolean =
        msg.contains("_MIGRATE_")

    private fun extractMigrateDc(msg: String): Int {
        val regex = Regex("_(\\d+)$")
        return regex.find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: currentDc
    }
}
