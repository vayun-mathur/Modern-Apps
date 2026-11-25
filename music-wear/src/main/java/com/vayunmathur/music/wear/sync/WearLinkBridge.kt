package com.vayunmathur.music.wear.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearMusicProtocol
import com.vayunmathur.music.wear.domain.WearPageRequest
import com.vayunmathur.music.wear.domain.WearPageSnapshot
import com.vayunmathur.music.wear.domain.WearPlaybackState
import com.vayunmathur.music.wear.domain.WearSearchRequest
import com.vayunmathur.music.wear.domain.WearWatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Music: page snapshots + playback state land in
 * [WearMusicStore]; page/search requests and playback ops are messages to
 * the phone. Delivery is bound to this process being alive; pending ops are
 * resent whenever a session connects.
 */
object WearLinkBridge {
    private const val TAG = "WearMusicBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    /** The process-wide WearLink node for the music companion, started on first use. */
    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) { node ?: create(appContext).also { node = it } }
    }

    private fun create(context: Context): WearLinkNode {
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                val text = body.decodeToString()
                when (path) {
                    WearMusicProtocol.PAGE_PATH -> runCatching {
                        WearMusicProtocol.json.decodeFromString<WearPageSnapshot>(text)
                    }.onSuccess { WearMusicStore.get(context).setPage(it) }
                        .onFailure { Log.status(TAG, "ignoring malformed page: ${it.message}") }
                    WearMusicProtocol.PLAYBACK_PATH -> runCatching {
                        WearMusicProtocol.json.decodeFromString<WearPlaybackState>(text)
                    }.onSuccess { WearMusicStore.get(context).setPlayback(it) }
                        .onFailure { Log.status(TAG, "ignoring malformed playback: ${it.message}") }
                }
            }
        }
        val created = WearLinkNode(context, WearMusicProtocol.CAPABILITY, listener)
        scope.launch {
            var wasConnected = false
            created.state.collect { linkState ->
                val connected = linkState is WearLinkState.Connected
                if (connected && !wasConnected) flushPending(context)
                wasConnected = connected
            }
        }
        created.start()
        return created
    }

    /** Ask the phone for one bounded page (first page when [WearPageRequest.cursor] is empty). */
    fun requestPage(context: Context, request: WearPageRequest) {
        scope.launch {
            get(context).sendMessage(
                WearMusicProtocol.PAGE_REQUEST_PATH,
                WearMusicProtocol.json.encodeToString(request).encodeToByteArray(),
            )
        }
    }

    /** Send a search query (typed or dictated) to the phone; results return as a SEARCH page. */
    fun requestSearch(context: Context, query: String, cursor: String = "") {
        scope.launch {
            get(context).sendMessage(
                WearMusicProtocol.SEARCH_REQUEST_PATH,
                WearMusicProtocol.json.encodeToString(WearSearchRequest(query, cursor)).encodeToByteArray(),
            )
        }
    }

    /** Send one op to the phone's playback service; the op also stays queued in the store until reflected. */
    fun sendOp(context: Context, op: WearMusicOp) {
        scope.launch {
            get(context).sendMessage(
                WearMusicProtocol.OP_PATH,
                WearMusicProtocol.json.encodeToString(op).encodeToByteArray(),
            )
        }
    }

    /** Resend every queued op on (re)connect; the phone executes, the watch only reflects state. */
    fun flushPending(context: Context) {
        for (op in WearMusicStore.get(context).pendingOps.value) sendOp(context, op)
    }

    /** Publish watch state as a data item. */
    fun publishWatchState(context: Context, state: WearWatchState) {
        scope.launch {
            get(context).putData(
                WearMusicProtocol.WATCH_STATE_PATH,
                WearMusicProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }
}
