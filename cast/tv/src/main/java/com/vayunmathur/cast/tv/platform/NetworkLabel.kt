package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.vayunmathur.library.log.Log
import java.net.NetworkInterface

private const val TAG = "NetworkLabel"

/**
 * A best-effort "On HomeWi-Fi · 192.168.1.4" line for the idle screen, or empty when it cannot be
 * read.
 *
 * Two independent halves, joined only when both exist: the SSID comes from `WifiManager` (quoted
 * on older platforms, bare `<unknown ssid>` when the platform withholds it without location
 * access), and the IPv4 address is the first non-loopback site-local address on an up interface.
 * Reading fails open - no permission here is worth asking for, because the line is a nicety and
 * the screen is complete without it.
 */
object NetworkLabel {

    /** Number of bytes in an IPv4 address, as returned by `InetAddress.getAddress()`. */
    private const val IPV4_ADDRESS_SIZE = 4

    fun of(context: Context): String {
        val ssid = runCatching { wifiSsid(context) }.getOrNull()
        val ip = runCatching { lanIpv4() }.getOrNull()
        return when {
            ssid != null && ip != null -> "$ssid · $ip"
            ssid != null -> ssid
            ip != null -> ip
            else -> ""
        }
    }

    private fun wifiSsid(context: Context): String? {
        @Suppress("DEPRECATION")
        val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.connectionInfo
            ?: return null
        // On Wi-Fi only: an Ethernet-connected TV has no SSID to show rather than a wrong one.
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        if (!active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return info.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && !it.startsWith("<unknown") }
    }

    private fun lanIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull()
            ?: run {
                Log.status(TAG, "no network interfaces visible")
                return null
            }
        return interfaces.asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.address.size == IPV4_ADDRESS_SIZE && !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.isNotBlank() }
    }
}
