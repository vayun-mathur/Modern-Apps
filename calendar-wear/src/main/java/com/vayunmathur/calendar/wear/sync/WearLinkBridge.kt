package com.vayunmathur.calendar.wear.sync

import android.content.Context
import com.vayunmathur.calendar.wear.data.WearCalendarStore
import com.vayunmathur.calendar.wear.domain.WearCalendarProtocol
import com.vayunmathur.calendar.wear.domain.WearCalendarSnapshot
import com.vayunmathur.calendar.wear.domain.WearWatchState
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Calendar: snapshot data items land in
 * [WearCalendarStore], watch state goes back as a data item, and
 * request-sync is a message. Replaces the Play-services listener service;
 * delivery is bound to this process being alive.
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
                if (path != WearCalendarProtocol.SNAPSHOT_PATH) return
                val snapshot = runCatching {
                    WearCalendarProtocol.json.decodeFromString<WearCalendarSnapshot>(
                        body.decodeToString(),
                    )
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed snapshot")
                    return
                }
                WearCalendarStore.get(context).setSnapshot(snapshot)
            }
        }
        return WearLinkNode(context, WearCalendarProtocol.CAPABILITY, listener).also { it.start() }
    }

    fun publishWatchState(context: Context, snapshot: WearCalendarSnapshot) {
        val state = WearWatchState(
            appliedGeneratedAtMillis = snapshot.generatedAtMillis,
            eventCount = snapshot.events.size,
        )
        scope.launch {
            get(context).putData(
                WearCalendarProtocol.WATCH_STATE_PATH,
                WearCalendarProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh snapshot; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearCalendarProtocol.REQUEST_SYNC_PATH, ByteArray(0))
}
