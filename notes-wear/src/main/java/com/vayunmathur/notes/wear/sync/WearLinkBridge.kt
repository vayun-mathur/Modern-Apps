package com.vayunmathur.notes.wear.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.notes.wear.data.WearNotesStore
import com.vayunmathur.notes.wear.domain.WearNoteOp
import com.vayunmathur.notes.wear.domain.WearNotesProtocol
import com.vayunmathur.notes.wear.domain.WearNotesSnapshot
import com.vayunmathur.notes.wear.domain.WearWatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Notes: snapshot data items land in
 * [WearNotesStore], watch state goes back as a data item, pending ops and
 * request-sync are messages. Replaces any Play-services listener; delivery
 * is bound to this process being alive. Pending ops are (re)sent whenever
 * a session connects and whenever a new op is queued — they leave the
 * queue only when a phone snapshot reflects them, never on send alone.
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
                if (path != WearNotesProtocol.SNAPSHOT_PATH) return
                val snapshot = runCatching {
                    WearNotesProtocol.json.decodeFromString<WearNotesSnapshot>(body.decodeToString())
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed snapshot")
                    return
                }
                WearNotesStore.get(context).setSnapshot(snapshot)
            }
        }
        val created = WearLinkNode(context, WearNotesProtocol.CAPABILITY, listener)
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

    /** Sends every pending op; ops stay queued until a snapshot reflects them. */
    fun flushPending(context: Context) {
        val store = WearNotesStore.get(context)
        val ops = store.pendingOps.value
        if (ops.isEmpty()) return
        scope.launch {
            val link = get(context)
            for (op in ops) {
                link.sendMessage(
                    WearNotesProtocol.OP_PATH,
                    WearNotesProtocol.json.encodeToString(op).encodeToByteArray(),
                    requireAck = true,
                )
            }
        }
    }

    fun publishWatchState(context: Context) {
        val store = WearNotesStore.get(context)
        val state = WearWatchState(
            appliedGeneratedAtMillis = store.appliedGeneratedAtMillis,
            noteCount = store.notes.value.size,
            pendingOps = store.pendingOps.value.size,
        )
        scope.launch {
            get(context).putData(
                WearNotesProtocol.WATCH_STATE_PATH,
                WearNotesProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh snapshot; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearNotesProtocol.REQUEST_SYNC_PATH, ByteArray(0))
}
