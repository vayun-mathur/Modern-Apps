package com.vayunmathur.maps.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * C5 search view-model: coordinate queries resolve directly, short
 * queries stay empty, and name/category queries run the offline index
 * after the debounce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsSearchVmC5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private fun loadIndex() {
        val dir = Files.createTempDirectory("searchvm5").toFile()
        temp = dir
        val latE7 = 377_749_000
        val lonE7 = -1_224_194_000
        val name = "C5 Vm Cafe"
        File(dir, PoiIndex.NAMES_FILE).writeBytes(name.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
        val index = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(latE7)
        index.putInt(lonE7)
        index.putInt(0)
        index.putShort(1)
        File(dir, PoiIndex.INDEX_FILE).writeBytes(index.array())
        val nameIndex = ByteBuffer.allocate(16 + 5 * 3).order(ByteOrder.LITTLE_ENDIAN)
        nameIndex.put("PNI1".toByteArray(Charsets.US_ASCII))
        nameIndex.putInt(1)
        nameIndex.putInt(1)
        nameIndex.putInt(3)
        nameIndex.putInt(0)
        nameIndex.putInt(0)
        nameIndex.putInt(0)
        // Entries sorted by word: cafe(2), c5(0), vm/car(1).
        nameIndex.put(2.toByte())
        nameIndex.put(0.toByte())
        nameIndex.put(1.toByte())
        File(dir, PoiIndex.NAME_INDEX_FILE).writeBytes(nameIndex.array())
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PSP1".toByteArray(Charsets.US_ASCII))
        header.putInt(1)
        header.putInt(1)
        header.putInt(1)
        header.putInt(latE7)
        header.putInt(lonE7)
        header.putInt(200_000)
        header.putInt(1)
        val cells = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
        val offs = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(1)
        val ords = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
        File(dir, PoiIndex.SPATIAL_FILE)
            .writeBytes(header.array() + cells.array() + offs.array() + ords.array())
        assertTrue(PoiIndex.reload(dir), "fixture did not map")
    }

    private fun settle(vm: MapsSearchViewModel, cond: () -> Boolean) {
        repeat(300) {
            // idleFor also advances the paused clock past the debounce delay.
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (cond()) return
            Thread.sleep(10)
        }
        assertTrue(cond(), "search did not settle: results=${vm.results.value}")
    }

    @Test
    fun `coordinate query resolves to a coordinate result`() {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("37.7749, -122.4194", 0.0, 0.0)
        assertEquals(1, vm.results.value.size)
        assertEquals("coord:37.7749:-122.4194", vm.results.value[0].id)
        assertEquals(false, vm.searching.value)
    }

    @Test
    fun `one character query stays empty`() {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("c", 37.7749, -122.4194)
        assertTrue(vm.results.value.isEmpty())
        assertEquals(false, vm.searching.value)
    }

    @Test
    fun `name query finds the indexed cafe after debounce`() {
        loadIndex()
        val vm = MapsSearchViewModel(app)
        vm.setQuery("vm cafe", 37.7749, -122.4194)
        assertEquals(true, vm.searching.value)
        settle(vm) { vm.results.value.any { it.title == "C5 Vm Cafe" } }
        assertEquals(false, vm.searching.value)
    }

    @Test
    fun `blank and then cleared query empties results`() {
        loadIndex()
        val vm = MapsSearchViewModel(app)
        vm.setQuery("vm cafe", 37.7749, -122.4194)
        settle(vm) { vm.results.value.isNotEmpty() }
        vm.setQuery("", 37.7749, -122.4194)
        settle(vm) { vm.results.value.isEmpty() }
    }
}
