package com.vayunmathur.maps.car

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.util.PoiIndex
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/**
 * C5 car search execution: a voice transcript runs the offline index and
 * publishes hits; navigation to a hit with a location fix but no graph
 * reports the route failure instead of starting guidance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsCarNavC5Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private fun field(screen: CarSearchScreen, name: String): Any? {
        val f = CarSearchScreen::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(screen)
    }

    private fun call(screen: CarSearchScreen, name: String, vararg args: Any?) {
        val m = CarSearchScreen::class.java.declaredMethods.first { it.name == name }
        m.isAccessible = true
        m.invoke(screen, *args)
    }

    /** Minimal index fixture with one cafe (mirrors PoiIndexSmartTest). */
    private fun loadIndex() {
        val dir = Files.createTempDirectory("carc5").toFile()
        temp = dir
        val latE7 = 377_749_000
        val lonE7 = -1_224_194_000
        val name = "C5 Car Cafe"
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
        // CarSearchScreen initializes the index from the app's own dirs;
        // point search at the already-mapped fixture by seeding its context
        // path the same way initialize() would find no files (it keeps the
        // current mapping when files are absent).
    }

    @Test
    fun `run search publishes index hits`() {
        loadIndex()
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        call(screen, "runSearch", "car cafe")
        // Search runs on IO; wait for the results to land.
        repeat(200) {
            Thread.sleep(10)
            @Suppress("UNCHECKED_CAST")
            val results = field(screen, "results") as List<PoiIndex.PoiRecord>
            if (results.isNotEmpty()) {
                assertEquals("C5 Car Cafe", results[0].name)
                return
            }
        }
        @Suppress("UNCHECKED_CAST")
        val results = field(screen, "results") as List<PoiIndex.PoiRecord>
        assertTrue(results.isNotEmpty(), "search found no hits")
        assertEquals("C5 Car Cafe", results[0].name)
    }

    @Test
    fun `run search with no hits reports the empty message`() {
        loadIndex()
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        call(screen, "runSearch", "zzzzqqqq")
        repeat(200) {
            Thread.sleep(10)
            if (field(screen, "loading") == false) {
                assertEquals(
                    carContext.getString(com.vayunmathur.maps.R.string.car_no_results),
                    field(screen, "message"),
                )
                return
            }
        }
        assertEquals(
            carContext.getString(com.vayunmathur.maps.R.string.car_no_results),
            field(screen, "message"),
        )
    }

    @Test
    fun `navigation with a fix but no graph reports route failure`() {
        loadIndex()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.7749
            longitude = -122.4194
        }
        (shadowOf(lm) as ShadowLocationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, loc)
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        val hit = PoiIndex.PoiRecord(377_749_000, -1_224_194_000, 1, "C5 Car Cafe", 0)
        call(screen, "startNavigationTo", hit)
        repeat(300) {
            Thread.sleep(10)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (field(screen, "message") == carContext.getString(com.vayunmathur.maps.R.string.car_route_failed)) {
                return
            }
        }
        assertEquals(
            carContext.getString(com.vayunmathur.maps.R.string.car_route_failed),
            field(screen, "message"),
        )
    }
}
