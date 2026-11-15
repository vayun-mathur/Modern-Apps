package com.vayunmathur.share.platform.airdrop.link

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.RouteInfo
import android.os.Build
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import com.vayunmathur.library.log.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.delay

private const val TAG = "MoseyNetwork"
private const val INTERFACE = "mosey0"
private const val INTERFACE_WAIT_MS = 5_000L
private const val INTERFACE_POLL_MS = 100L
private const val LINK_LOCAL_PREFIX = 64

/** `NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK`, passed as Mosey does (`byx`). */
private const val KEEP_CONNECTED_LOCAL_NETWORK = 3

private const val TRANSPORT_WIFI_AWARE = NetworkCapabilities.TRANSPORT_WIFI_AWARE

/**
 * `mosey0` registered with ConnectivityService, so NsdManager can browse `_airdrop._tcp` on it
 * and sockets can be bound to it. Shape copied from Mosey's `MoseyNetwork`: Wi-Fi Aware
 * transport, not-metered and not-VPN, an `fe80::/64` route, legacy type name
 * `WIFI_AWARE_FACTORY`, and a request held for its lifetime so it is never "unwanted".
 *
 * The hidden framework members it needs are reached reflectively, which is why it only works on
 * a build that whitelists :share for hidden-API access.
 */
@SuppressLint("PrivateApi")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MoseyNetworkAgent private constructor(
    private val context: Context,
    private val thread: HandlerThread,
    private val agent: NetworkAgent,
    private val callback: ConnectivityManager.NetworkCallback,
) {
    val network: Network? get() = runCatching { agent.network }.getOrNull()

    fun tearDown() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(callback) }
        runCatching { agent.unregister() }
        thread.quitSafely()
    }

    /** Shared constants and helpers for MoseyNetworkAgent. */
    companion object {
        suspend fun bringUp(context: Context): MoseyNetworkAgent? {
            val iface = awaitInterface() ?: return null
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val thread = HandlerThread("MoseyNetworkThread").apply { start() }
            val looper = thread.looper
            val caps = capabilities()
            val lp = linkProperties(iface)
            val provider = registerProvider(context, cm, looper)
            val config = NetworkAgentConfig.Builder().setLegacyTypeName("WIFI_AWARE_FACTORY").build()
            val score = NetworkScore.Builder().setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK).build()
            val agent = object : NetworkAgent(context, looper, "MoseyNetworkAgent", caps, lp, score, config, provider) {
                override fun onNetworkUnwanted() {
                    Log.status(TAG, "mosey0 network unwanted")
                }
            }
            agent.register()
            agent.markConnected()
            val callback = object : ConnectivityManager.NetworkCallback() {}
            cm.requestNetwork(
                NetworkRequest.Builder().clearCapabilities().addTransportType(TRANSPORT_WIFI_AWARE).build(),
                callback,
            )
            Log.status(TAG, "mosey0 up as ${agent.network} with ${lp.linkAddresses}")
            return MoseyNetworkAgent(context, thread, agent, callback)
        }

        /**
         * Registers Mosey's `NetworkProvider`, or returns null. Registration needs NETWORK_FACTORY
         * (signature|role), which a priv-app cannot hold; an agent with no provider is still
         * accepted under CREATE_APP_SPECIFIC_NETWORK, so this is only a nicety.
         */
        private fun registerProvider(
            context: Context,
            cm: ConnectivityManager,
            looper: android.os.Looper,
        ): NetworkProvider? {
            val provider = NetworkProvider(context, looper, "MoseyNetworkProvider")
            return runCatching {
                ConnectivityManager::class.java
                    .getMethod("registerNetworkProvider", NetworkProvider::class.java)
                    .invoke(cm, provider)
                provider
            }.onFailure { Log.dev(TAG, "no NetworkProvider (${it.cause ?: it}); registering agent without one") }
                .getOrNull()
        }

        private fun hasLinkLocalIpv6(iface: NetworkInterface): Boolean =
            iface.inetAddresses.toList().any { address ->
                val linkLocalIpv6 = address is Inet6Address && address.isLinkLocalAddress
                linkLocalIpv6
            }

        private fun isUsableInterface(iface: NetworkInterface?): Boolean {
            if (iface == null || !iface.isUp) return false
            return hasLinkLocalIpv6(iface)
        }

        private suspend fun awaitInterface(): NetworkInterface? {
            var waited = 0L
            while (waited < INTERFACE_WAIT_MS) {
                val iface = runCatching { NetworkInterface.getByName(INTERFACE) }.getOrNull()
                if (isUsableInterface(iface)) {
                    return iface
                }
                delay(INTERFACE_POLL_MS)
                waited += INTERFACE_POLL_MS
            }
            return null
        }

        /**
         * `withoutDefaultCapabilities` and the builder it returns are hidden from the public SDK,
         * so they are reached reflectively.
         */
        private fun capabilities(): NetworkCapabilities {
            val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
            val builder = builderClass.getMethod("withoutDefaultCapabilities").invoke(null)
            val addTransport = builderClass.getMethod("addTransportType", Int::class.javaPrimitiveType)
            val addCapability = builderClass.getMethod("addCapability", Int::class.javaPrimitiveType)
            addTransport.invoke(builder, TRANSPORT_WIFI_AWARE)
            addCapability.invoke(builder, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            addCapability.invoke(builder, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            return builderClass.getMethod("build").invoke(builder) as NetworkCapabilities
        }

        /**
         * The interface, its IPv6 addresses and an `fe80::/64` route. The setters and the
         * `LinkAddress`/`RouteInfo` constructors used are hidden members of public classes.
         */
        private fun linkProperties(iface: NetworkInterface): LinkProperties {
            val lp = LinkProperties()
            val cls = LinkProperties::class.java
            cls.getMethod("setInterfaceName", String::class.java).invoke(lp, INTERFACE)
            val newAddress = LinkAddress::class.java
                .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            val addAddress = cls.getMethod("addLinkAddress", LinkAddress::class.java)
            iface.interfaceAddresses.filter { it.address is Inet6Address }.forEach {
                addAddress.invoke(lp, newAddress.newInstance(it.address, it.networkPrefixLength.toInt()))
            }
            val route = RouteInfo::class.java
                .getConstructor(IpPrefix::class.java, InetAddress::class.java, String::class.java)
                .newInstance(IpPrefix(InetAddress.getByName("fe80::"), LINK_LOCAL_PREFIX), null, INTERFACE)
            cls.getMethod("addRoute", RouteInfo::class.java).invoke(lp, route)
            return lp
        }
    }
}
