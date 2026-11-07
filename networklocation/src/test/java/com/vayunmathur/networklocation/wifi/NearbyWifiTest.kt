package com.vayunmathur.networklocation.wifi

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.networklocation.BeaconId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NearbyWifiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun scanResult(bssid: String?): ScanResult {
        val r = ScanResult()
        r.BSSID = bssid
        r.SSID = "test"
        return r
    }

    private fun setResults(results: List<ScanResult>) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wm).setScanResults(results)
    }

    @Test
    fun `scan returns fixed aps deduplicated`() {
        setResults(
            listOf(
                scanResult("00:11:22:33:44:55"),
                scanResult("00:11:22:33:44:55"),
                scanResult("10:22:33:44:55:66"),
            ),
        )
        assertEquals(
            listOf(BeaconId.Wifi("00:11:22:33:44:55"), BeaconId.Wifi("10:22:33:44:55:66")),
            NearbyWifi(context).scan(),
        )
    }

    @Test
    fun `scan drops null blank placeholder randomized and multicast bssids`() {
        setResults(
            listOf(
                scanResult(null),
                scanResult(""),
                scanResult("   "),
                scanResult("00:00:00:00:00:00"),
                scanResult("02:11:22:33:44:55"), // locally administered
                scanResult("01:11:22:33:44:55"), // multicast
                scanResult("zz:11:22:33:44:55"), // malformed
                scanResult("00:11:22:33:44"), // malformed
                scanResult("00:11:22:33:44:55"),
            ),
        )
        assertEquals(listOf(BeaconId.Wifi("00:11:22:33:44:55")), NearbyWifi(context).scan())
    }

    @Test
    fun `no scan results yields an empty list`() {
        setResults(emptyList())
        assertTrue(NearbyWifi(context).scan().isEmpty())
    }
}
