package com.vayunmathur.contacts.wear.sync

import android.content.Context
import com.vayunmathur.contacts.wear.data.WearContactsStore
import com.vayunmathur.contacts.wear.domain.WearContactsChunk
import com.vayunmathur.contacts.wear.domain.WearContactsManifest
import com.vayunmathur.contacts.wear.domain.WearContactsProtocol
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watch side of WearLink for Contacts: manifest/chunk data items feed
 * [WearContactsStore]'s reassembler, and a completed snapshot publishes
 * watch state back. Replaces the Play-services listener service; delivery
 * is bound to this process being alive.
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    // One store shared with the UI so reassembly state is not split.
    @Volatile
    private var store: WearContactsStore? = null

    fun store(context: Context): WearContactsStore {
        val appContext = context.applicationContext
        return store ?: synchronized(this) {
            store ?: WearContactsStore(appContext).also { store = it }
        }
    }

    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                val watchStore = store(context)
                runCatching {
                    when {
                        path == WearContactsProtocol.MANIFEST_PATH ->
                            watchStore.onManifest(decodeManifest(body))
                        WearContactsProtocol.chunkIndexFromPath(path) != null ->
                            watchStore.onChunk(decodeChunk(body))
                        else -> return
                    }
                }.onFailure { Log.status(TAG, "snapshot item ignored: ${it.message}") }
                val snapshot = watchStore.snapshot.value
                if (snapshot.manifest != null) {
                    scope.launch { WearWatchStatePublisher.publish(context, snapshot) }
                }
            }
        }
        return WearLinkNode(context, WearContactsProtocol.CAPABILITY, listener).also { it.start() }
    }

    /** Live-session check for capability probing (no system node query any more). */
    fun isConnected(context: Context): Boolean = get(context).isConnected

    private fun decodeManifest(body: ByteArray): WearContactsManifest =
        WearContactsProtocol.json.decodeFromString(body.decodeToString())

    private fun decodeChunk(body: ByteArray): WearContactsChunk =
        WearContactsProtocol.json.decodeFromString(body.decodeToString())
}
