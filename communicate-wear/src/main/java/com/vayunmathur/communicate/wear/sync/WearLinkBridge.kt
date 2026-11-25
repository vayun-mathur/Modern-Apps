package com.vayunmathur.communicate.wear.sync

import android.content.Context
import com.vayunmathur.communicate.wear.data.WearCommunicateStore
import com.vayunmathur.communicate.wear.domain.WearCommWatchState
import com.vayunmathur.communicate.wear.domain.WearCommunicateProtocol
import com.vayunmathur.communicate.wear.domain.WearCommunicateSnapshot
import com.vayunmathur.communicate.wear.domain.WearIncomingCall
import com.vayunmathur.communicate.wear.domain.WearNewMessageAlert
import com.vayunmathur.communicate.wear.domain.WearOpResult
import com.vayunmathur.communicate.wear.domain.WearThreadPage
import com.vayunmathur.communicate.wear.domain.WearThreadRequest
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Communicate: snapshot data items and thread
 * pages land in [WearCommunicateStore], alerts surface as store state,
 * pending ops and page/sync requests are messages. Delivery is bound to
 * this process being alive; pending ops resend on reconnect and leave
 * only on a phone success verdict.
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val store = WearCommunicateStore.get(context)
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                if (path != WearCommunicateProtocol.SNAPSHOT_PATH) return
                val snapshot = runCatching {
                    WearCommunicateProtocol.json.decodeFromString<WearCommunicateSnapshot>(
                        body.decodeToString(),
                    )
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed snapshot")
                    return
                }
                store.setSnapshot(snapshot)
            }

            override fun onMessage(path: String, body: ByteArray) {
                val text = body.decodeToString()
                when (path) {
                    WearCommunicateProtocol.THREAD_PAGE_PATH -> runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearThreadPage>(text)
                    }.getOrNull()?.let { store.setThreadPage(it) }
                    WearCommunicateProtocol.NEW_MESSAGE_PATH -> runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearNewMessageAlert>(text)
                    }.getOrNull()?.let { store.setAlert(it) }
                    WearCommunicateProtocol.INCOMING_CALL_PATH -> runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearIncomingCall>(text)
                    }.getOrNull()?.let { store.setIncomingCall(it) }
                    WearCommunicateProtocol.OP_RESULT_PATH -> runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearOpResult>(text)
                    }.getOrNull()?.let { store.applyOpResult(it) }
                }
            }
        }
        val created = WearLinkNode(context, WearCommunicateProtocol.CAPABILITY, listener)
        scope.launch {
            var wasConnected = false
            created.state.collect { linkState ->
                val connected = linkState is WearLinkState.Connected
                if (connected && !wasConnected) {
                    flushPending(context)
                    publishWatchState(context)
                }
                wasConnected = connected
            }
        }
        created.start()
        return created
    }

    /** Sends every pending op; ops stay queued until the phone verdict. */
    fun flushPending(context: Context) {
        val ops = WearCommunicateStore.get(context).pendingOps.value
        if (ops.isEmpty()) return
        scope.launch {
            val link = get(context)
            for (op in ops) {
                link.sendMessage(
                    WearCommunicateProtocol.OP_PATH,
                    WearCommunicateProtocol.json.encodeToString(op).encodeToByteArray(),
                    requireAck = true,
                )
            }
        }
    }

    fun publishWatchState(context: Context) {
        val store = WearCommunicateStore.get(context)
        val state = WearCommWatchState(
            appliedGeneratedAtMillis = store.appliedGeneratedAtMillis,
            threadCount = store.snapshot.value.threads.size,
            pendingOps = store.pendingOps.value.size,
        )
        scope.launch {
            get(context).putData(
                WearCommunicateProtocol.WATCH_STATE_PATH,
                WearCommunicateProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh snapshot; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearCommunicateProtocol.REQUEST_SYNC_PATH, ByteArray(0))

    /** Ask the phone for one thread page (latest when [beforeTimestampMillis] is 0). */
    suspend fun requestThreadPage(
        context: Context,
        threadKey: String,
        beforeTimestampMillis: Long = 0L,
    ): Boolean = get(context).sendMessage(
        WearCommunicateProtocol.REQUEST_THREAD_PATH,
        WearCommunicateProtocol.json.encodeToString(
            WearThreadRequest(threadKey, beforeTimestampMillis),
        ).encodeToByteArray(),
    )
}
