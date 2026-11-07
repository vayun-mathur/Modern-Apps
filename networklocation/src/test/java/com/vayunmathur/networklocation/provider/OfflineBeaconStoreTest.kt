package com.vayunmathur.networklocation.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.BeaconKeys
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.RadioType
import com.vayunmathur.networklocation.WpsRec
import com.vayunmathur.networklocation.writeWpsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineBeaconStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val ap = BeaconId.Wifi("00:11:22:33:44:55")
    private val apUnknown = BeaconId.Wifi("00:aa:bb:cc:dd:ee")
    private val cell = BeaconId.Cell(310, 260, RadioType.LTE, 4242, 1234)

    private fun clearStores() {
        OfflineDatabases.file(context, OfflineDatabases.WIFI).delete()
        OfflineDatabases.file(context, OfflineDatabases.CELL).delete()
    }

    private fun installWifiStore() {
        writeWpsStore(
            OfflineDatabases.file(context, OfflineDatabases.WIFI),
            BeaconKeys.WIFI_UNIVERSE_BITS,
            listOf(
                WpsRec(0L, BeaconKeys.parseMac(ap.bssid)!!, 3_742_150_000L, -12_208_400_000L, 25L, 10L),
                // Unknown accuracy (0xFFFF sentinel) and unknown altitude.
                WpsRec(0L, BeaconKeys.parseMac(apUnknown.bssid)!!, 4_000_000_000L, -7_000_000_000L, 0xFFFFL, null),
            ),
        )
    }

    private fun installCellStore() {
        writeWpsStore(
            OfflineDatabases.file(context, OfflineDatabases.CELL),
            BeaconKeys.CELL_UNIVERSE_BITS,
            listOf(
                WpsRec(
                    BeaconKeys.cellKeyHi(cell), BeaconKeys.cellKeyLo(cell),
                    5_100_000_000L, 0L, 80L, null,
                ),
            ),
        )
    }

    @Test
    fun `missing stores resolve nothing and describe as closed`() {
        clearStores()
        val store = OfflineBeaconStore(context)
        assertTrue(store.resolveWifi(listOf(ap)).isEmpty())
        assertTrue(store.resolveCell(listOf(cell)).isEmpty())
        assertTrue(store.resolveWifi(emptyList()).isEmpty())
        assertTrue(store.resolveCell(emptyList()).isEmpty())
        val desc = store.describe()
        assertTrue(desc.contains("wifi=closed"), desc)
        assertTrue(desc.contains("cell=closed"), desc)
        store.close()
        store.close() // idempotent
    }

    @Test
    fun `wifi hits resolve with coordinates accuracy and altitude`() {
        clearStores()
        installWifiStore()
        val store = OfflineBeaconStore(context)
        assertTrue(store.describe().contains("wifi=open"), store.describe())
        val out = store.resolveWifi(listOf(ap, BeaconId.Wifi("00:99:88:77:66:55")))
        assertEquals(1, out.size, "absent AP is omitted, not invented")
        val fix = out.getValue(ap)
        assertEquals(ap, fix.id)
        assertEquals(37.4215, fix.latitude, 1e-6)
        assertEquals(-122.084, fix.longitude, 1e-6)
        assertEquals(25.0, fix.accuracyMeters, 1e-6)
        assertEquals(10.0, fix.altitudeMeters!!, 1e-6)
        store.close()
    }

    @Test
    fun `unknown accuracy becomes the conservative default and unknown altitude is null`() {
        clearStores()
        installWifiStore()
        val store = OfflineBeaconStore(context)
        val fix = store.resolveWifi(listOf(apUnknown)).getValue(apUnknown)
        assertEquals(40.0, fix.latitude, 1e-6)
        assertEquals(-70.0, fix.longitude, 1e-6)
        assertEquals(100.0, fix.accuracyMeters, "negative native accuracy must be substituted")
        assertNull(fix.altitudeMeters, "native NaN altitude must become null")
        store.close()
    }

    @Test
    fun `malformed and randomized bssids never resolve`() {
        clearStores()
        installWifiStore()
        val store = OfflineBeaconStore(context)
        val out = store.resolveWifi(
            listOf(
                BeaconId.Wifi("not-a-mac"),
                BeaconId.Wifi("02:11:22:33:44:55"), // locally administered
                BeaconId.Wifi("01:11:22:33:44:55"), // multicast
                BeaconId.Wifi(""),
            ),
        )
        assertTrue(out.isEmpty())
        store.close()
    }

    @Test
    fun `cell hits resolve and absent cells are omitted`() {
        clearStores()
        installCellStore()
        val store = OfflineBeaconStore(context)
        assertTrue(store.describe().contains("cell=open"), store.describe())
        val absent = BeaconId.Cell(310, 260, RadioType.LTE, 9999, 1234)
        val out = store.resolveCell(listOf(cell, absent))
        assertEquals(1, out.size)
        val fix = out.getValue(cell)
        assertEquals(51.0, fix.latitude, 1e-6)
        assertEquals(0.0, fix.longitude, 1e-6)
        assertEquals(80.0, fix.accuracyMeters, 1e-6)
        assertNull(fix.altitudeMeters)
        store.close()
    }

    @Test
    fun `a store that arrives after open is picked up on the next lookup`() {
        clearStores()
        val store = OfflineBeaconStore(context)
        assertTrue(store.resolveWifi(listOf(ap)).isEmpty())
        installWifiStore()
        val out = store.resolveWifi(listOf(ap))
        assertEquals(1, out.size, "downloaded store must be reopened without a restart")
        store.close()
        assertTrue(store.describe().contains("wifi=closed"), store.describe())
    }

    @Test
    fun `explicit reopen releases and reopens handles`() {
        clearStores()
        installWifiStore()
        installCellStore()
        val store = OfflineBeaconStore(context)
        store.open()
        assertEquals(1, store.resolveWifi(listOf(ap)).size)
        assertEquals(1, store.resolveCell(listOf(cell)).size)
        store.close()
    }

    @Test
    fun `a truncated store file leaves the handle closed instead of crashing`() {
        clearStores()
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1, 2, 3))
        val store = OfflineBeaconStore(context)
        assertTrue(store.resolveWifi(listOf(ap)).isEmpty())
        assertTrue(store.describe().contains("wifi=closed"), store.describe())
        store.close()
    }
}
