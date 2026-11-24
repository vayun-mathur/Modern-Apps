package com.vayunmathur.contacts.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.domain.WearContactsManifest
import com.vayunmathur.contacts.domain.WearContactsProtocol
import com.vayunmathur.contacts.domain.WearHandoffRequest
import com.vayunmathur.contacts.domain.WearPresence
import com.vayunmathur.contacts.domain.WearPresencePolicy
import com.vayunmathur.contacts.domain.WearSnapshotBuilder
import com.vayunmathur.contacts.domain.WearWatchState
import com.vayunmathur.contacts.domain.WearContact
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

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

/** Loads the full book (provider + SIM) for a republish, e.g. after a watch request. */
fun loadWearContacts(context: Context): List<WearContact> {
    val device = runCatching { Contact.getAllContacts(context) }.getOrDefault(emptyList())
    val sim = runCatching {
        com.vayunmathur.contacts.data.SimContactsDataSource.simContactsAsContacts(context)
    }.getOrDefault(emptyList())
    val seen = device.map { it.id }.toMutableSet()
    return (device + sim.filter { seen.add(it.id) }).map { it.toWearContact() }
}

/**
 * Phone side of the contacts wear sync over WearLink (RFCOMM,
 * :library:wearlink): presence is a completed handshake, the snapshot is a
 * chunked data item (generation/manifest discipline retained in the payload
 * protocol), watch state is a data item, and request/handoff are messages
 * handled here while this process lives.
 *
 * "No Bluetooth permission / no watch / watch without Contacts" is a normal
 * state ([WearPresence.NO_WATCH]), never a crash. There is no Play-services
 * listener service any more: watch-originated requests arriving while the
 * phone app is dead wait until it next runs and reconnects.
 */
class WearContactsSync(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "WearContactsSync"
    }

    private val _presence = MutableStateFlow(WearPresence.NO_WATCH)
    val presence: StateFlow<WearPresence> = _presence.asStateFlow()

    private val _watchState = MutableStateFlow<WearWatchState?>(null)
    val watchState: StateFlow<WearWatchState?> = _watchState.asStateFlow()

    private val _lastManifest = MutableStateFlow<WearContactsManifest?>(null)
    val lastManifest: StateFlow<WearContactsManifest?> = _lastManifest.asStateFlow()

    @Volatile
    private var watchConnected = false

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearContactsProtocol.WATCH_STATE_PATH) return
            _watchState.value = runCatching {
                WearContactsProtocol.json.decodeFromString<WearWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            refreshPresence()
        }

        override fun onMessage(path: String, body: ByteArray) {
            when (path) {
                WearContactsProtocol.REQUEST_SNAPSHOT_PATH -> republish()
                WearContactsProtocol.HANDOFF_PATH -> handleHandoff(body)
            }
        }
    }

    private val node = WearLinkNode(context, WearContactsProtocol.CAPABILITY, listener)

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                watchConnected = linkState is WearLinkState.Connected
                refreshPresence()
            }
        }
        node.start()
    }

    /** Publish the current address book as a capped, chunked snapshot. */
    fun publish(contacts: List<Contact>) = publishWearContacts(contacts.map { it.toWearContact() })

    /** Publish already-mapped watch rows (the watch-request republish path). */
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
            // Chunks first, manifest last — sent as one data item for the
            // manifest and one per chunk path, preserving the watch-side
            // reassembler's "apply only when the manifest's count is
            // complete" rule over WearLink's generation-checked documents.
            var allSent = true
            for (chunk in chunks) {
                val sent = node.putData(
                    WearContactsProtocol.chunkPath(chunk.index),
                    WearContactsProtocol.json.encodeToString(chunk).encodeToByteArray(),
                )
                allSent = allSent && sent
            }
            val manifestSent = node.putData(
                WearContactsProtocol.MANIFEST_PATH,
                WearContactsProtocol.json.encodeToString(manifest).encodeToByteArray(),
            )
            if (!allSent || !manifestSent) {
                Log.status(TAG, "snapshot publish not fully delivered (no live WearLink session)")
            }
        }
    }

    private fun republish() {
        scope.launch(Dispatchers.IO) {
            runCatching { publishWearContacts(loadWearContacts(context)) }
                .onFailure { Log.status(TAG, "republish failed: ${it.message}") }
        }
    }

    private fun handleHandoff(data: ByteArray) {
        val request = runCatching {
            WearContactsProtocol.json.decodeFromString<WearHandoffRequest>(data.decodeToString())
        }.getOrNull() ?: return
        val intent = when (request.kind) {
            WearHandoffRequest.KIND_CALL ->
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", request.value, null))
            WearHandoffRequest.KIND_MESSAGE ->
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", request.value, null))
            else -> return
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.status(TAG, "handoff failed: ${it.message}") }
    }

    private fun refreshPresence() {
        _presence.value = WearPresencePolicy.presence(
            if (watchConnected) 1 else 0, _watchState.value,
        )
    }

    /** One-line status for the Settings screen, from the same policy the tests pin. */
    fun statusText(): String = WearPresencePolicy.statusText(_presence.value, _watchState.value)
}
