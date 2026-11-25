package com.vayunmathur.todo.wear.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.todo.wear.data.WearTodoStore
import com.vayunmathur.todo.wear.domain.WearTodoCatalog
import com.vayunmathur.todo.wear.domain.WearTodoPage
import com.vayunmathur.todo.wear.domain.WearTodoPageRequest
import com.vayunmathur.todo.wear.domain.WearTodoProtocol
import com.vayunmathur.todo.wear.domain.WearTodoWatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Todo: catalog/page data items land in
 * [WearTodoStore], watch state goes back as a data item, pending ops and
 * page/sync requests are messages. Pending ops are (re)sent whenever a
 * session connects and whenever a new op is queued — they leave the queue
 * only when a phone page reflects them, never on send alone.
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
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                val store = WearTodoStore.get(context)
                when (path) {
                    WearTodoProtocol.CATALOG_PATH -> runCatching {
                        WearTodoProtocol.json.decodeFromString<WearTodoCatalog>(body.decodeToString())
                    }.onSuccess { store.setCatalog(it) }
                        .onFailure { Log.status(TAG, "ignoring malformed catalog") }
                    WearTodoProtocol.PAGE_PATH -> runCatching {
                        WearTodoProtocol.json.decodeFromString<WearTodoPage>(body.decodeToString())
                    }.onSuccess { store.setPage(it) }
                        .onFailure { Log.status(TAG, "ignoring malformed page") }
                }
            }
        }
        val created = WearLinkNode(context, WearTodoProtocol.CAPABILITY, listener)
        scope.launch {
            var wasConnected = false
            created.state.collect { linkState ->
                val connected = linkState is WearLinkState.Connected
                if (connected && !wasConnected) {
                    flushPending(context)
                    requestSync(context)
                    publishWatchState(context)
                }
                wasConnected = connected
            }
        }
        created.start()
        return created
    }

    /** Sends every pending op; ops stay queued until a phone page reflects them. */
    fun flushPending(context: Context) {
        val ops = WearTodoStore.get(context).pendingOps.value
        if (ops.isEmpty()) return
        scope.launch {
            val link = get(context)
            for (op in ops) {
                link.sendMessage(
                    WearTodoProtocol.OP_PATH,
                    WearTodoProtocol.json.encodeToString(op).encodeToByteArray(),
                    requireAck = true,
                )
            }
        }
    }

    fun publishWatchState(context: Context) {
        val store = WearTodoStore.get(context)
        val state = WearTodoWatchState(
            appliedCatalogAtMillis = store.appliedCatalogAtMillis,
            cachedPages = store.pages.value.size,
            pendingOps = store.pendingOps.value.size,
        )
        scope.launch {
            get(context).putData(
                WearTodoProtocol.WATCH_STATE_PATH,
                WearTodoProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh catalog + default pages; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearTodoProtocol.REQUEST_SYNC_PATH, ByteArray(0))

    /** Ask the phone for one page slice; the cached slice keeps rendering meanwhile. */
    suspend fun requestPage(context: Context, pageKey: String, offset: Int): Boolean =
        get(context).sendMessage(
            WearTodoProtocol.REQUEST_PAGE_PATH,
            WearTodoProtocol.json.encodeToString(WearTodoPageRequest(pageKey, offset))
                .encodeToByteArray(),
        )
}
