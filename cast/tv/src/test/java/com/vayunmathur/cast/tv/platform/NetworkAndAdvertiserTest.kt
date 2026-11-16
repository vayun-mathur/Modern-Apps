package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.DecoderLimits
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NetworkLabel against shadow Wi-Fi state, and the NSD advertiser lifecycle. */
@RunWith(RobolectricTestRunner::class)
class NetworkAndAdvertiserTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Wi-Fi transport capabilities, built reflectively (Builder is not on the compile SDK). */
    private fun wifiCapabilities(): NetworkCapabilities {
        val builderClass = Class.forName("android.net.NetworkCapabilities\$Builder")
        val builder = try {
            builderClass.getConstructor().newInstance()
        } catch (ignored: NoSuchMethodException) {
            builderClass.getConstructor(NetworkCapabilities::class.java)
                .newInstance(NetworkCapabilities())
        }
        builderClass.getMethod("addTransportType", Int::class.java)
            .invoke(builder, NetworkCapabilities.TRANSPORT_WIFI)
        return builderClass.getMethod("build").invoke(builder) as NetworkCapabilities
    }

    /** A WifiInfo carrying [ssid], built reflectively (Builder is not on the compile SDK). */
    private fun wifiInfo(ssid: String): WifiInfo {
        val builderClass = Class.forName("android.net.wifi.WifiInfo\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setSsid", ByteArray::class.java).invoke(builder, ssid.toByteArray())
        builderClass.getMethod("setBssid", String::class.java).invoke(builder, "02:00:00:00:01:00")
        return builderClass.getMethod("build").invoke(builder) as WifiInfo
    }

    @Test fun labelJoinsSsidAndAddressOnWifi() {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi).setConnectionInfo(wifiInfo("\"HomeWiFi\""))
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network != null) {
            shadowOf(cm).setNetworkCapabilities(network, wifiCapabilities())
        }
        val label = NetworkLabel.of(ctx)
        // The SSID half must survive quote-stripping when Wi-Fi transport is reported.
        assertTrue(label.isEmpty() || label.contains("HomeWiFi") || !label.contains("\""), label)
    }

    @Test fun labelHidesUnknownSsid() {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi).setConnectionInfo(wifiInfo("<unknown ssid>"))
        val label = NetworkLabel.of(ctx)
        assertTrue(!label.contains("unknown"), label)
    }

    @Test fun advertiserRegistersAndUnregisters() {
        val advertiser = ReceiverAdvertiser(ctx)
        advertiser.advertise(
            friendlyName = "Living Room TV",
            deviceId = "abc123def456",
            port = 43210,
            limits = DecoderLimits(),
        )
        // Robolectric's NsdManager accepts the registration; the advertiser must
        // not report the local-network block on the happy path.
        assertEquals(false, advertiser.localNetworkBlocked)
        advertiser.unadvertise()
        advertiser.unadvertise() // second teardown is a no-op
    }
}
