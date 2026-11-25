package com.vayunmathur.findfamily.wear.sync

import android.content.Context
import com.vayunmathur.findfamily.wear.data.WearFindFamilyStore
import com.vayunmathur.findfamily.wear.domain.WearFindFamilyOp
import com.vayunmathur.findfamily.wear.domain.WearFindFamilyProtocol
import com.vayunmathur.findfamily.wear.domain.WearFindFamilySnapshot
import com.vayunmathur.findfamily.wear.domain.WearOpKind
import com.vayunmathur.findfamily.wear.domain.WearWatchState
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
 * Watch side of WearLink for FindFamily: snapshot data items land in
 * [WearFindFamilyStore], watch state goes back as a data item, and
 * stop-sharing / refresh / request-sync are messages to the phone (the
 * source of truth — the watch never claims a phone change locally).
 * Delivery is bound to this process being alive.
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
        val store = WearFindFamilyStore.get(context)
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                if (path != WearFindFamilyProtocol.SNAPSHOT_PATH) return
                val snapshot = runCatching {
                    WearFindFamilyProtocol.json.decodeFromString<WearFindFamilySnapshot>(body.decodeToString())
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed snapshot")
                    return
                }
                store.setSnapshot(snapshot)
            }
        }
        val created = WearLinkNode(context, WearFindFamilyProtocol.CAPABILITY, listener)
        scope.launch {
            created.state.collect { linkState ->
                store.setConnected(linkState is WearLinkState.Connected)
            }
        }
        created.start()
        return created
    }

    fun publishWatchState(context: Context) {
        val snapshot = WearFindFamilyStore.get(context).snapshot.value
        val state = WearWatchState(
            appliedGeneratedAtMillis = snapshot.generatedAtMillis,
            peopleCount = snapshot.people.size,
        )
        scope.launch {
            get(context).putData(
                WearFindFamilyProtocol.WATCH_STATE_PATH,
                WearFindFamilyProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh snapshot; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearFindFamilyProtocol.REQUEST_SYNC_PATH, ByteArray(0))

    /** Stop sharing now on the phone (same switch as its Quick Settings tile). */
    suspend fun stopSharingNow(context: Context): Boolean = sendOp(context, WearOpKind.STOP_SHARING_NOW)

    /** Ask the phone to publish fresh locations (its freshest state + service reconcile). */
    suspend fun requestRefresh(context: Context): Boolean = sendOp(context, WearOpKind.REQUEST_REFRESH)

    private suspend fun sendOp(context: Context, kind: WearOpKind): Boolean =
        get(context).sendMessage(
            WearFindFamilyProtocol.OP_PATH,
            WearFindFamilyProtocol.json.encodeToString(
                WearFindFamilyOp(kind, System.currentTimeMillis()),
            ).encodeToByteArray(),
            requireAck = true,
        )
}
