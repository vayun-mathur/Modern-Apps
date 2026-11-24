package com.vayunmathur.contacts.wear.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.vayunmathur.contacts.wear.data.WearContactsStore
import com.vayunmathur.contacts.wear.domain.WearContactsChunk
import com.vayunmathur.contacts.wear.domain.WearContactsManifest
import com.vayunmathur.contacts.wear.domain.WearContactsProtocol
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the phone's chunked snapshot while the UI is closed, stores the
 * completed snapshot locally, then publishes watch state back so the phone can
 * show "watch has N contacts". Incomplete generations change nothing.
 */
class WearContactsListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = WearContactsStore(applicationContext)
        var completed = false
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val json = runCatching {
                DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            runCatching {
                when {
                    path == WearContactsProtocol.MANIFEST_PATH -> {
                        val before = store.snapshot.value.contacts.size
                        store.onManifest(decodeManifest(json))
                        completed = completed || store.snapshot.value.contacts.size != before ||
                            store.snapshot.value.manifest != null
                    }
                    WearContactsProtocol.chunkIndexFromPath(path) != null ->
                        store.onChunk(decodeChunk(json))
                    else -> Unit
                }
            }.onFailure { Log.status(TAG, "snapshot item ignored: ${it.message}") }
        }
        val snapshot = store.snapshot.value
        if (snapshot.manifest != null) {
            scope.launch { WearWatchStatePublisher.publish(applicationContext, snapshot) }
        }
    }

    private fun decodeManifest(json: String): WearContactsManifest =
        WearContactsProtocol.json.decodeFromString(json)

    private fun decodeChunk(json: String): WearContactsChunk =
        WearContactsProtocol.json.decodeFromString(json)

    companion object {
        private const val TAG = "WearContactsListener"
        private const val KEY_JSON = "json"
    }
}
