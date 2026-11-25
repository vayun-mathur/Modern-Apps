package com.vayunmathur.maps.wear.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.maps.wear.data.WearMapsStore
import com.vayunmathur.maps.wear.domain.WearMapSnapshot
import com.vayunmathur.maps.wear.domain.WearMapsProtocol
import com.vayunmathur.maps.wear.domain.WearPosition
import com.vayunmathur.maps.wear.domain.WearRouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Maps: region/route/position data items land
 * in [WearMapsStore], watch state goes back as a data item, and a fresh
 * region is requested by message. Delivery is bound to this process
 * being alive; the persisted store covers cold starts and link loss.
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
        val store = WearMapsStore.get(context)
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                val text = body.decodeToString()
                when (path) {
                    WearMapsProtocol.REGION_PATH -> decode<WearMapSnapshot>(text)?.let(store::setSnapshot)
                    WearMapsProtocol.ROUTE_PATH -> decode<WearRouteState>(text)?.let(store::setRoute)
                    WearMapsProtocol.POSITION_PATH -> decode<WearPosition>(text)?.let(store::setPosition)
                }
            }
        }
        val created = WearLinkNode(context, WearMapsProtocol.CAPABILITY, listener)
        scope.launch {
            created.state.collect { linkState ->
                store.setConnected(linkState is WearLinkState.Connected)
            }
        }
        created.start()
        return created
    }

    private inline fun <reified T> decode(text: String): T? = runCatching {
        WearMapsProtocol.json.decodeFromString<T>(text)
    }.getOrElse {
        Log.status(TAG, "ignoring malformed data item")
        null
    }

    fun publishWatchState(context: Context) {
        val state = WearMapsStore.get(context).watchState()
        scope.launch {
            get(context).putData(
                WearMapsProtocol.WATCH_STATE_PATH,
                WearMapsProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh region; false when no session is live. */
    suspend fun requestRegion(context: Context): Boolean =
        get(context).sendMessage(WearMapsProtocol.REQUEST_REGION_PATH, ByteArray(0))
}
