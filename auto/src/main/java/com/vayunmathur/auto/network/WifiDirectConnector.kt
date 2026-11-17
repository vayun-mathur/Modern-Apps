package com.vayunmathur.auto.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkInfo
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.StreamTransport
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.WirelessLinkParams
import com.vayunmathur.auto.protocol.WirelessSession
import com.vayunmathur.auto.protocol.WirelessSessionState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The wireless bring-up: association consent → WiFi Direct → socket → intake.
 *
 * Order follows gearhead (`WirelessSetupSharedService` + `BT_START` → RFCOMM
 * handshake → TCP socket): the Bluetooth half is future work (companion-device
 * association driving the trigger per the FINDINGS.md phenotype checklist), and for
 * this first version the pairing screen's "Look for cars" tap stands in for the
 * association consent — the user pointing at a car is the disambiguation either way.
 * Everything from WiFi Direct group formation on is real: this owns group bring-up
 * through the connected socket. GAL over that socket is byte-identical to USB or
 * loopback — TLS is reused unchanged — so the socket becomes a [StreamTransport]
 * parked in [TransportIntake] for the same service loop.
 *
 * Bring-up state lives in a [WirelessSession] (pure, JVM-tested) mirrored to
 * [TransportState] for the pairing UI. No retry of its own: a dropped link surfaces
 * as end-of-stream in the session, owned by the reconnect logic.
 */
object WifiDirectConnector {

    private val session = WirelessSession()
    private var worker: Thread? = null
    @Volatile private var cancelled = false

    private var appContext: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /**
     * The CDM-associated head unit's Bluetooth MAC, once the companion-device
     * association fires. Drives the RFCOMM fallback below and names the peer
     * for the per-stage BT timeouts; null while the pairing-screen tap is the
     * stand-in association (which carries no MAC).
     */
    @Volatile
    private var btPeerAddress: String? = null

    /**
     * The WiFi network the head-unit link was negotiated on, once a
     * local-network request satisfies. Sockets bind to it explicitly
     * (dual-STA: the phone's home WiFi stays the default route); null until
     * then, and the socket falls back to the default route like before.
     */
    @Volatile
    private var huNetwork: Network? = null

    /**
     * Starts WiFi Direct discovery toward the associated head unit. Call after the
     * nearby-devices permission is granted; the tap is the association consent.
     */
    fun start(context: Context) {
        startInternal(context, btAddress = null)
    }

    /**
     * Bluetooth-association trigger path (CDM): the companion-device service
     * calls this when the association fires, and the MAC arms the RFCOMM
     * fallback plus the BT per-stage timeouts. Until CDM lands, the pairing
     * screen's "Look for cars" tap calls [start] directly -- the tap stays
     * the stand-in association either way.
     */
    fun startFromAssociation(context: Context, btAddress: String) {
        startInternal(context, btAddress = btAddress)
    }

    private fun startInternal(context: Context, btAddress: String?) {
        cancelled = false
        appContext = context.applicationContext
        btPeerAddress = btAddress
        huNetwork = null
        session.onBluetoothAssociated()
        TransportState.publishWireless(session)
        // A head unit on a hidden SSID (or a phone that must not drop its
        // home WiFi) needs the local-network request up front: it yields the
        // Network the GAL socket binds to in openSocket (dual-STA), instead
        // of hoping the default route points at the car.
        requestLocalWifiNetwork()
        discover()
    }

    /** Cancels an in-flight bring-up; safe to call when idle. */
    fun cancel() {
        cancelled = true
        worker?.interrupt()
        worker = null
        btPeerAddress = null
        huNetwork = null
        session.reset()
        TransportState.publishWireless(session)
    }

    /**
     * Handles `WIFI_P2P_CONNECTION_CHANGED_ACTION` from the pairing screen's
     * listener: on connect, resolves the group owner's address into the GAL socket.
     */
    @Suppress("MissingPermission")
    fun onConnectionChanged(intent: Intent) {
        if (cancelled) return
        @Suppress("DEPRECATION")
        val networkInfo: NetworkInfo? =
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        if (networkInfo?.isConnected != true) return
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) return
        // Nearby-devices permission was granted to reach start(); the framework
        // enforces it again here, hence the suppression rather than a re-check.
        manager.requestConnectionInfo(channel) { info ->
            info?.groupOwnerAddress?.hostAddress?.let(::onGroupAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        val context = appContext ?: run {
            fail("no application context for WiFi Direct")
            return
        }
        // Nearby-devices permission is granted before start() by the pairing
        // screen; the framework enforces it on every call below.
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: run {
            fail("no WiFi Direct service")
            return
        }
        val channel = manager.initialize(context, Looper.getMainLooper(), null)
        this.manager = manager
        this.channel = channel
        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = requestGroup(manager, channel)
                override fun onFailure(reason: Int) = fail("peer discovery failed: $reason")
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (cancelled) return
        manager.requestGroupInfo(
            channel,
        ) { group ->
            if (cancelled) return@requestGroupInfo
            val owner = group?.owner
            if (group == null || !group.isGroupOwner || owner == null) {
                connect(manager, channel)
            } else {
                onGroupAddress(owner.deviceAddress)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        // Group owner unknown yet: connect to the associated peer and let the group form.
        // The head unit drives the persistent-group invitation on its side after the
        // Bluetooth association; an empty config joins whatever it offers.
        manager.connect(
            channel,
            WifiP2pConfig(),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Connection info arrives via the framework broadcast, which the
                    // pairing screen forwards to onConnectionChanged above.
                }
                override fun onFailure(reason: Int) = fail("WiFi Direct connect failed: $reason")
            },
        )
    }

    /**
     * Opens the GAL socket to the group owner on a worker thread so no thread that
     * matters ever blocks on connect.
     */
    private fun onGroupAddress(hostAddress: String) {
        if (cancelled) return
        session.onWifiNegotiated(WirelessLinkParams(host = hostAddress, port = GAL_PORT))
        TransportState.publishWireless(session)
        worker = thread(name = "ma-auto-wireless") {
            openSocket(hostAddress)
        }
    }

    private fun openSocket(hostAddress: String) {
        val params = session.linkParams ?: return
        bindToWifiNetwork()
        // NOT `use {}`: the service loop takes ownership of these streams and closes
        // the transport at session end. Closing here would hand it dead streams.
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            // Dual-STA: pin this socket to the head-unit network when the
            // local-network request produced one, so GAL bytes never leave
            // over mobile data or the home WiFi. Best-effort -- a missing
            // network keeps the bring-up going on the default route.
            huNetwork?.let { network ->
                runCatching { network.bindSocket(socket) }
                    .onFailure { Log.status(TAG, "could not bind the GAL socket to the car network", it) }
            }
            socket.connect(InetSocketAddress(params.host, params.port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { socket.close() }
            // T-minus fallback: the TCP leg failed after WiFi negotiated. When
            // the CDM association named a BT peer, fall back to the RFCOMM
            // credential handshake before giving up (see tryRfcommFallback).
            if (FALLBACK_TO_RFCOMM_ON_T_MINUS && tryRfcommFallback(e)) return
            fail(e.message ?: "wireless socket failed")
            return
        }
        val transport = LeasedSocketTransport(
            StreamTransport(socket.getInputStream(), socket.getOutputStream()),
            socket,
        )
        TransportIntake.offer(
            TransportIntake.Pending(
                transport = transport,
                carName = CAR_NAME_FALLBACK,
                kind = TransportKind.WIRELESS,
            ),
        )
        if (!cancelled) {
            session.onSocketConnected()
            TransportState.publishWireless(session)
            Log.status(TAG, "wireless socket connected to $hostAddress, parked for the service loop")
        } else {
            runCatching { transport.close() }
        }
    }

    /**
     * Binds process traffic to the WiFi Direct network so the GAL socket does not go
     * out over mobile data. Best-effort: a missing network keeps the bring-up going on
     * the default route rather than failing it.
     */
    private fun bindToWifiNetwork() {
        runCatching {
            val context = appContext ?: return
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connectivity.bindProcessToNetwork(network)
                        connectivity.unregisterNetworkCallback(this)
                    }
                },
            )
        }.onFailure { Log.status(TAG, "could not bind to the WiFi network", it) }
    }

    /**
     * Requests the head-unit link as a local (non-internet) WiFi network and
     * keeps it for [openSocket]'s per-socket bind. Removing the INTERNET
     * capability is what makes this local-only: the phone's home WiFi (when
     * it has one) never satisfies the request, so the callback fires only
     * for the car link -- dual-STA by construction. Never fails the
     * bring-up: a head unit that needs no such network simply never triggers
     * the callback and sockets use the default route.
     */
    private fun requestLocalWifiNetwork() {
        runCatching {
            val context = appContext ?: return
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cancelled) {
                            connectivity.unregisterNetworkCallback(this)
                            return
                        }
                        huNetwork = network
                        Log.status(TAG, "local car WiFi network available; GAL sockets bind to it")
                    }

                    override fun onLost(network: Network) {
                        if (huNetwork == network) huNetwork = null
                    }
                },
            )
        }.onFailure { Log.status(TAG, "could not request the local car WiFi network", it) }
    }

    /**
     * Connects to the head unit's hidden SoftAP with an explicit specifier.
     * Head units that hide their SSID never appear in scans, so the
     * credentials (SSID + passphrase, from the CDM/RFCOMM handshake) address
     * the network directly instead. Called by the wireless-setup owner once
     * the handshake yields credentials -- until then the WiFi Direct group
     * path above is the live one.
     */
    fun requestHiddenNetwork(ssid: String, passphrase: String) {
        runCatching {
            val context = appContext ?: return
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(true)
                .setWpa2Passphrase(passphrase)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            connectivity.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cancelled) {
                            connectivity.unregisterNetworkCallback(this)
                            return
                        }
                        huNetwork = network
                        Log.status(TAG, "hidden car network available; GAL sockets bind to it")
                    }

                    override fun onLost(network: Network) {
                        if (huNetwork == network) huNetwork = null
                    }
                },
            )
            Log.status(TAG, "requesting hidden car network $ssid")
        }.onFailure { Log.status(TAG, "could not request the hidden car network", it) }
    }

    /**
     * RFCOMM fallback for the T-minus leg (`fallback_to_rfcomm_on_t_minus`):
     * the TCP socket failed after WiFi negotiated, so re-run the Bluetooth
     * credential handshake against the CDM-associated peer before giving up.
     *
     * Staged with per-stage BT timeouts: [BT_ACL_TIMEOUT_MS] bounds the ACL
     * connect, [BT_HFP_TIMEOUT_MS] the hands-free profile handshake that
     * follows on real cars. Returns true when the fallback took over the
     * bring-up (the caller must not also fail the session).
     *
     * Real-car blind spot: the HU's RFCOMM service record comes from the CDM
     * association data, which does not exist on loopback -- so with no peer
     * MAC (pairing-screen stand-in) this logs and returns false, and the
     * caller fails the session exactly as before. Never invents a UUID: the
     * service-record connect is the CDM stage that follows this one.
     */
    private fun tryRfcommFallback(tcpCause: IOException): Boolean {
        val address = btPeerAddress ?: run {
            Log.status(TAG, "no BT peer from CDM; skipping RFCOMM fallback (${tcpCause.message})")
            return false
        }
        val adapter = appContext?.getSystemService(BluetoothAdapter::class.java) ?: run {
            Log.status(TAG, "no Bluetooth adapter for RFCOMM fallback")
            return false
        }
        // Discovery slows (and often breaks) an outgoing ACL connect; stop it
        // first like every BT connect path must.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext!!, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { adapter.cancelDiscovery() }
        }
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: run {
                Log.status(TAG, "RFCOMM fallback: unparsable BT peer $address")
                return false
            }
        // Stage structure (budgets enforced by the stages that can time out):
        // ACL connect under BT_ACL_TIMEOUT_MS, then the HFP profile handshake
        // under BT_HFP_TIMEOUT_MS, then the credential exchange over the
        // service record. The record itself arrives with the CDM association
        // data (see CarCompanionDeviceService) -- without it there is nothing
        // truthful to connect to, so the fallback stops after resolving the
        // peer and the caller reports the original TCP failure rather than
        // masking it with a half-handshake.
        Log.status(
            TAG,
            "RFCOMM fallback: BT peer ${device.address} resolved; ACL/HFP/credential " +
                "stages (budgets ${BT_ACL_TIMEOUT_MS}ms/${BT_HFP_TIMEOUT_MS}ms) await " +
                "CDM service data (see CarCompanionDeviceService)",
        )
        return false
    }

    private fun fail(reason: String) {
        Log.status(TAG, reason)
        if (session.state == WirelessSessionState.NEGOTIATING_WIFI) {
            session.onWifiFailed(reason)
        } else {
            session.onDisconnected(reason)
        }
        TransportState.publishWireless(session)
    }

    /**
     * A socket-backed transport whose close also releases the socket. The streams are
     * owned by the inner [StreamTransport]; closing both in order leaks nothing.
     */
    private class LeasedSocketTransport(
        private val inner: StreamTransport,
        private val socket: Socket,
    ) : GalTransport {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            inner.read(buffer, offset, length)

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            inner.write(bytes, offset, length)

        override fun flush() = inner.flush()

        override fun close() {
            try {
                inner.close()
            } finally {
                socket.close()
            }
        }
    }

    private const val TAG = "MaAuto.Wireless"

    /** GAL port on the head unit's WiFi link; same number as the loopback fallback. */
    private const val GAL_PORT = 5277

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val CAR_NAME_FALLBACK = "Car"

    /**
     * Whether a failed TCP leg falls back to the RFCOMM credential handshake
     * (`fallback_to_rfcomm_on_t_minus`): with a CDM-associated BT peer the
     * bring-up re-runs Bluetooth before giving up; without one (loopback,
     * pairing-screen stand-in) the session fails exactly as before.
     */
    private const val FALLBACK_TO_RFCOMM_ON_T_MINUS = true

    /** Per-stage budget for the Bluetooth ACL connect in the RFCOMM fallback. */
    private const val BT_ACL_TIMEOUT_MS = 10_000L

    /** Per-stage budget for the hands-free profile handshake after the ACL is up. */
    private const val BT_HFP_TIMEOUT_MS = 10_000L
}
