package com.vayunmathur.share.platform.receive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.network.transport.TcpTransport
import com.vayunmathur.share.network.transport.isTerminal
import com.vayunmathur.share.platform.ReceivedFileStore
import com.vayunmathur.share.platform.airdrop.AirDropController
import com.vayunmathur.share.platform.discovery.BleDiscoveryManager
import com.vayunmathur.share.platform.discovery.NsdDiscoveryManager
import com.vayunmathur.share.platform.transfer.ShareTransferService
import com.vayunmathur.share.protocol.ShareNative
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareReceive"

/**
 * Staged files older than this are unreachable leftovers of a killed process, so a cold start
 * deletes them. A day, not an hour: a completed file the user has not saved yet is still
 * theirs, and only the *partial* ones are truly orphaned.
 */
private const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Single owner of everything the receive path needs, and the only source of truth for whether
 * it should be running.
 *
 * A singleton rather than fields on [ShareTransferService], because the persisted flag is then
 * authoritative: a `START_STICKY` restart with a null Intent just calls [syncServiceState], the
 * Quick Settings tile works whether or not the service is alive, and two overlapping service
 * instances cannot double-post notifications. Structurally the same shape as
 * `LocationServiceController` in `:findfamily`.
 *
 * The transport and the notifier live for the whole process, not for the enabled window:
 * [start] and [stop] only control listening and advertising. That is what lets a transfer
 * already in flight finish, and keep reporting progress, after the tile is switched off.
 */
object ShareReceiveController {

    /** Persisted on/off switch, owned by the Quick Settings tile (default off). */
    const val RECEIVE_ENABLED_KEY = "share_receive_enabled"

    /** Nearby endpoint ids are four characters. */
    private const val ENDPOINT_ID_LENGTH = 4

    /** SDK that made POST_NOTIFICATIONS a runtime permission. */
    private const val NOTIFICATION_RUNTIME_SDK = 33

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private class Parts(
        val nsd: NsdDiscoveryManager,
        val ble: BleDiscoveryManager,
        val store: ReceivedFileStore,
        val transport: TcpTransport,
        val airDrop: AirDropController,
        val notifier: ShareReceiveNotifier,
        val endpointId: String,
    )

    private var parts: Parts? = null

    @Volatile
    private var advertising = false

    /**
     * The device name announced over mDNS, BLE and `CONNECTION_REQUEST`.
     *
     * Held here rather than in `ShareViewModel` because advertising has to work from boot, when
     * no ViewModel exists.
     */
    @Volatile
    var localName: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "My device"
        private set

    /**
     * Endpoint id advertised as the mDNS instance name. Nearby endpoint ids are four
     * characters; a stable random one per process keeps re-advertising idempotent.
     */
    private fun randomEndpointId(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..ENDPOINT_ID_LENGTH).map { alphabet.random() }.joinToString("")
    }

    private fun ensure(context: Context): Parts = synchronized(lock) {
        parts ?: run {
            val app = context.applicationContext
            val store = ReceivedFileStore(app)
            val endpointId = randomEndpointId()
            val transport = TcpTransport(
                localName = localName,
                receivedStore = store,
                localEndpointInfo = endpointInfo() ?: ByteArray(0),
                localEndpointId = endpointId,
            )
            val airDrop = AirDropController(app, store) { localName }
            val notifier = ShareReceiveNotifier(
                app,
                incoming = combine(transport.incomingConnections, airDrop.receiver.incoming) { qs, ad -> qs + ad },
                retire = { handle -> retire(handle) },
            )
            notifier.start(scope)
            Parts(
                nsd = NsdDiscoveryManager(app),
                ble = BleDiscoveryManager(app),
                store = store,
                transport = transport,
                airDrop = airDrop,
                notifier = notifier,
                endpointId = endpointId,
            ).also { parts = it }
        }
    }

    /**
     * The one [TcpTransport] in the process.
     *
     * Shared with the send flow so `setLocalIdentity` has exactly one owner: two transports
     * would mean two `ServerSocket`s and two identities for one advertised device.
     */
    fun transport(context: Context): TcpTransport = ensure(context).transport

    fun nsd(context: Context): NsdDiscoveryManager = ensure(context).nsd

    fun ble(context: Context): BleDiscoveryManager = ensure(context).ble

    fun airDrop(context: Context): AirDropController = ensure(context).airDrop

    fun connectionFor(context: Context, handle: Long): Connection? =
        ensure(context).transport.connectionFor(handle)

    /** Whether a notification action for [handle] still has a live session to act on. */
    fun hasSession(context: Context, handle: Long): Boolean {
        val p = ensure(context)
        return if (isAirDropHandle(handle)) p.airDrop.receiver.sessionFor(handle) != null
        else p.transport.connectionFor(handle) != null
    }

    /** True while any session is still moving bytes; the service must not die under one. */
    fun hasActiveTransfers(context: Context): Boolean {
        val p = ensure(context)
        return p.transport.connections.value.any { !it.state.value.isTerminal } || p.airDrop.receiver.hasActiveTransfers
    }

    /**
     * The endpoint-info blob for the current device name, or null if it cannot be built.
     *
     * PHONE is cosmetic — it picks the icon the peer renders next to our name.
     */
    fun endpointInfo(): ByteArray? = try {
        ShareNativeDiscovery.nativeBuildEndpointInfo(localName, ShareNative.DEVICE_TYPE_PHONE)
    } catch (e: UnsatisfiedLinkError) {
        Log.error(TAG, "libshare_nearby unavailable — cannot build endpoint info", e)
        null
    }

    /** Rename the device, re-advertising if we are currently visible. */
    fun setLocalName(context: Context, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == localName) return
        localName = trimmed
        // The name lives inside the endpoint-info blob, so renaming means re-advertising.
        if (advertising) {
            stop()
            start(context)
        }
    }

    // ------------------------------------------------------------------
    // Persisted flag + reconciliation
    // ------------------------------------------------------------------

    suspend fun isReceiveEnabled(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBooleanAwait(RECEIVE_ENABLED_KEY, false)

    suspend fun setReceiveEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(RECEIVE_ENABLED_KEY, enabled)
        syncServiceState(context)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < NOTIFICATION_RUNTIME_SDK || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether the foreground service should be running: the flag *and* the permission.
     *
     * Both `syncServiceState` and the service's own stop check use this, so they cannot
     * disagree — a service that keeps being started on one condition and refuses to stop on
     * another would run forever without ever receiving anything.
     */
    suspend fun isServiceWanted(context: Context): Boolean {
        val app = context.applicationContext
        return isReceiveEnabled(app) && hasNotificationPermission(app)
    }

    /**
     * Start the service if it is wanted, otherwise ask it to stop. Safe from any context —
     * tile, boot, sticky restart, permission refresh.
     *
     * Stopping goes through the service's own `ACTION_STOP` rather than `stopService`, because
     * only the service knows whether a transfer is still in flight; killing it there and then
     * would drop a live socket.
     */
    suspend fun syncServiceState(context: Context) {
        val app = context.applicationContext
        val wanted = isServiceWanted(app)
        withContext(Dispatchers.Main) {
            if (wanted) {
                try {
                    app.startForegroundService(
                        Intent(app, ShareTransferService::class.java)
                            .setAction(ShareTransferService.ACTION_START_RECEIVE)
                    )
                } catch (_: Exception) {
                }
            } else {
                ShareTransferService.stop(app)
            }
        }
    }

    // ------------------------------------------------------------------
    // Listening + advertising
    // ------------------------------------------------------------------

    /**
     * Begin listening and advertising. Idempotent.
     *
     * Also performs cold-start recovery. Nothing else survives process death — sockets, native
     * handles and partial files all go — but *posted notifications do*, so a stale Request
     * notification would offer an Accept that can never work.
     */
    // Startup spans socket, TLS and NSD/BLE layers, which throw undocumented
    // RuntimeExceptions as well as IOExceptions; both catches stay broad by design.
    @Suppress("TooGenericExceptionCaught")
    fun start(context: Context) {
        val app = context.applicationContext
        val p = ensure(app)
        val endpointInfo = endpointInfo()
        if (endpointInfo == null) {
            // A random blob is worse than silence: peers would list us and then fail to connect.
            Log.status(TAG, "refusing to advertise without an endpoint info blob")
            return
        }
        // Under `lock`, not just a volatile check-then-set: `start` runs on the service's main
        // thread and `stop` on Dispatchers.IO, and an interleaving that closed the ServerSocket
        // after listen() had already handed its port to nsd.advertise() would leave the device
        // advertised on a port nothing is accepting on.
        synchronized(lock) {
            if (advertising) return
            advertising = true
            ShareReceiveNotifier.cancelStale(app)
            val swept = p.store.gcOrphans(ORPHAN_MAX_AGE_MS)
            if (swept > 0) Log.status(TAG, "swept $swept orphaned staged file(s)")
            // Before listen(), so a socket accepted immediately announces the identity we are
            // about to advertise rather than the transport's construction-time one.
            try {
                p.transport.setLocalIdentity(localName, endpointInfo, p.endpointId)
                val port = p.transport.listen()
                // The WIFI_LAN port under _FC9F5ED42C8A._tcp, endpoint info in the `n` TXT
                // attribute — the record a Quick Share device lists us from.
                p.nsd.advertise(p.endpointId, endpointInfo, port)
                // The same blob inside a Nearby Connections BleAdvertisement under 0xFEF3.
                p.ble.startAdvertising(p.endpointId, endpointInfo)
                Log.status(TAG, "receiving as \"$localName\" on port $port")
            } catch (e: Exception) {
                // Bind/TLS/advertise failures must not crash the receive service or
                // wedge `advertising` on: a later start() retries from scratch.
                advertising = false
                Log.error(TAG, "failed to start receiving", e)
                return
            }
        }
        scope.launch {
            try {
                p.airDrop.startReceiving()
            } catch (e: Exception) {
                Log.error(TAG, "failed to start AirDrop receiving", e)
            }
        }
    }

    /**
     * Stop listening and advertising. Idempotent.
     *
     * Deliberately leaves live sessions pumping: switching off means "no new transfers", not
     * "drop the one in progress".
     */
    fun stop() {
        val p = parts ?: return
        synchronized(lock) {
            if (!advertising) return
            advertising = false
            p.nsd.unadvertise()
            p.ble.stopAdvertising()
            p.transport.stopListening()
        }
        scope.launch { p.airDrop.stopReceiving() }
        Log.status(TAG, "stopped receiving")
    }

    /** Suspend until no session is still moving bytes. */
    suspend fun awaitIdle(context: Context) {
        ensure(context).transport.awaitIdle()
    }

    /**
     * Push the current name and endpoint id into the transport, for sessions it is about to
     * create.
     *
     * The send flow needs this before dialling out: a rename while not advertising never
     * refreshed the transport, so the `CONNECTION_REQUEST` would carry a stale name.
     */
    fun refreshLocalIdentity(context: Context) {
        val p = ensure(context)
        endpointInfo()?.let { p.transport.setLocalIdentity(localName, it, p.endpointId) }
    }

    /** Answer a peer's `INTRODUCTION` (or AirDrop `Ask`) from a notification action. */
    fun acceptIncoming(context: Context, handle: Long, accept: Boolean): Int {
        val p = ensure(context)
        return if (isAirDropHandle(handle)) p.airDrop.accept(handle, accept)
        else p.transport.acceptIncoming(handle, accept)
    }

    suspend fun cancel(context: Context, handle: Long) {
        val p = ensure(context)
        if (isAirDropHandle(handle)) p.airDrop.cancel(handle) else p.transport.cancel(handle)
    }

    /** Drop a finished session once its outcome has been shown. */
    private fun retire(handle: Long) {
        val p = parts ?: return
        if (isAirDropHandle(handle)) p.airDrop.retire(handle) else p.transport.retire(handle)
    }

    /** AirDrop sessions use negative handles; Quick Share's are native pointers. */
    private fun isAirDropHandle(handle: Long): Boolean = handle < 0
}
