package com.vayunmathur.networklocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineDatabasesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearAll() {
        val names = listOf(
            OfflineDatabases.GEOCODER, OfflineDatabases.WIFI, OfflineDatabases.CELL,
            "geocoder.geodb", "geocoder-v3.geodb", "wifi.wpsdb", "cells.wpsdb",
            "wifi-v2.wpsdb", "cells-v2.wpsdb", "unrelated.txt",
        )
        for (n in names) OfflineDatabases.file(context, n).delete()
    }

    private fun writeGeocoderHeader(magic: Int, version: Int, size: Int = 16) {
        val f = OfflineDatabases.file(context, OfflineDatabases.GEOCODER)
        val bytes = ByteArray(size)
        fun put(at: Int, v: Int) {
            for (i in 0 until 4) bytes[at + i] = (v ushr (24 - 8 * i)).toByte()
        }
        put(0, magic)
        put(4, version)
        f.writeBytes(bytes)
    }

    @Test
    fun `names urls and hashes are the published ones`() {
        assertEquals("geocoder.db", OfflineDatabases.GEOCODER)
        assertEquals("wifi-v3.wpsdb", OfflineDatabases.WIFI)
        assertEquals("cells-v3.wpsdb", OfflineDatabases.CELL)
        assertEquals(
            "https://data.maproj.org/geocoder/geocoder.db",
            OfflineDatabases.urlFor(OfflineDatabases.GEOCODER),
        )
        assertEquals(
            "https://data.maproj.org/wps/wifi-v3.wpsdb",
            OfflineDatabases.urlFor(OfflineDatabases.WIFI),
        )
        assertEquals(
            "https://data.maproj.org/wps/cells-v3.wpsdb",
            OfflineDatabases.urlFor(OfflineDatabases.CELL),
        )
        assertEquals("https://data.maproj.org/wps/other.bin", OfflineDatabases.urlFor("other.bin"))
        assertEquals(64, OfflineDatabases.sha256For(OfflineDatabases.GEOCODER)?.length)
        assertEquals(64, OfflineDatabases.sha256For(OfflineDatabases.WIFI)?.length)
        assertEquals(64, OfflineDatabases.sha256For(OfflineDatabases.CELL)?.length)
        assertNull(OfflineDatabases.sha256For("other.bin"))
        assertNull(OfflineDatabases.sha256For(""))
    }

    @Test
    fun `file lives in device protected files dir`() {
        clearAll()
        val f = OfflineDatabases.file(context, OfflineDatabases.WIFI)
        assertEquals(OfflineDatabases.WIFI, f.name)
        assertEquals(OfflineDatabases.dir(context), f.parentFile)
        assertTrue(OfflineDatabases.dir(context).path.endsWith("files"))
    }

    @Test
    fun `absent and empty stores are not present`() {
        clearAll()
        assertFalse(OfflineDatabases.isPresent(context, OfflineDatabases.WIFI))
        assertFalse(OfflineDatabases.allPresent(context))
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(ByteArray(0))
        assertFalse(OfflineDatabases.isPresent(context, OfflineDatabases.WIFI))
        assertFalse(OfflineDatabases.allPresent(context))
    }

    @Test
    fun `non-geocoder stores are present when non-empty`() {
        clearAll()
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1, 2, 3))
        OfflineDatabases.file(context, OfflineDatabases.CELL).writeBytes(byteArrayOf(9))
        assertTrue(OfflineDatabases.isPresent(context, OfflineDatabases.WIFI))
        assertTrue(OfflineDatabases.isPresent(context, OfflineDatabases.CELL))
        assertFalse(OfflineDatabases.allPresent(context), "geocoder still missing")
        writeGeocoderHeader(0x4D414745, 5)
        assertTrue(OfflineDatabases.allPresent(context))
    }

    @Test
    fun `geocoder needs the current magic and version`() {
        clearAll()
        writeGeocoderHeader(0x4D414745, 5)
        assertTrue(OfflineDatabases.isPresent(context, OfflineDatabases.GEOCODER))
        writeGeocoderHeader(0x4D414745, 4)
        assertFalse(OfflineDatabases.isPresent(context, OfflineDatabases.GEOCODER), "old version counts absent")
        writeGeocoderHeader(0x12345678, 5)
        assertFalse(OfflineDatabases.isPresent(context, OfflineDatabases.GEOCODER))
        // Too short to hold a header at all.
        OfflineDatabases.file(context, OfflineDatabases.GEOCODER).writeBytes(byteArrayOf(0x4D, 0x41))
        assertFalse(OfflineDatabases.isPresent(context, OfflineDatabases.GEOCODER))
    }

    @Test
    fun `prune stale deletes superseded names only and is repeatable`() {
        clearAll()
        val superseded = listOf(
            "geocoder.geodb", "geocoder-v3.geodb", "wifi.wpsdb", "cells.wpsdb",
            "wifi-v2.wpsdb", "cells-v2.wpsdb",
        )
        for (n in superseded) OfflineDatabases.file(context, n).writeBytes(byteArrayOf(1))
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1))
        OfflineDatabases.file(context, "unrelated.txt").writeBytes(byteArrayOf(1))
        OfflineDatabases.pruneStale(context)
        for (n in superseded) {
            assertFalse(OfflineDatabases.file(context, n).exists(), "$n should be pruned")
        }
        assertTrue(OfflineDatabases.file(context, OfflineDatabases.WIFI).exists())
        assertTrue(OfflineDatabases.file(context, "unrelated.txt").exists())
        OfflineDatabases.pruneStale(context) // second call is a no-op, not an error
        assertTrue(OfflineDatabases.file(context, OfflineDatabases.WIFI).exists())
    }

    @Test
    fun `open read only returns null for missing or empty and a descriptor otherwise`() {
        clearAll()
        assertNull(OfflineDatabases.openReadOnly(context, OfflineDatabases.WIFI))
        val f = OfflineDatabases.file(context, OfflineDatabases.WIFI)
        f.writeBytes(ByteArray(0))
        assertNull(OfflineDatabases.openReadOnly(context, OfflineDatabases.WIFI))
        f.writeBytes(byteArrayOf(7, 8, 9, 10))
        val pfd = OfflineDatabases.openReadOnly(context, OfflineDatabases.WIFI)
        assertNotNull(pfd)
        assertEquals(4L, pfd.statSize)
        pfd.close()
    }
}
