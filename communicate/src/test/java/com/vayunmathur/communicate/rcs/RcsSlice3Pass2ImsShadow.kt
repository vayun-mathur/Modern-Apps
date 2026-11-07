package com.vayunmathur.communicate.rcs

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork

/**
 * A fake IMS PDN for slice-3 pass 2: one network with IMS capability, link
 * properties carrying a wildcard local address (bindable, and not filtered
 * out as loopback by the production local-IP scan) plus a loopback P-CSCF.
 * Lets the IMS network, MSRP listen and direct-SIP layers run end to end
 * over loopback sockets.
 */
@Implements(ConnectivityManager::class)
class RcsSlice3Pass2ImsShadow : ShadowConnectivityManager() {

    @Implementation
    override fun getAllNetworks(): Array<Network> = arrayOf(imsNetwork)

    @Implementation
    override fun getNetworkCapabilities(network: Network?): NetworkCapabilities = imsCapabilities

    @Implementation
    override fun getLinkProperties(network: Network?): LinkProperties = imsLinkProperties()

    @Implementation
    override fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        callback.onAvailable(imsNetwork)
    }

    companion object {
        val imsNetwork: Network = ShadowNetwork.newInstance(770_001)

        val imsCapabilities: NetworkCapabilities by lazy {
            val ctor = NetworkCapabilities::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance().also { caps ->
                val addCapability = NetworkCapabilities::class.java
                    .getMethod("addCapability", Int::class.javaPrimitiveType)
                addCapability.invoke(caps, NetworkCapabilities.NET_CAPABILITY_IMS)
                addCapability.invoke(caps, NetworkCapabilities.NET_CAPABILITY_MMTEL)
                NetworkCapabilities::class.java
                    .getMethod("addTransportType", Int::class.javaPrimitiveType)
                    .invoke(caps, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        }

        fun imsLinkProperties(): LinkProperties {
            val ctor = LinkProperties::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val props = ctor.newInstance()
            val linkAddress = LinkAddress::class.java
                .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(InetAddress.getByName("0.0.0.0"), 0)
            LinkProperties::class.java.getMethod("addLinkAddress", LinkAddress::class.java)
                .invoke(props, linkAddress)
            // P-CSCF list (hidden field; name varies by SDK level).
            val field = runCatching { LinkProperties::class.java.getDeclaredField("mPcscfServers") }
                .getOrElse { LinkProperties::class.java.getDeclaredField("mPcscfs") }
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(props) as MutableList<InetAddress>).add(InetAddress.getByName("127.0.0.1"))
            return props
        }
    }
}
