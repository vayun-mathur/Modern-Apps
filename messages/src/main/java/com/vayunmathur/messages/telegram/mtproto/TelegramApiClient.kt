package com.vayunmathur.messages.telegram.mtproto

import android.util.Log
import com.vayunmathur.messages.telegram.api.TlRegistry
import com.vayunmathur.messages.telegram.api.functions.AuthExportAuthorization
import com.vayunmathur.messages.telegram.api.functions.AuthImportAuthorization
import com.vayunmathur.messages.telegram.api.types.AuthExportedAuthorization
import com.vayunmathur.messages.telegram.mtproto.rpc.RpcException
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.SecureRandom

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

    var onDisconnected: (suspend () -> Unit)? = null

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

        val conn = MtProtoConnection(address, port, dc, { update ->
            _updates.emit(update)
        }, {
            onDisconnected?.invoke()
        })

        if (existingAuthKey != null && existingAuthKeyId != null && existingSalt != null) {
            val sid = existingSessionId ?: SecureRandom().nextLong()
            conn.setAuthData(existingAuthKey, existingAuthKeyId, existingSalt, sid)
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
                // Export auth from current DC before migrating
                var exportedAuth: AuthExportedAuthorization? = null
                try {
                    exportedAuth = conn.rpcEngine.execute(
                        AuthExportAuthorization(newDc),
                        { data, _ -> conn.send(data, true) },
                        { TlRegistry.decode(it) }
                    ) as? AuthExportedAuthorization
                } catch (ex: Exception) {
                    Log.w(TAG, "Auth export failed: ${ex.message}")
                }
                disconnect()
                connect(newDc)
                // Import auth into new DC
                if (exportedAuth != null) {
                    try {
                        val newConn = connection ?: throw IllegalStateException("Not connected after migration")
                        newConn.rpcEngine.execute(
                            AuthImportAuthorization(exportedAuth.id, exportedAuth.bytes),
                            { data, _ -> newConn.send(data, true) },
                            { TlRegistry.decode(it) }
                        )
                    } catch (ex: Exception) {
                        Log.w(TAG, "Auth import failed: ${ex.message}")
                    }
                }
                return invoke(method, decoder)
            }
            if (isFloodWait(e.message)) {
                val waitSeconds = extractFloodWaitDelay(e.message)
                Log.w(TAG, "FLOOD_WAIT: waiting ${waitSeconds}s")
                delay(waitSeconds * 1000L)
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
                connect(currentDc, key, keyId, s)
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

    private fun isFloodWait(msg: String): Boolean =
        msg.contains("FLOOD_WAIT_") || msg.contains("FLOOD_PREMIUM_WAIT_")

    private fun extractFloodWaitDelay(msg: String): Int {
        val regex = Regex("FLOOD_(?:PREMIUM_)?WAIT_(\\d+)")
        return regex.find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 5
    }
}
