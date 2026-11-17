package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SubscriptionManager
import com.vayunmathur.library.log.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IMS PDN access for RCS media (MSRP sockets).
 *
 * Mirrors TestRcsApp's `ImsPdnNetworkFetcher` (NET_CAPABILITY_IMS request →
 * cached `Network` → `network.socketFactory` sockets bound to the IMS PDN)
 * without Guava: one-shot `requestNetwork` calls wrapped in suspend functions
 * with a bounded wait, so a missing IMS PDN degrades to a plain socket instead
 * of hanging.
 *
 * Raw sockets are inherent here (MSRP is a TCP protocol, not HTTP — no HTTP
 * client can speak it); FT-over-HTTP stays on `NetworkClient` per the
 * networking rules.
 *
 * Every entry point early-returns null when the dev gate is off and never
 * throws; callers fall back to unbound sockets.
 */
object RcsImsNetwork {
    private const val TAG = "RcsImsNetwork"

    /** How long to wait for the IMS network before falling back. */
    private const val REQUEST_TIMEOUT_MS = 15_000L

    /** Cached IMS network; invalidated when a socket bind fails. */
    @Volatile
    private var cached: Network? = null

    /** Last IMS-PDN local address seen, for SDP offers. Null = unknown. */
    @Volatile
    var lastLocalIp: String? = null
        private set

    /**
     * The IMS PDN `Network`, requesting it when not cached. Null when
     * unavailable (no IMS PDN, timeout, or permission failure).
     *
     * Two paths: (1) scan already-up networks for IMS capability (no request
     * needed — the PDN is typically already connected for VoLTE); (2) a
     * specifier-pinned `requestNetwork` (CELLULAR + IMS + MMTEL +
     * TelephonyNetworkSpecifier, mirroring the system's own IMS request —
     * a bare IMS request doesn't match the specifier-gated NetworkAgent).
     */
    suspend fun imsNetwork(context: Context, subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): Network? {
        if (!RcsFeature.enabled) return null
        cached?.let { return it }
        val cm = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return null
        // Path 1: already-up scan (fast, no request).
        scanForIms(cm)?.let { network ->
            cached = network
            lastLocalIp = localIpFor(context, network)
            return network
        }
        // Path 2: pinned request.
        val network = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val builder = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                // NET_CAPABILITY_MMTEL is API 33+; on 31-32 the IMS
                // capability alone still matches the IMS PDN.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMTEL)
                }
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    runCatching {
                        val specClass = Class.forName(
                            "android.net.TelephonyNetworkSpecifier",
                        )
                        val ctor = specClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                        builder.setNetworkSpecifier(ctor.newInstance(subId) as android.net.NetworkSpecifier)
                    }.onFailure {
                        Log.status(TAG, "TelephonyNetworkSpecifier unavailable", it)
                    }
                }
                val request = builder.build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
                runCatching { cm.requestNetwork(request, callback) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        if (network != null) {
            cached = network
            lastLocalIp = localIpFor(context, network)
        }
        return network
    }

    /** Scan connected networks for IMS capability (no request needed). */
    private fun scanForIms(cm: ConnectivityManager): Network? {
        return runCatching {
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            }
        }.getOrNull()
    }

    /**
     * Open a TCP socket to [host]:[port], preferring the IMS PDN's socket
     * factory (source-bound to the IMS local address like TestRcsApp's
     * `MsrpManager.createMsrpSession`). Falls back to a plain socket when the
     * IMS network is unavailable. Null only when both paths fail.
     */
    suspend fun createSocket(
        context: Context,
        host: String,
        port: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket? {
        if (!RcsFeature.enabled) return null
        // IMS path first.
        val ims = imsNetwork(context)
        if (ims != null) {
            val bound = runCatching {
                val factory = ims.getSocketFactory()
                val local = lastLocalIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                Log.status(TAG, "IMS socket to $host:$port via ${ims} local=$local")
                val socket = if (local != null) {
                    factory.createSocket(host, port, local, 0)
                } else {
                    val s = factory.createSocket()
                    s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
                    s
                }
                socket.soTimeout = 0
                socket
            }
            val socket = bound.getOrElse {
                Log.status(TAG, "IMS socket bind failed to $host:$port", it)
                null
            }
            if (socket != null && socket.isConnected) return socket
            runCatching { socket?.close() }
            // IMS network went stale — drop it so the next call re-requests.
            cached = null
            Log.status(TAG, "IMS socket bind failed; falling back to plain socket")
        }
        // Plain fallback (v1 behavior).
        return runCatching {
            val s = Socket()
            s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = 0
            s
        }.getOrNull()
    }

    /** Local IP addresses on [network] (link addresses); empty on failure. */
    fun localIps(context: Context, network: Network): List<String> {
        if (!RcsFeature.enabled) return emptyList()
        return runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?: return emptyList()
            val props = cm.getLinkProperties(network) ?: return emptyList()
            props.linkAddresses.mapNotNull { it.address?.hostAddress }
                .filter { !it.startsWith("127.") && it != "::1" }
        }.getOrDefault(emptyList())
    }

    /**
     * Bound MSRP listen socket on the IMS PDN: a `ServerSocket` on an
     * ephemeral port, bound to the IMS local address when known.
     *
     * Note: `Network` exposes a client `SocketFactory` but no server-socket
     * factory, so this binds a plain `ServerSocket` explicitly to the IMS
     * local address. Inbound TCP to that address arrives on the IMS PDN at
     * the IP layer regardless of which `Network` created the socket — the
     * routing decision is the peer's (it connects to the address we
     * advertise in `a=path`), not ours.
     *
     * Returns the socket + the local (ip, port) to advertise in `a=path` and
     * `c=`, or null when no IMS network / bind fails. The socket is NOT
     * closed here — the caller owns the accept loop and closes on teardown.
     * Plain-network fallback is deliberately absent: advertising an
     * unroutable address is worse than offering active-only.
     */
    private const val LISTEN_BACKLOG = 16
    suspend fun listenSocket(context: Context): ListenSocket? {
        if (!RcsFeature.enabled) return null
        val ims = imsNetwork(context) ?: return null
        return runCatching {
            val localIp = lastLocalIp
                ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                ?: return null
            val server = ServerSocket()
            // Backlog sized for one peer per pending session plus slack.
            server.bind(InetSocketAddress(localIp, 0), LISTEN_BACKLOG)
            val port = server.localPort.takeIf { it > 0 } ?: run {
                runCatching { server.close() }
                return null
            }
            val ip = localIp.hostAddress ?: return null
            ListenSocket(server = server, localIp = ip, localPort = port)
        }.getOrElse {
            Log.status(TAG, "IMS listen bind failed", it)
            null
        }
    }

    /** An owned IMS-PDN listen socket with its advertised address. */
    data class ListenSocket(
        val server: ServerSocket,
        val localIp: String,
        val localPort: Int,
    ) {
        /** `msrp://ip:port/<session>;tcp` path for `a=path`. */
        fun msrpPath(sessionId: String): String = "msrp://$localIp:$localPort/$sessionId;tcp"

        fun close() = runCatching { server.close() }
    }

    private fun localIpFor(context: Context, network: Network): String? =
        localIps(context, network).firstOrNull { it.contains('.') }
            ?: localIps(context, network).firstOrNull()

    /** Drop the cached network (subId change / teardown). */
    fun reset() {
        cached = null
        lastLocalIp = null
    }
}
