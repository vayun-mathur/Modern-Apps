package com.vayunmathur.share.platform.airdrop

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.network.airdrop.AirDropHttpServer
import com.vayunmathur.share.platform.ReceivedFileStore
import com.vayunmathur.share.platform.airdrop.link.AirDropLink
import com.vayunmathur.share.platform.airdrop.link.AirDropLinkSelector
import com.vayunmathur.share.platform.airdrop.link.MoseyStatus
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AirDrop"

/**
 * Owns AirDrop for the process: the selected link (Mosey or Wi-Fi), the HTTPS receiver, the
 * mDNS records, and the sender.
 *
 * The link is reference-counted across receiving, browsing and sending, because bringing up
 * Mosey starts an AWDL radio session that should run only while something needs it.
 */
class AirDropController(
    context: Context,
    store: ReceivedFileStore,
    private val localName: () -> String,
) {
    private val app = context.applicationContext
    private val discovery = AirDropDiscovery(app)
    private val ble = AirDropBleAdvertiser(app)
    private val sender = AirDropSender(localName)

    val receiver = AirDropReceiver(
        store = store,
        localName = localName,
        freeBytes = { app.filesDir.usableSpace },
        onTransfer = { remote, started ->
            val link = selection?.link ?: return@AirDropReceiver
            if (started) link.onTransferStarted(remote) else link.onTransferEnded(remote)
        },
    )
    private val server = AirDropHttpServer(receiver::handle)

    private val linkLock = Mutex()
    private var selection: AirDropLinkSelector.Selection? = null
    private var users = 0
    private var receiving = false

    private val _mosey = MutableStateFlow<MoseyStatus?>(null)

    /** What Mosey detection found the last time a link was selected; null before that. */
    val mosey: StateFlow<MoseyStatus?> = _mosey.asStateFlow()

    /** Peers listed by the last browse, keyed by the [NearbyDevice.endpointId] shown for them. */
    private val peers = ConcurrentHashMap<String, AirDropPeer>()

    private suspend fun acquire(): AirDropLink = linkLock.withLock {
        val current = selection ?: AirDropLinkSelector.select(app).also {
            selection = it
            _mosey.value = it.mosey
            Log.status(TAG, "link: ${it.link.kind}")
        }
        users++
        current.link
    }

    private suspend fun release() = linkLock.withLock {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            selection?.link?.deactivate()
            selection = null
        }
    }

    suspend fun startReceiving() {
        if (receiving) return
        val link = acquire()
        try {
            val port = server.start()
            discovery.advertise(AirDropMessages.randomShortId(), port, link.network)
            receiving = true
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    suspend fun stopReceiving() {
        if (!receiving) return
        receiving = false
        discovery.unadvertise()
        server.stop()
        release()
    }

    /** Lists AirDrop receivers with their names while collected. */
    fun browse(): Flow<NearbyDevice> = flow {
        val link = acquire()
        ble.start()
        try {
            emitAll(
                discovery.browse(link.network).mapNotNull { peer ->
                    val info = sender.discover(peer, link) ?: return@mapNotNull null
                    val id = "airdrop:${peer.serviceName}"
                    peers[id] = peer
                    NearbyDevice(
                        endpointId = id,
                        endpointName = info.receiverComputerName,
                        serviceName = peer.serviceName,
                        host = peer.address.hostAddress,
                        port = peer.port,
                        source = DiscoverySource.AirDrop,
                    )
                },
            )
        } finally {
            ble.stop()
            release()
        }
    }

    suspend fun send(device: NearbyDevice, files: List<File>, onProgress: (Long, Long) -> Unit): AirDropSender.Result {
        val peer = peers[device.endpointId] ?: return AirDropSender.Result.Failed("receiver is no longer listed")
        val link = acquire()
        return try {
            sender.send(peer, link, files, onProgress)
        } finally {
            release()
        }
    }

    fun accept(handle: Long, accept: Boolean): Int = receiver.accept(handle, accept)

    fun cancel(handle: Long) = receiver.cancel(handle)

    fun retire(handle: Long) = receiver.retire(handle)
}
