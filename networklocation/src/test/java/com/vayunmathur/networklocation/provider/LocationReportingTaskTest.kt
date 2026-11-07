package com.vayunmathur.networklocation.provider

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.networklocation.BeaconKeys
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.WpsRec
import com.vayunmathur.networklocation.writeWpsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocationReportingTaskTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setWifi(bssids: List<String>) {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wm).setScanResults(
            bssids.map { bssid ->
                ScanResult().apply {
                    BSSID = bssid
                    SSID = "test"
                }
            },
        )
    }

    private fun clearTelephony() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setAllCellInfo(emptyList())
    }

    private fun clearStores() {
        OfflineDatabases.file(context, OfflineDatabases.WIFI).delete()
        OfflineDatabases.file(context, OfflineDatabases.CELL).delete()
    }

    @Test
    fun `a resolved wifi beacon produces a network location fix`() {
        clearStores()
        clearTelephony()
        val mac = BeaconKeys.parseMac("00:11:22:33:44:55")!!
        writeWpsStore(
            OfflineDatabases.file(context, OfflineDatabases.WIFI),
            BeaconKeys.WIFI_UNIVERSE_BITS,
            listOf(WpsRec(0L, mac, 3_742_150_000L, -12_208_400_000L, 25L, null)),
        )
        setWifi(listOf("00:11:22:33:44:55"))

        val latch = CountDownLatch(1)
        val fixes = mutableListOf<Location>()
        val task = LocationReportingTask(context) { loc ->
            fixes.add(loc)
            latch.countDown()
        }
        task.start(60_000L)
        task.start(60_000L) // a second start while running is a no-op
        assertTrue(latch.await(10, TimeUnit.SECONDS), "expected a fix from the resolved beacon")
        task.stop()
        task.stop() // idempotent

        assertEquals(1, fixes.size)
        val fix = fixes[0]
        assertEquals(LocationManager.NETWORK_PROVIDER, fix.provider)
        assertEquals(37.4215, fix.latitude, 5e-4)
        assertEquals(-122.084, fix.longitude, 5e-4)
        assertTrue(fix.hasAccuracy())
        assertTrue(fix.accuracy > 0f)
        assertTrue(fix.time > 0L)
    }

    @Test
    fun `no scanned beacons produces no fix`() {
        clearStores()
        clearTelephony()
        setWifi(emptyList())
        val latch = CountDownLatch(1)
        val task = LocationReportingTask(context) { latch.countDown() }
        task.start(60_000L)
        assertFalse(latch.await(3, TimeUnit.SECONDS), "no beacons must mean no fix")
        task.stop()
    }

    @Test
    fun `scanned but unresolved beacons produce no fix`() {
        clearStores()
        clearTelephony()
        setWifi(listOf("00:11:22:33:44:55"))
        val latch = CountDownLatch(1)
        val task = LocationReportingTask(context) { latch.countDown() }
        task.start(60_000L)
        assertFalse(
            latch.await(3, TimeUnit.SECONDS),
            "a beacon the stores do not know must not invent a position",
        )
        task.stop()
    }

    @Test
    fun `interval below the floor is clamped and stop halts the loop`() {
        clearStores()
        clearTelephony()
        setWifi(emptyList())
        val task = LocationReportingTask(context) { }
        task.start(0L)
        task.stop()
        // Restarting after stop works: a fresh scope is created.
        task.start(0L)
        task.stop()
    }
}
