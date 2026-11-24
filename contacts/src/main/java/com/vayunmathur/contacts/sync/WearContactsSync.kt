package com.vayunmathur.contacts.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.domain.WearContactsManifest
import com.vayunmathur.contacts.domain.WearContactsProtocol
import com.vayunmathur.contacts.domain.WearPresence
import com.vayunmathur.contacts.domain.WearPresencePolicy
import com.vayunmathur.contacts.domain.WearSnapshotBuilder
import com.vayunmathur.contacts.domain.WearWatchState
import com.vayunmathur.contacts.domain.WearContact
import com.vayunmathur.library.log.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString

/** Await a Play services [Task] without a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Maps the provider model onto the watch row: names/phones/emails only, no photos. */
fun Contact.toWearContact(): WearContact = WearContact(
    id = id,
    name = name.value.trim().ifBlank {
        details.phoneNumbers.firstOrNull()?.number ?: details.emails.firstOrNull()?.address ?: ""
    },
    phones = details.phoneNumbers.map { it.number },
    emails = details.emails.map { it.address },
    isFavorite = isFavorite,
)

/** Loads the full book (provider + SIM) for a republish, e.g. from the listener service. */
fun loadWearContacts(context: Context): List<WearContact> {
    val device = runCatching { Contact.getAllContacts(context) }.getOrDefault(emptyList())
    val sim = runCatching {
        com.vayunmathur.contacts.data.SimContactsDataSource.simContactsAsContacts(context)
    }.getOrDefault(emptyList())
    val seen = device.map { it.id }.toMutableSet()
    return (device + sim.filter { seen.add(it.id) }).map { it.toWearContact() }
}

/**
 * Phone side of the contacts wear sync: capability discovery, chunked snapshot
 * publish, and the watch-state status the Settings screen shows.
 *
 * Every Wearable call is wrapped so that "no Play services / no watch / watch
 * without Contacts" is a normal state ([WearPresence.NO_WATCH]), never a crash.
 * The Data Layer only connects the two apps because they share the package name
 * `com.vayunmathur.contacts` and a signing certificate — dev builds on both
 * sides use the debug key, release builds on both sides use the same release key.
 */
class WearContactsSync(
    private val context: Context,
    private val scope: CoroutineScope,
) : CapabilityClient.OnCapabilityChangedListener, DataClient.OnDataChangedListener {

    companion object {
        private const val TAG = "WearContactsSync"
        private const val KEY_JSON = "json"
        private const val KEY_SENT_AT = "sent_at"
    }

    private val _presence = MutableStateFlow(WearPresence.NO_WATCH)

    /** Whether a contacts-capable watch is reachable / synced. */
    val presence: StateFlow<WearPresence> = _presence.asStateFlow()

    private val _watchState = MutableStateFlow<WearWatchState?>(null)

    /** Last state the watch published, or null when it never has. */
    val watchState: StateFlow<WearWatchState?> = _watchState.asStateFlow()

    private val _lastManifest = MutableStateFlow<WearContactsManifest?>(null)

    /** The manifest most recently published by this phone, for the status line. */
    val lastManifest: StateFlow<WearContactsManifest?> = _lastManifest.asStateFlow()

    @Volatile
    private var watchNodeCount = 0

    private var started = false

    /** Begin capability + watch-state listening; no-op when already started/unavailable. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            Wearable.getCapabilityClient(context)
                .addListener(this, WearContactsProtocol.CAPABILITY)
            Wearable.getDataClient(context).addListener(this)
        }.onFailure { Log.status(TAG, "Wearable listeners unavailable: ${it.message}") }
        scope.launch { refreshCapability() }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        updateNodes(capabilityInfo.nodes.size)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearContactsProtocol.WATCH_STATE_PATH) continue
            val json = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            _watchState.value = runCatching {
                WearContactsProtocol.json.decodeFromString<WearWatchState>(json)
            }.getOrNull() ?: continue
            refreshPresence()
        }
    }

    /** Publish the current address book as a capped, chunked snapshot. */
    fun publish(contacts: List<Contact>) = publishWearContacts(contacts.map { it.toWearContact() })

    /** Publish already-mapped watch rows (the listener service's republish path). */
    fun publishWearContacts(rows: List<WearContact>) {
        val selected = WearSnapshotBuilder.select(rows)
        val generation = System.currentTimeMillis()
        val chunks = WearSnapshotBuilder.chunk(generation, selected)
        val manifest = WearSnapshotBuilder.manifest(
            generation = generation,
            totalContacts = rows.size,
            selected = selected,
            chunkCount = chunks.size,
            sentAtMillis = generation,
        )
        _lastManifest.value = manifest
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dataClient = Wearable.getDataClient(context)
                // Chunks first, manifest last: the watch only applies a snapshot
                // once the manifest announcing its chunk count has arrived.
                for (chunk in chunks) {
                    val request = PutDataMapRequest
                        .create(WearContactsProtocol.chunkPath(chunk.index))
                        .apply {
                            dataMap.putString(
                                KEY_JSON,
                                WearContactsProtocol.json.encodeToString(chunk),
                            )
                            dataMap.putLong(KEY_SENT_AT, generation)
                        }
                        .asPutDataRequest().setUrgent()
                    dataClient.putDataItem(request).awaitTask()
                }
                val manifestRequest = PutDataMapRequest
                    .create(WearContactsProtocol.MANIFEST_PATH)
                    .apply {
                        dataMap.putString(
                            KEY_JSON,
                            WearContactsProtocol.json.encodeToString(manifest),
                        )
                        dataMap.putLong(KEY_SENT_AT, generation)
                    }
                    .asPutDataRequest().setUrgent()
                dataClient.putDataItem(manifestRequest).awaitTask()
                // Drop stale chunk items past the new count so an older, larger
                // generation cannot leak contacts into a later reassembly.
                deleteStaleChunks(dataClient, chunks.size)
            }.onFailure { Log.status(TAG, "snapshot publish failed: ${it.message}") }
        }
    }

    private suspend fun deleteStaleChunks(dataClient: DataClient, newCount: Int) {
        runCatching {
            val items = dataClient.dataItems.awaitTask()
            try {
                for (item in items) {
                    val index = item.uri.path?.let {
                        WearContactsProtocol.chunkIndexFromPath(it)
                    } ?: continue
                    if (index >= newCount) {
                        dataClient.deleteDataItems(item.uri).awaitTask()
                    }
                }
            } finally {
                items.release()
            }
        }
    }

    private suspend fun refreshCapability() {
        runCatching {
            val capability = Wearable.getCapabilityClient(context)
                .getCapability(WearContactsProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitTask()
            updateNodes(capability.nodes.size)
        }.onFailure {
            watchNodeCount = 0
            refreshPresence()
            Log.status(TAG, "capability check unavailable: ${it.message}")
        }
    }

    private fun updateNodes(count: Int) {
        watchNodeCount = count
        refreshPresence()
    }

    private fun refreshPresence() {
        _presence.value = WearPresencePolicy.presence(watchNodeCount, _watchState.value)
    }

    /** One-line status for the Settings screen, from the same policy the tests pin. */
    fun statusText(): String = WearPresencePolicy.statusText(_presence.value, _watchState.value)
}
